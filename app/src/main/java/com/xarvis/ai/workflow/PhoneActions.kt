package com.xarvis.ai.workflow

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.xarvis.ai.tools.ContactsDirectory
import com.xarvis.ai.tools.PermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Things Gemma can do on the phone: calls, WhatsApp and SMS, flashlight, alarms and timers,
 * Bluetooth and Wi-Fi. Calls open the dialer and messages open ready to send, so the user
 * always makes the final tap.
 */
class PhoneActions(context: Context) {

    private val appContext = context.applicationContext
    private val contacts = ContactsDirectory(appContext)

    // ---- Calls and messages ----

    /**
     * [direct]: Rex typed "call ..." himself, so the call is placed (asking for the call permission
     * once). Otherwise the dialer opens with the number ready and he taps the call button, so a
     * misunderstanding by Gemma can never phone someone.
     */
    suspend fun call(target: String, direct: Boolean): StepResult {
        val who = resolve(target).let { it as? Resolved.Found ?: return (it as Resolved.Problem).result }
        if (direct) {
            if (!PermissionGate.has(appContext, CALL)) PermissionGate.request(CALL)
            if (PermissionGate.has(appContext, CALL)) {
                return start(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", who.number, null)), "Calling ${who.display}.")
            }
        }
        return start(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", who.number, null)),
            "Opened the dialer for ${who.display}. Tap the green call button to call.",
        )
    }

    suspend fun whatsApp(target: String, text: String?): StepResult {
        val who = resolve(target).let { it as? Resolved.Found ?: return (it as Resolved.Problem).result }
        val phone = international(who.number).filter { it.isDigit() }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone" + (text?.let { "&text=" + Uri.encode(it) } ?: ""))
        val done = if (text == null) "Opened WhatsApp with ${who.display}."
        else "Opened WhatsApp with your message to ${who.display}. Tap Send to send it."
        for (pkg in WHATSAPP_PACKAGES) {
            try {
                appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return StepResult(true, done)
            } catch (e: ActivityNotFoundException) {
                // not this WhatsApp; try the next one
            }
        }
        return StepResult(false, "WhatsApp isn't installed on this phone.")
    }

    suspend fun sms(target: String, text: String): StepResult {
        val who = resolve(target).let { it as? Resolved.Found ?: return (it as Resolved.Problem).result }
        return start(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", who.number, null)).putExtra("sms_body", text),
            "Opened a text message to ${who.display}. Tap Send to send it.",
        )
    }

    private sealed interface Resolved {
        class Found(val number: String, val display: String) : Resolved
        class Problem(val result: StepResult) : Resolved
    }

    /** A typed number, or the best-matching contact's number. */
    private suspend fun resolve(target: String): Resolved {
        val t = target.trim()
        if (PHONE_NUMBER.matches(t)) return Resolved.Found(t.filter { it.isDigit() || it == '+' }, t)
        if (!PermissionGate.has(appContext, READ_CONTACTS)) PermissionGate.request(READ_CONTACTS)
        if (!PermissionGate.has(appContext, READ_CONTACTS)) {
            return problem("I need permission to read your contacts to find $t. Allow it when I ask, or say the number instead.")
        }
        val words = ContactsDirectory.nameWords(t)
        val matches = withContext(Dispatchers.IO) { contacts.search(words) }
        if (matches.isEmpty()) return problem("I couldn't find \"$t\" in your contacts.")
        val exact = matches.filter { it.name.equals(t, ignoreCase = true) }
        val top = exact.ifEmpty { matches.filter { it.score == matches.first().score } }
        if (top.size > 1) {
            return problem(
                "I found more than one \"$t\": " + top.take(4).joinToString { it.name } + ". Say the full name."
            )
        }
        val person = top.first()
        val number = person.bestNumber ?: return problem("${person.name} has no phone number saved.")
        return Resolved.Found(number.value, person.name)
    }

    private fun problem(message: String) = Resolved.Problem(StepResult(false, message))

    /** WhatsApp wants the number with its country code; add this phone's when the contact has none. */
    private fun international(number: String): String {
        if (number.trim().startsWith("+")) return number
        val tm = appContext.getSystemService(TelephonyManager::class.java)
        val country = listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase(Locale.US) ?: return number
        return PhoneNumberUtils.formatNumberToE164(number, country) ?: number
    }

    // ---- Flashlight ----

    fun flashlight(on: Boolean): StepResult {
        val cameras = appContext.getSystemService(CameraManager::class.java)
            ?: return StepResult(false, "This phone has no flashlight.")
        val id = runCatching {
            cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return StepResult(false, "This phone has no flashlight.")
        return try {
            cameras.setTorchMode(id, on)
            StepResult(true, if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            StepResult(false, "I couldn't switch the flashlight ${if (on) "on" else "off"}: the camera is busy.")
        }
    }

    // ---- Alarms and timers ----

    fun alarm(hour: Int, minute: Int, label: String?): StepResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val shown = String.format(Locale.US, "%d:%02d %s", (hour + 11) % 12 + 1, minute, if (hour < 12) "AM" else "PM")
        return start(intent, "Alarm set for $shown" + (label?.let { " ($it)" } ?: "") + ".")
    }

    fun timer(seconds: Int): StepResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return start(intent, "Timer set for ${duration(seconds)}.")
    }

    private fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
            m.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
            s.takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
        ).joinToString(" ")
    }

    // ---- Bluetooth and Wi-Fi ----

    @SuppressLint("MissingPermission") // checked before use
    suspend fun bluetooth(on: Boolean): StepResult {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return StepResult(false, "This phone has no Bluetooth.")
        if (adapter.isEnabled == on) return StepResult(true, "Bluetooth is already ${if (on) "on" else "off"}.")
        if (on) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!PermissionGate.has(appContext, BT_CONNECT)) PermissionGate.request(BT_CONNECT)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || PermissionGate.has(appContext, BT_CONNECT)) {
                // Android shows its own "turn on Bluetooth?" prompt.
                val asked = start(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "Tap Allow to turn Bluetooth on.")
                if (asked.success) return asked
            }
        }
        // Android doesn't let apps turn Bluetooth off (or on without the permission); open the switch instead.
        return start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Android doesn't let apps do that directly, so I opened Bluetooth settings. Use the switch at the top.")
    }

    fun wifi(on: Boolean): StepResult {
        val enabled = runCatching { appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
        if (enabled == on) return StepResult(true, "Wi-Fi is already ${if (on) "on" else "off"}.")
        // Apps can't switch Wi-Fi since Android 10; the quick panel is one tap away.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
        else Intent(Settings.ACTION_WIFI_SETTINGS)
        return start(intent, "Android doesn't let apps switch Wi-Fi, so I opened the Wi-Fi switch for you.")
    }

    private fun start(intent: Intent, successMessage: String): StepResult = try {
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        StepResult(true, successMessage)
    } catch (e: ActivityNotFoundException) {
        StepResult(false, "Nothing on this phone can do that.")
    } catch (e: SecurityException) {
        StepResult(false, "Android didn't allow that: ${e.message}")
    }

    companion object {
        private const val CALL = Manifest.permission.CALL_PHONE
        private const val READ_CONTACTS = Manifest.permission.READ_CONTACTS
        private const val BT_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val PHONE_NUMBER = Regex("""^\+?[\d\s\-()]{5,}$""")
    }
}
