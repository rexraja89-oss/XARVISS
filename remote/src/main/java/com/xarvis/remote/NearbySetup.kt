package com.xarvis.remote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Linking a new phone that's right next to this one (Rex, 15 Nov 2026: "just touching that phone with S22 Ultra
 * get the connection done"). Phone-to-phone NFC ("Android Beam") was removed from Android 10, and Bluetooth/Wi-Fi
 * need the app on both phones first, so it's done with the camera instead — the nearest thing to a touch:
 * 1. this phone shows a code with the download link; the new phone's camera opens it, and XARVIS Remote installs;
 * 2. this phone shows the invite as a code; the new phone scans it from inside XARVIS Remote and links.
 */
object NearbySetup {

    /** On the controlling phone (S22): the two codes, one after the other, the screen at full brightness. */
    fun show(activity: Activity, invite: String) {
        val step1 = page(
            activity, "Step 1 of 2 — install the app",
            "On the NEW phone: open the Camera and point it at this code. Tap the link it shows, download XARVIS Remote and " +
                "install it (if Android warns: Settings → Allow from this source; Play Protect: More details → Install anyway). " +
                "Already installed? Tap Next.",
            Updates.APK_URL,
        )
        val step2 = page(
            activity, "Step 2 of 2 — link",
            "On the NEW phone: open XARVIS Remote → tap “📷 Scan the code on my other phone” and point it at this code. " +
                "It links by itself. (The code holds your relay login — show it only to your own phone.)",
            invite,
        )
        val first = AlertDialog.Builder(activity).setView(step1).setNegativeButton("Close", null).create()
        first.setButton(AlertDialog.BUTTON_POSITIVE, "Next →") { _, _ ->
            AlertDialog.Builder(activity).setView(step2).setPositiveButton("Done", null).create().also { bright(it) }.show()
        }
        bright(first)
        first.show()
    }

    /** On the new phone: scans the invite the other phone shows; [found] gets its text. Nothing to install. */
    fun scan(activity: Activity, found: (String) -> Unit, failed: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        // The scanner comes with Google Play services; on a brand-new phone it may need fetching once.
        ModuleInstall.getClient(activity).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
            .addOnCompleteListener {
                scanner.startScan()
                    .addOnSuccessListener { code -> code.rawValue?.let(found) ?: failed("That code was empty. Try again.") }
                    .addOnFailureListener { e ->
                        failed("The scanner didn't start (${e.message ?: "Google Play services?"}). Use the invite instead: on the other phone ➕ → Share.")
                    }
            }
    }

    private fun page(activity: Activity, title: String, help: String, content: String): LinearLayout {
        val dp = activity.resources.displayMetrics.density
        val size = (activity.resources.displayMetrics.widthPixels * 0.75f).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(activity).apply { text = title; textSize = 20f; setTextColor(Color.parseColor("#B71C1C")) })
            addView(TextView(activity).apply { text = help; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, pad / 2, 0, pad) })
            addView(
                ImageView(activity).apply { setImageBitmap(qr(content, size)) },
                LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
        }
    }

    /** Full brightness while a code is up, so the other camera reads it at once. */
    private fun bright(dialog: AlertDialog) {
        dialog.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
