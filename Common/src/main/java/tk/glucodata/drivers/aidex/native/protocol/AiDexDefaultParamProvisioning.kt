// JugglucoNG — AiDex Native Kotlin Driver
// AiDexDefaultParamProvisioning.kt — Local compare helpers for AiDex default params
//
// This mirrors the safe/read-only half of the official OtaManager default-param
// pipeline:
//   - use official OTA settingContent resources as candidate sources
//   - normalize the captured 0x31 blob into the packed compare shape
//   - derive the same firmware version key the official app uses
//   - validate CRC-8/MAXIM on settingContent
//   - build candidate DP and compare without writing anything back yet
//
// The current catalog snapshot comes from the rooted official mgdl app's local
// ObjectBox OTA cache. It is still a snapshot, not a live server-backed fetch,
// but it is no longer a hand-picked seed list.

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

    private val officialCatalog = AiDexOfficialDpCatalogSnapshot.entries

    fun normalizeCatalogModelName(modelName: String?): String? {
        if (modelName.isNullOrBlank()) return null
        val normalized = modelName.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
        return when {
            normalized.contains("GX01S") -> "1034_GX01S"
            normalized.contains("GX02S") -> "1034_GX02S"
            normalized.contains("GX03S") -> "1034_GX03S"
            normalized.contains("GXXXS14") -> "1034_GXXXS_14"
            normalized.contains("GXXXS16") -> "1034_GXXXS_16"
            normalized.contains("GXXXS7") -> "1034_GXXXS_7"
            normalized.startsWith("1034GX01S") -> "1034_GX01S"
            normalized.startsWith("1034GX02S") -> "1034_GX02S"
            normalized.startsWith("1034GX03S") -> "1034_GX03S"
            normalized.startsWith("1034GXXXS14") -> "1034_GXXXS_14"
            normalized.startsWith("1034GXXXS16") -> "1034_GXXXS_16"
            normalized.startsWith("1034GXXXS7") -> "1034_GXXXS_7"
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
        val matches = officialCatalog.filter { it.settingType == settingType && it.aidexVersion == "X" }
        val versionKey = deriveCatalogVersionKey(firmwareVersion) ?: return matches.sortedBy { it.version }
        val exact = matches.filter { it.version == versionKey }
        val remainder = matches.filterNot { it.version == versionKey }
            .sortedWith(
                compareBy<CatalogEntry> { versionPriority(it.version, versionKey) }
                    .thenByDescending { majorMinorMatches(it.version, versionKey) }
                    .thenBy { it.version }
            )
        return exact + remainder
    }

    private fun deriveCatalogVersionKey(firmwareVersion: String?): String? {
        if (firmwareVersion.isNullOrBlank()) return null
        val token = firmwareVersion.trim().substringBefore(' ').substringBefore('(')
        val parts = token.split('.').filter { it.isNotBlank() }
        val numeric = parts.isNotEmpty() && parts.all { part -> part.all(Char::isDigit) }
        return when {
            token.isBlank() -> null
            numeric && parts.size >= 4 -> parts.dropLast(1).joinToString(".")
            else -> token
        }
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
