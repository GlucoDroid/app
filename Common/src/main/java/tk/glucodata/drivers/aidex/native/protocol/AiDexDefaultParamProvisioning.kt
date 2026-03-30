// JugglucoNG — AiDex Native Kotlin Driver
// AiDexDefaultParamProvisioning.kt — Local compare helpers for AiDex default params
//
// This mirrors the safe/read-only half of the official OtaManager default-param
// pipeline:
//   - use captured OTA settingContent resources as candidate sources
//   - normalize the captured 0x31 blob into a broad comparable shape
//   - validate CRC-8/MAXIM on settingContent
//   - build candidate DP and compare without writing anything back yet
//
// Important: the compare path is still diagnostic/read-only, but the 2026-03-31
// forced official probe gave one grounded reference point:
//   GX-01S / 1.8.1 -> parameters_x_1.6.1.0.ini
// with packed 0x301 payload compare and preserve range 16..23.
// Older 1.7.x / 1.2.x / 1.3.x entries remain best-effort until we capture the
// same official branch for those families.

package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim
import java.util.Locale

object AiDexDefaultParamProvisioning {

    data class CatalogEntry(
        val settingType: String,
        val version: String,
        val aidexVersion: String,
        val settingVersion: String,
        val settingContent: String,
    )

    data class CurrentVariant(
        val hex: String,
        val headerSwapApplied: Boolean,
    ) {
        val byteCount: Int get() = hex.length / 2
        val versionHex: String get() = hex.take(8)
    }

    data class Candidate(
        val entry: CatalogEntry,
        val candidateHex: String,
        val candidateBaseHex: String,
        val crcValid: Boolean,
        val localCrcHex: String,
        val onlineCrcHex: String,
    ) {
        val byteCount: Int get() = candidateHex.length / 2
        val versionHex: String get() = candidateHex.take(8)
    }

    data class Comparison(
        val current: CurrentVariant,
        val candidate: Candidate,
        val diffByteCount: Int,
        val exactMatch: Boolean,
    ) {
        val entry: CatalogEntry get() = candidate.entry
    }

    // Seeded from the captured official endpoint response in
    // c-logs/nonroot-livepair-probe2.log. This is intentionally limited to the
    // known GX0xS families we have actually observed, and is used only for
    // compare/diagnostics until the write-side path is justified.
    private val knownCatalog = listOf(
        CatalogEntry(
            settingType = "1034_GX01S",
            version = "1.8.1",
            aidexVersion = "X",
            settingVersion = "parameters_x_1.6.1.0.ini",
            settingContent = "0106010080C61300840338FF6E006D006B006E007100100E302AD06BB0FFC4FFECFF00000000100E302AD06B0A0000000000C4092800FA00740E2003EE020A000A000800FA0019007D000802AA009CFF640000001100E803B80B32005500D601280046001E0032006400020014002003B004050014005A003200EE021E005A005A00F401F4019033B04F000000000000000000000000000000000000000000000000000000000000000000B7",
        ),
        CatalogEntry(
            settingType = "1034_GX01S",
            version = "1.2.0",
            aidexVersion = "X",
            settingVersion = "parameters_x_1.0.5.0.ini",
            settingContent = "0100050080C61300840338FF78006900610062006200D020F03CD06B92FFA6FFB0FFBAFFC4FFD020F03CD06B0A0000000000E8032800FA00E40CF4010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003E",
        ),
        CatalogEntry(
            settingType = "1034_GX02S",
            version = "1.2.0",
            aidexVersion = "X",
            settingVersion = "parameters_x_1.0.5.0.ini",
            settingContent = "01000500002F0D00840338FF78006900610062006200D020F03CD06B92FFA6FFB0FFBAFFC4FFD020F03CD06B0A0000000000E8032800FA00E40CF40100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000052",
        ),
        CatalogEntry(
            settingType = "1034_GX03S",
            version = "1.2.0",
            aidexVersion = "X",
            settingVersion = "parameters_x_1.0.5.0.ini",
            settingContent = "01000500008C0A00840338FF78006900610062006200D020F03CD06B92FFA6FFB0FFBAFFC4FFD020F03CD06B0A0000000000E8032800FA00E40CF401000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000F0",
        ),
        CatalogEntry(
            settingType = "1034_GX01S",
            version = "1.3.0",
            aidexVersion = "X",
            settingVersion = "parameters_x_1.0.11.0.ini",
            settingContent = "01000B0080C61300840338FF62006100610061006300100EF03CD06B00000000000014001E00100EF03CD06B0A0000000000E8032800FA00740E8A022A003200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000081",
        ),
        CatalogEntry(
            settingType = "1034_GX02S",
            version = "1.3.0",
            aidexVersion = "X",
            settingVersion = "parameters_x_1.0.11.0.ini",
            settingContent = "01000B00002F0D00840338FF62006100610061006300100EF03CD06B00000000000014001E00100EF03CD06B0A0000000000E8032800FA00740E8A022A0032000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000ED",
        ),
    )

    fun normalizeCatalogModelName(modelName: String?): String? {
        if (modelName.isNullOrBlank()) return null
        val normalized = modelName.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        return when {
            normalized.contains("GX01S") -> "1034_GX01S"
            normalized.contains("GX02S") -> "1034_GX02S"
            normalized.contains("GX03S") -> "1034_GX03S"
            normalized.startsWith("1034GX01S") -> "1034_GX01S"
            normalized.startsWith("1034GX02S") -> "1034_GX02S"
            normalized.startsWith("1034GX03S") -> "1034_GX03S"
            else -> null
        }
    }

    fun compareKnownCatalog(currentRawHex: String, modelName: String?, firmwareVersion: String? = null): List<Comparison> {
        val settingType = normalizeCatalogModelName(modelName) ?: return emptyList()
        val variants = normalizeCurrentVariants(currentRawHex)
        if (variants.isEmpty()) return emptyList()

        return candidateCatalogEntries(settingType, firmwareVersion).asSequence()
            .mapNotNull { entry ->
                variants.mapNotNull { variant ->
                    val candidate = buildCandidate(variant.hex, entry) ?: return@mapNotNull null
                    val diffBytes = diffByteCount(variant.hex, candidate.candidateHex)
                    Comparison(
                        current = variant,
                        candidate = candidate,
                        diffByteCount = diffBytes,
                        exactMatch = diffBytes == 0 && variant.hex.length == candidate.candidateHex.length,
                    )
                }.minWithOrNull(
                    compareBy<Comparison> { it.diffByteCount }
                        .thenBy { if (it.current.headerSwapApplied) 1 else 0 }
                )
            }
            .sortedWith(
                compareBy<Comparison> { it.diffByteCount }
                    .thenBy { if (it.current.headerSwapApplied) 1 else 0 }
                    .thenBy { it.entry.version }
            )
            .toList()
    }

    fun normalizeCurrentVariants(currentRawHex: String): List<CurrentVariant> {
        val upper = currentRawHex.trim().uppercase(Locale.US)
        if (upper.length < 12 || upper.length % 2 != 0 || !upper.all(::isHexChar)) return emptyList()

        val withoutLead = upper.drop(2)
        if (withoutLead.length < 8) return emptyList()

        // Official GET_DEFAULT_PARAM handling compares BleMessage.data as-is
        // (binaryToHex on the packed payload). The earlier "swap header" variant
        // was our local guess, and the vendor oracle on working 1.7.1 / 1.8.1
        // did not justify keeping it as a canonical compare path.
        return listOf(CurrentVariant(hex = withoutLead, headerSwapApplied = false))
    }

    private fun buildCandidate(oldDpHex: String, entry: CatalogEntry): Candidate? {
        val settingContent = entry.settingContent.trim().uppercase(Locale.US)
        if (settingContent.length < 10 || settingContent.length % 2 != 0 || !settingContent.all(::isHexChar)) {
            return null
        }

        val crcPayloadHex = settingContent.dropLast(2)
        val onlineCrcHex = settingContent.takeLast(2)
        val payloadBytes = hexToBytes(crcPayloadHex) ?: return null
        val localCrcHex = "%02X".format(Crc8Maxim.checksum(payloadBytes))
        val crcValid = localCrcHex.equals(onlineCrcHex, ignoreCase = true)
        if (!crcValid) {
            return Candidate(
                entry = entry,
                candidateHex = settingContent.dropLast(8),
                candidateBaseHex = settingContent.dropLast(8),
                crcValid = false,
                localCrcHex = localCrcHex,
                onlineCrcHex = onlineCrcHex,
            )
        }

        val candidateBaseHex = settingContent.dropLast(8)
        var candidateHex = candidateBaseHex

        // Official OtaManager preserves one small "no change" slice from the old
        // DP before deciding whether to write. Our captured/normalized DP blob is
        // still a best-effort reconstruction of that compare string, so apply the
        // same range only when the candidate and current shapes line up cleanly.
        if (oldDpHex.length == candidateBaseHex.length && candidateBaseHex.length >= 24) {
            val preserveRange = if (oldDpHex.length == 0xA8) 8..15 else 16..23
            val preserved = oldDpHex.substring(preserveRange.first, preserveRange.last + 1)
            candidateHex = candidateBaseHex.replaceRange(preserveRange.first, preserveRange.last + 1, preserved)
        }

        return Candidate(
            entry = entry,
            candidateHex = candidateHex,
            candidateBaseHex = candidateBaseHex,
            crcValid = true,
            localCrcHex = localCrcHex,
            onlineCrcHex = onlineCrcHex,
        )
    }

    private fun candidateCatalogEntries(settingType: String, firmwareVersion: String?): List<CatalogEntry> {
        val matches = knownCatalog.filter { it.settingType == settingType && it.aidexVersion == "X" }
        val normalizedFw = normalizeFirmwareVersion(firmwareVersion)
        if (normalizedFw == null) return matches
        return matches.sortedWith(
            compareBy<CatalogEntry> { versionPriority(it.version, normalizedFw) }
                .thenByDescending { it.version == normalizedFw }
                .thenByDescending { majorMinorMatches(it.version, normalizedFw) }
                .thenBy { it.version }
        )
    }

    private fun normalizeFirmwareVersion(firmwareVersion: String?): String? {
        if (firmwareVersion.isNullOrBlank()) return null
        return firmwareVersion.trim().substringBefore(' ').substringBefore('(')
    }

    private fun versionPriority(entryVersion: String, firmwareVersion: String): Int {
        return when {
            entryVersion == firmwareVersion -> 0
            majorMinorMatches(entryVersion, firmwareVersion) -> 1
            else -> 2
        }
    }

    private fun majorMinorMatches(entryVersion: String, firmwareVersion: String): Boolean {
        val left = entryVersion.split('.')
        val right = firmwareVersion.split('.')
        return left.size >= 2 && right.size >= 2 && left[0] == right[0] && left[1] == right[1]
    }

    private fun diffByteCount(a: String, b: String): Int {
        val byteCount = minOf(a.length, b.length) / 2
        var diffs = 0
        for (index in 0 until byteCount) {
            val off = index * 2
            if (!a.regionMatches(off, b, off, 2, ignoreCase = true)) {
                diffs++
            }
        }
        diffs += kotlin.math.abs((a.length / 2) - (b.length / 2))
        return diffs
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || !hex.all(::isHexChar)) return null
        return ByteArray(hex.length / 2) { index ->
            val off = index * 2
            ((hexDigit(hex[off]) shl 4) or hexDigit(hex[off + 1])).toByte()
        }
    }

    private fun isHexChar(ch: Char): Boolean {
        return (ch in '0'..'9') || (ch in 'A'..'F') || (ch in 'a'..'f')
    }

    private fun hexDigit(ch: Char): Int {
        return when (ch) {
            in '0'..'9' -> ch - '0'
            in 'A'..'F' -> ch - 'A' + 10
            in 'a'..'f' -> ch - 'a' + 10
            else -> 0
        }
    }
}
