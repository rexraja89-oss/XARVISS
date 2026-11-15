package com.xarvis.remote

import android.nfc.NdefMessage
import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * "Touch to link" (Rex, 15 Nov 2026: "like sci-fi movies, just by touching a phone it connects"). While the S22
 * shows 📱 Set up a phone that's next to me, it acts as an NFC tag — the standard kind (NFC Forum Type 4) every
 * Android phone reads — the same way it acts as a card for paying. Touch 1 holds the download link (a phone
 * without the app just opens it); touch 2 holds the invite, which XARVIS Remote on the other phone reads.
 * The rest of the time it answers nothing.
 */
class TouchTagService : HostApduService() {

    private var file: ByteArray? = null

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val message = armed ?: return NOT_FOUND
        if (apdu.size >= 5 && apdu[0] == 0.toByte() && apdu[1] == 0xA4.toByte()) {
            val n = apdu[4].toInt() and 0xFF
            if (apdu.size < 5 + n) return NOT_FOUND
            val data = apdu.copyOfRange(5, 5 + n)
            return when {
                apdu[2] == 0x04.toByte() && data.contentEquals(NDEF_APP) -> OK.also { file = null }
                apdu[2] == 0x00.toByte() && data.contentEquals(CC_ID) -> OK.also { file = CC }
                apdu[2] == 0x00.toByte() && data.contentEquals(NDEF_ID) -> {
                    val bytes = message.toByteArray()
                    file = byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
                    OK
                }
                else -> NOT_FOUND
            }
        }
        if (apdu.size >= 5 && apdu[0] == 0.toByte() && apdu[1] == 0xB0.toByte()) {
            val f = file ?: return NOT_FOUND
            val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            val want = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
            if (offset > f.size) return WRONG_OFFSET
            return f.copyOfRange(offset, minOf(f.size, offset + want)) + OK
        }
        return NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        file = null
    }

    companion object {
        /** What the touch gives right now; null = not set up (answers nothing). */
        @Volatile
        var armed: NdefMessage? = null

        private val NDEF_APP = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val CC_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04)

        /** Capability container: version 2.0, up to 240 bytes a read, the NDEF file E104 (up to 2 KB, read-only). */
        private val CC = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0xF0.toByte(), 0x00, 0xF0.toByte(),
            0x04, 0x06, 0xE1.toByte(), 0x04, 0x08, 0x00, 0x00, 0xFF.toByte(),
        )
        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
        private val NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
