package com.xarvis.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands an APK to Android's own installer (a PackageInstaller session). Android checks the signature as always: only
 * an APK signed with Rex's key can replace XARVIS, and an update keeps all of XARVIS's data.
 *
 * **Without the "update?" prompt (13 Nov 2026, Rex: "update all at one time"):** each session asks for
 * USER_ACTION_NOT_REQUIRED, which Android 12+ honours for an installer holding UPDATE_PACKAGES_WITHOUT_USER_ACTION when
 * the app is an update the Updater installed last time (or the Updater itself). Anything else — a first install, an
 * app last installed from Chrome, older Android — gets Android's usual prompt, exactly as before.
 */
object Installer {
    private const val ACTION = "com.xarvis.updater.INSTALL_RESULT"

    /** Who's waiting for each session's answer. */
    private val waiting = ConcurrentHashMap<Int, (Outcome) -> Unit>()

    sealed interface Outcome {
        /** Android's "update this app?" screen (only an app on screen may open it). */
        data class Confirm(val intent: Intent, val session: Int = -1) : Outcome
        data object Installed : Outcome
        data object Cancelled : Outcome

        /** Android wanted a tap and there was no screen to ask on (an update XARVIS asked for in the background). */
        data object NeedsTap : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** Starts the install; [onOutcome] gets Android's answers on the main thread. Returns the session id. */
    fun install(context: Context, apk: File, packageName: String, onOutcome: (Outcome) -> Unit): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        waiting[id] = onOutcome
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            waiting.remove(id)
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    /**
     * Installs [apk] and waits for the end. If Android asks anyway, [onConfirm] shows its screen (Rex taps Update);
     * with no [onConfirm] (nobody on screen) the install is given up and the answer is [Outcome.NeedsTap].
     */
    suspend fun installAndWait(
        context: Context, apk: File, packageName: String, onConfirm: ((Intent) -> Unit)?, timeoutMs: Long = 15 * 60_000L,
    ): Outcome {
        val end = CompletableDeferred<Outcome>()
        // Writing a 107 MB APK into the session: never on the screen's thread.
        val session = withContext(Dispatchers.IO) {
            install(context, apk, packageName) { o ->
                when {
                    o !is Outcome.Confirm -> end.complete(o)
                    onConfirm != null -> onConfirm(o.intent)
                    else -> {
                        abandon(context, o.session)
                        end.complete(Outcome.NeedsTap)
                    }
                }
            }
        }
        return withTimeoutOrNull(timeoutMs) { end.await() } ?: run {
            abandon(context, session)
            Outcome.Failed("Android didn't answer in ${timeoutMs / 60_000} minutes")
        }
    }

    private fun abandon(context: Context, session: Int) {
        if (session < 0) return
        waiting.remove(session)
        runCatching { context.packageManager.packageInstaller.abandonSession(session) }
    }

    /** Hands [outcome] to whoever waits for [session] (false: nobody — this app was restarted meanwhile). */
    internal fun deliver(session: Int, outcome: Outcome): Boolean {
        val who = (if (outcome is Outcome.Confirm) waiting[session] else waiting.remove(session)) ?: return false
        Handler(Looper.getMainLooper()).post { who(outcome) }
        return true
    }
}

/** Android's answers about the install. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val outcome = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "update this app?" screen: Rex taps Update there.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { Installer.Outcome.Confirm(it, session) } ?: Installer.Outcome.Failed("Android didn't ask to confirm")
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.Outcome.Installed
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.Outcome.Cancelled
            else -> Installer.Outcome.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused it")
        }
        if (!Installer.deliver(session, outcome) && outcome is Installer.Outcome.Confirm) {
            // Nobody's waiting any more (the Updater was restarted): show Android's screen anyway, if it may.
            runCatching { context.startActivity(outcome.intent) }
        }
    }
}
