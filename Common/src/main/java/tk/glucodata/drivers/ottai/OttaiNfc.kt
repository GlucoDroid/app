// JugglucoNG — Ottai CGM driver
// OttaiNfc.kt — read-only NFC wake detection and diagnostics.
//
// Ottai generations expose different NFC technologies. Some Chinese sensors
// expose only raw NFC-A: presenting the RF field wakes them, but Android offers
// no standard memory interface to read. Never send an undocumented transceive
// command here; detecting the supported tag technology is sufficient to tell
// the setup flow that the wake tap happened.
//
// Enabled only while the setup wizard's "NFC dump" mode is on; otherwise taps
// fall straight through to the normal (Libre) NFC handler in MainActivity.

package tk.glucodata.drivers.ottai

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcV
import android.os.SystemClock
import tk.glucodata.Applic
import tk.glucodata.Log

object OttaiNfc {
    private const val TAG = "OttaiNfc"
    // Some Android NFC stacks dispatch the same physical tap to the generic
    // foreground handler several seconds after the reader-mode callback.
    private const val CONSUMED_TAG_WINDOW_MS = 30_000L
    private const val NFC_A_TECH = "android.nfc.tech.NfcA"
    private const val NFC_V_TECH = "android.nfc.tech.NfcV"
    private const val MIFARE_ULTRALIGHT_TECH = "android.nfc.tech.MifareUltralight"

    internal enum class WakeInterface {
        NFC_V,
        MIFARE_ULTRALIGHT,
        NFC_A_FIELD_ONLY,
        UNSUPPORTED,
    }

    data class Result(
        val details: String,
        val wakeDetected: Boolean,
    )

    /** When true, MainActivity.startnfc routes the tag here instead of to Libre. */
    @Volatile var dumpMode: Boolean = false

    /** Last dump text + an optional UI callback (set by the wizard). */
    @Volatile var lastDump: String? = null
    @Volatile var onResult: ((Result) -> Unit)? = null
    @Volatile private var consumedTagId: String? = null
    @Volatile private var consumeTagUntilMs: Long = 0L
    @Volatile private var wakeHapticPending: Boolean = false
    @Volatile private var activationSensorId: String? = null

    fun armForSetup() {
        dumpMode = true
    }

    fun armForActivationRetry(sensorId: String) {
        activationSensorId = OttaiConstants.canonicalSensorId(sensorId)
        dumpMode = true
    }

    fun disarmActivationRetry(sensorId: String) {
        val canonical = OttaiConstants.canonicalSensorId(sensorId)
        if (activationSensorId.equals(canonical, ignoreCase = true)) {
            activationSensorId = null
            dumpMode = false
        }
    }

    @JvmStatic
    fun consumeWakeHaptic(): Boolean {
        if (!wakeHapticPending) return false
        wakeHapticPending = false
        return true
    }

    /** Returns true if the tag belongs to the active/recent Ottai wake flow. */
    @JvmStatic
    fun onTag(tag: Tag): Boolean {
        val tagId = hex(tag.id)
        if (!dumpMode) {
            return tagId == consumedTagId && SystemClock.elapsedRealtime() <= consumeTagUntilMs
        }
        val wakeInterface = classifyTechs(tag.techList)
        val details = runCatching { dump(tag, wakeInterface) }
            .getOrElse { "NFC inspection error: $it" }
        val result = Result(details, wakeInterface != WakeInterface.UNSUPPORTED)
        if (result.wakeDetected) {
            consumedTagId = tagId
            consumeTagUntilMs = SystemClock.elapsedRealtime() + CONSUMED_TAG_WINDOW_MS
            wakeHapticPending = true
            dumpMode = false
            activationSensorId?.let { sensorId ->
                activationSensorId = null
                Applic.app?.let { OttaiNfcWakeReminder.cancel(it, sensorId) }
            }
        }
        lastDump = details
        Log.i(TAG, "\n$details")
        runCatching { onResult?.invoke(result) }
        return true
    }

    internal fun classifyTechs(techs: Array<String>): WakeInterface = when {
        techs.contains(NFC_V_TECH) -> WakeInterface.NFC_V
        techs.contains(MIFARE_ULTRALIGHT_TECH) -> WakeInterface.MIFARE_ULTRALIGHT
        techs.contains(NFC_A_TECH) -> WakeInterface.NFC_A_FIELD_ONLY
        else -> WakeInterface.UNSUPPORTED
    }

    private fun hex(b: ByteArray?): String =
        b?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: "null"

    private fun dump(tag: Tag, wakeInterface: WakeInterface): String {
        val sb = StringBuilder()
        val uid = tag.id
        sb.append("UID=").append(hex(uid))
            .append("  techs=").append(tag.techList.joinToString(",")).append('\n')
        return when (wakeInterface) {
            WakeInterface.NFC_V -> dumpNfcV(sb, tag, uid)
            WakeInterface.MIFARE_ULTRALIGHT -> dumpMifareUltralight(sb, tag)
            WakeInterface.NFC_A_FIELD_ONLY -> sb.append(
                "NFC-A wake field detected; no standard readable memory interface exposed",
            ).toString()
            WakeInterface.UNSUPPORTED -> sb.append("unsupported NFC technology").toString()
        }
    }

    /** ISO15693 read-only dump. Tries non-addressed mode, falls back to addressed. */
    private fun dumpNfcV(sb: StringBuilder, tag: Tag, uid: ByteArray): String {
        val nfcv = NfcV.get(tag) ?: return sb.append("NfcV interface unavailable").toString()
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

    /** Some Ottai M8 sensors expose Mifare Ultralight. Dump all readable pages. */
    private fun dumpMifareUltralight(sb: StringBuilder, tag: Tag): String {
        val mu = MifareUltralight.get(tag)
            ?: return sb.append("MifareUltralight interface unavailable").toString()
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
