package tk.glucodata.update

/**
 * Pure data + comparison logic for the in-app updater. Everything here is free of Android
 * dependencies so it can be unit tested directly (see `Common/src/test/.../update/`).
 *
 * Distribution model: JugglucoNG ships as a direct APK on GitHub Releases. The updater reads
 * the release list, picks the artifact that matches *this* build's applicationId, and hands the
 * downloaded file to the platform [android.content.pm.PackageInstaller]. Android still owns the
 * final confirmation dialog; nothing here installs silently.
 */

/**
 * Where releases are read from, as `owner/repository`. User-adjustable so a fork — or a
 * mirror of this project — can be followed instead of the default.
 *
 * Pointing this somewhere hostile still cannot install anything: the APK's signing certificate
 * has to match the installed app's, which no third party can produce.
 */
@JvmInline
value class UpdateSource(val repository: String) {
    val isValid: Boolean get() = PATTERN.matches(repository)

    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,100}$")

        fun sanitize(raw: String): String = raw.trim().trim('/')

        fun isValid(raw: String): Boolean = PATTERN.matches(sanitize(raw))
    }
}

/** One downloadable APK belonging to a release. */
data class UpdateArtifact(
    val fileName: String,
    /**
     * Always taken from the GitHub API's own asset listing, never from a field inside
     * `update-manifest.json`. A tampered manifest therefore cannot point the download
     * somewhere else.
     */
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String? = null
)

/** A release that is newer than what is installed, already narrowed to this build's variant. */
data class AvailableUpdate(
    /** Base version name as released, e.g. `1.2.0-Alpha` (no `-phone`/`DUB` build suffixes). */
    val versionName: String,
    /** Present only when the release carries an `update-manifest.json` asset. */
    val versionCode: Int?,
    val tagName: String,
    val notes: String,
    val publishedAtMillis: Long,
    val prerelease: Boolean,
    val artifact: UpdateArtifact
) {
    /** Stable identity for "the user already dismissed this one". */
    val identity: String get() = "$tagName/${artifact.fileName}"
}

/** Why a check or download failed. Mapped to a string resource by the UI. */
enum class UpdateError {
    NETWORK,
    RATE_LIMITED,
    PARSE,
    NO_ARTIFACT,
    CHECKSUM,
    SIGNATURE,
    PACKAGE_MISMATCH,
    DOWNGRADE,
    STORAGE,
    INSTALL_PERMISSION,
    INSTALL_FAILED,
    CANCELLED
}

/** Result of one update check. */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AvailableUpdate) : UpdateCheckResult
    data class Failed(val error: UpdateError) : UpdateCheckResult
}

/**
 * Version-name ordering for JugglucoNG tags (`1.1.0-Alpha`, `1.0.9-Alpha`, `v1.2.0`).
 *
 * Only used as a fallback: once a release carries an `update-manifest.json` with a
 * `versionCode`, that integer decides. Release tags have never contained a version code, so
 * releases published before the manifest existed still need a name comparison to be usable.
 */
object AppVersion {

    /** Standard semver-ish ordering: numeric parts first, then qualifier, release > pre-release. */
    fun compare(left: String, right: String): Int {
        val a = parse(left)
        val b = parse(right)
        val size = maxOf(a.numbers.size, b.numbers.size)
        for (i in 0 until size) {
            val cmp = (a.numbers.getOrNull(i) ?: 0).compareTo(b.numbers.getOrNull(i) ?: 0)
            if (cmp != 0) return cmp
        }
        // No qualifier means a final release, which outranks any qualifier ("1.2.0" > "1.2.0-Alpha").
        if (a.qualifier.isEmpty() != b.qualifier.isEmpty()) {
            return if (a.qualifier.isEmpty()) 1 else -1
        }
        return compareQualifiers(a.qualifier, b.qualifier)
    }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private data class Parsed(val numbers: List<Int>, val qualifier: String)

    private fun parse(raw: String): Parsed {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val separator = trimmed.indexOfFirst { it == '-' || it == '+' || it == '_' }
        val head = if (separator >= 0) trimmed.substring(0, separator) else trimmed
        val tail = if (separator >= 0) trimmed.substring(separator + 1) else ""
        val numbers = head.split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return Parsed(numbers, tail)
    }

    /**
     * Digit-aware compare so `Alpha10` sorts after `Alpha2` rather than before it, which a plain
     * lexicographic compare would get wrong.
     */
    private fun compareQualifiers(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val lc = left[i]
            val rc = right[j]
            if (lc.isDigit() && rc.isDigit()) {
                var iEnd = i
                while (iEnd < left.length && left[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < right.length && right[jEnd].isDigit()) jEnd++
                val lNum = left.substring(i, iEnd).toLongOrNull() ?: 0L
                val rNum = right.substring(j, jEnd).toLongOrNull() ?: 0L
                if (lNum != rNum) return lNum.compareTo(rNum)
                i = iEnd
                j = jEnd
            } else {
                val cmp = lc.lowercaseChar().compareTo(rc.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (left.length - i).compareTo(right.length - j)
    }
}

/**
 * Maps this build's applicationId onto the release-asset naming scheme produced by
 * `Common/build.gradle` (`JugglucoNG-<version>[-wear][-<build type>].apk`).
 *
 * Offering the wrong artifact is not a cosmetic bug: a `-dub` APK carries a different
 * applicationId and would install as a *second* app rather than an update, and a `-wear` APK
 * would simply fail. So the match is exact on the variant tokens, not a "contains" guess.
 */
object UpdateArtifacts {

    private const val PREFIX = "JugglucoNG-"
    private const val SUFFIX = ".apk"

    /** Tokens the filename builder can append. Anything else in the name is version text. */
    private val VARIANT_TOKENS = setOf("wear", "debug", "dub", "dub2")

    /**
     * The variant tokens an artifact must carry to be installable over [applicationId], or null
     * when this build must never self-update (debug builds are signed and named per developer
     * machine and have their own applicationId suffix).
     */
    fun expectedTokens(applicationId: String): Set<String>? = when {
        applicationId.endsWith(".debug") -> null
        applicationId.endsWith(".dub2") -> setOf("dub2")
        applicationId.endsWith(".dub") -> setOf("dub")
        else -> emptySet()
    }

    /** Variant tokens actually present in an asset filename, or null if it is not one of ours. */
    fun tokensOf(assetName: String): Set<String>? {
        if (!assetName.startsWith(PREFIX) || !assetName.endsWith(SUFFIX)) return null
        val body = assetName.removeSuffix(SUFFIX)
        return body.split('-').filterTo(mutableSetOf()) { it.lowercase() in VARIANT_TOKENS }
    }

    /** Picks the single asset that can update [applicationId] in place, if the release has one. */
    fun selectAssetName(assetNames: List<String>, applicationId: String): String? {
        val wanted = expectedTokens(applicationId) ?: return null
        return assetNames.firstOrNull { tokensOf(it) == wanted }
    }
}
