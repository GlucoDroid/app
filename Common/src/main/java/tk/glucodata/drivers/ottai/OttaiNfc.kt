// JugglucoNG — Ottai (Chinese-market CGM) driver
// OttaiNfc.kt — DEBUG ISO15693 (NfcV) memory dumper.
//
// Ottai is a Sinocare OEM whose CGM is an ISO15693 (NFC Type V) tag. A virgin
// sensor stays BLE-dormant until an NFC "activation/wake" write; we don't yet
// know that vendor command. This dumper uses ONLY the standard, read-only
// ISO15693 commands — Get System Info (0x2B) and Read Single Block (0x20) — so it
// can never change sensor state. It dumps UID + system info + full block memory
// to logcat (tag "OttaiNfc") so we can read the sensor's true state/layout and
// work out the activation write.
//
// Enabled only while the setup wizard's "NFC dump" mode is on; otherwise taps
// fall straight through to the normal (Libre) NFC handler in MainActivity.

package tk.glucodata.drivers.ottai

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcV
import tk.glucodata.Log

object OttaiNfc {
    private const val TAG = "OttaiNfc"

    /** When true, MainActivity.startnfc routes the tag here instead of to Libre. */
    @Volatile var dumpMode: Boolean = false

    /** Last dump text + an optional UI callback (set by the wizard). */
    @Volatile var lastDump: String? = null
    @Volatile var onResult: ((String) -> Unit)? = null

    /** Returns true if the tap was consumed (dump mode active). */
    @JvmStatic
    fun onTag(tag: Tag): Boolean {
        if (!dumpMode) return false
        val dump = runCatching { dump(tag) }.getOrElse { "NFC dump error: $it" }
        lastDump = dump
        Log.i(TAG, "\n$dump")
        runCatching { onResult?.invoke(dump) }
        return true
    }

    private fun hex(b: ByteArray?): String =
        b?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: "null"

    /** ISO15693 read-only dump. Tries non-addressed mode, falls back to addressed. */
    private fun dump(tag: Tag): String {
        val sb = StringBuilder()
        val uid = tag.id
        sb.append("UID=").append(hex(uid))
            .append("  techs=").append(tag.techList.joinToString(",")).append('\n')
        val nfcv = NfcV.get(tag) ?: return dumpMifareUltralight(sb, tag)
        nfcv.connect()
        try {
            sb.append("maxTransceive=").append(nfcv.maxTransceiveLength)
                .append(" dsfid=").append(nfcv.dsfId.toInt() and 0xff)
                .append(" respFlags=").append(nfcv.responseFlags.toInt() and 0xff).append('\n')

            // Get System Info (0x2B): non-addressed (flags 0x02) then addressed (0x22 + UID).
            var addressed = false
            var sys = tx(nfcv, byteArrayOf(0x02, 0x2B))
            if (sys == null || (sys.isNotEmpty() && (sys[0].toInt() and 0xff) != 0)) {
                addressed = true
                sys = tx(nfcv, byteArrayOf(0x22, 0x2B) + uid)
            }
            sb.append("GetSystemInfo(").append(if (addressed) "addr" else "non-addr").append("): ")
                .append(hex(sys)).append('\n')

            // Read Single Block (0x20) until an error/NACK response.
            var count = 0
            for (blk in 0 until 256) {
                val cmd = if (addressed) byteArrayOf(0x22, 0x20) + uid + byteArrayOf(blk.toByte())
                else byteArrayOf(0x02, 0x20, blk.toByte())
                val resp = tx(nfcv, cmd)
                if (resp == null || resp.isEmpty() || (resp[0].toInt() and 0xff) != 0) {
                    sb.append("blk ").append(blk).append(": stop (").append(hex(resp)).append(")\n")
                    break
                }
                sb.append("blk %02x: %s\n".format(blk, hex(resp.copyOfRange(1, resp.size))))
                count = blk + 1
            }
            sb.append("== read $count blocks ==")
        } finally {
            runCatching { nfcv.close() }
        }
        return sb.toString()
    }

    /** Ottai V1.5 (M8) sensors are Mifare Ultralight (ISO14443-A). Dump all pages. */
    private fun dumpMifareUltralight(sb: StringBuilder, tag: Tag): String {
        val mu = MifareUltralight.get(tag) ?: return sb.append("not NfcV nor MifareUltralight").toString()
        mu.connect()
        try {
            val maxPages = when (mu.type) {
                MifareUltralight.TYPE_ULTRALIGHT -> 16
                MifareUltralight.TYPE_ULTRALIGHT_C -> 48
                else -> 64
            }
            sb.append("MifareUltralight type=").append(mu.type).append(" maxPages=").append(maxPages).append('\n')
            var page = 0
            while (page < maxPages) {
                val data = runCatching { mu.readPages(page) }.getOrNull() ?: break // 4 pages = 16 bytes
                for (i in 0 until 4) if (page + i < maxPages) {
                    sb.append("pg %02x: %s\n".format(page + i, hex(data.copyOfRange(i * 4, i * 4 + 4))))
                }
                page += 4
            }
        } finally {
            runCatching { mu.close() }
        }
        return sb.toString()
    }

    private fun tx(nfcv: NfcV, cmd: ByteArray): ByteArray? =
        runCatching { nfcv.transceive(cmd) }.getOrNull()
}
