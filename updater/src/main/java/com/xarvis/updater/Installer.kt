package com.xarvis.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Hands an APK to Android's own installer (a PackageInstaller session). Android then shows its usual
 * "Do you want to update this app?" — Rex taps Update — and checks the signature as always: only an APK signed
 * with Rex's key can replace XARVIS, and an update keeps all of XARVIS's data.
 */
object Installer {
    private const val ACTION = "com.xarvis.updater.INSTALL_RESULT"

    /** The screen's listener for the outcome (on the main thread). */
    @Volatile var listener: ((Outcome) -> Unit)? = null

    sealed interface Outcome {
        /** Android's "update this app?" screen, opened by the Updater's screen (an app in front may open it). */
        data class Confirm(val intent: Intent) : Outcome
        data object Installed : Outcome
        data object Cancelled : Outcome
        data class Failed(val why: String) : Outcome
    }

    fun install(context: Context, apk: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun post(outcome: Outcome) {
        Handler(Looper.getMainLooper()).post { listener?.invoke(outcome) }
    }
}

/** Android's answers about the install. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "update this app?" screen: Rex taps Update there.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Installer.listener != null) {
                        Installer.post(Installer.Outcome.Confirm(confirm))
                    } else {
                        runCatching { context.startActivity(confirm) }
                    }
                } else {
                    Installer.post(Installer.Outcome.Failed("Android didn't ask to confirm"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.post(Installer.Outcome.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.post(Installer.Outcome.Cancelled)
            else -> Installer.post(
                Installer.Outcome.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused it"),
            )
        }
    }
}
