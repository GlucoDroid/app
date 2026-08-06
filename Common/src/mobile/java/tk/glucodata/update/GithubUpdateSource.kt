package tk.glucodata.update

import android.os.Build
import tk.glucodata.BuildConfig
import tk.glucodata.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches release metadata from the GitHub Releases API.
 *
 * Hardening that matters here, because this decides what APK the user is later asked to install:
 *  - HTTPS only, and only the two GitHub hosts below;
 *  - the download URL always comes from the API's asset listing, never from a field inside
 *    `update-manifest.json`;
 *  - response bodies are size-capped, so a hostile or broken endpoint cannot exhaust memory.
 */
object GithubUpdateSource {

    private const val LOG_TAG = "AppUpdate"

    private const val API_HOST = "api.github.com"
    private const val RELEASE_HOST = "github.com"
    private const val CDN_HOST = "objects.githubusercontent.com"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    private const val MAX_RELEASES = 15
    private const val MAX_METADATA_BYTES = 512 * 1024

    val userAgent: String = "JugglucoNG/${BuildConfig.BASE_VERSION_NAME} (Android; +https://github.com/${BuildConfig.UPDATE_REPO})"

    /** `https://github.com/<owner>/<repo>/releases/download/` — every asset URL must start with this. */
    private val assetUrlPrefix: String
        get() = "https://$RELEASE_HOST/${BuildConfig.UPDATE_REPO}/releases/download/"

    /**
     * Looks for a release on [channel] that is newer than the running build and carries an
     * artifact for this applicationId.
     */
    fun check(channel: UpdateChannel): UpdateCheckResult {
        val body = when (val response = getText(
            url = "https://$API_HOST/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=$MAX_RELEASES",
            accept = "application/vnd.github+json"
        )) {
            is TextResponse.Ok -> response.body
            is TextResponse.Error -> return UpdateCheckResult.Failed(response.error)
        }

        val releases = runCatching { GithubReleaseParser.parseReleases(body) }.getOrNull()
            ?: return UpdateCheckResult.Failed(UpdateError.PARSE)

        val candidates = releases.filter { channel == UpdateChannel.PRERELEASE || !it.prerelease }
        if (candidates.isEmpty()) return UpdateCheckResult.UpToDate

        // Only the newest release matters. Walking further back would mean offering an older
        // build than the newest published one, which is never what the user wants.
        for (release in candidates) {
            val manifest = release.asset(GithubReleaseParser.MANIFEST_ASSET)
                ?.let { fetchManifest(it.downloadUrl) }
            val update = GithubReleaseParser.toAvailableUpdate(
                release = release,
                manifest = manifest,
                applicationId = BuildConfig.APPLICATION_ID,
                deviceSdk = Build.VERSION.SDK_INT
            ) ?: continue

            if (!isTrustedAssetUrl(update.artifact.downloadUrl)) {
                Log.w(LOG_TAG, "rejecting release asset from unexpected host")
                return UpdateCheckResult.Failed(UpdateError.NO_ARTIFACT)
            }
            return if (GithubReleaseParser.isNewerThanInstalled(
                    update = update,
                    installedVersionCode = BuildConfig.VERSION_CODE,
                    installedVersionName = BuildConfig.BASE_VERSION_NAME
                )
            ) {
                UpdateCheckResult.Available(update)
            } else {
                UpdateCheckResult.UpToDate
            }
        }
        return UpdateCheckResult.Failed(UpdateError.NO_ARTIFACT)
    }

    private fun fetchManifest(url: String): GithubReleaseParser.UpdateManifest? {
        if (!isTrustedAssetUrl(url)) return null
        val response = getText(url, accept = "application/json")
        if (response !is TextResponse.Ok) return null
        return GithubReleaseParser.parseManifest(response.body)
    }

    /** True for URLs we are willing to open: HTTPS, GitHub, and under this repo's release path. */
    fun isTrustedAssetUrl(url: String): Boolean {
        if (!url.startsWith(assetUrlPrefix)) return false
        val host = runCatching { URL(url).host }.getOrNull() ?: return false
        return host == RELEASE_HOST
    }

    /** Redirect targets are checked too, since GitHub bounces asset downloads to its CDN. */
    fun isTrustedDownloadHost(host: String?): Boolean =
        host == RELEASE_HOST || host == CDN_HOST

    private sealed interface TextResponse {
        data class Ok(val body: String) : TextResponse
        data class Error(val error: UpdateError) : TextResponse
    }

    private fun getText(url: String, accept: String): TextResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(url)
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    TextResponse.Ok(connection.inputStream.readCapped(MAX_METADATA_BYTES))
                }
                // GitHub answers an exhausted anonymous quota with 403/429 and a zeroed
                // X-RateLimit-Remaining. Worth telling apart from a real outage: the user just
                // has to wait, and a retry loop would make it worse.
                (code == 403 || code == 429) && connection.getHeaderField("X-RateLimit-Remaining") == "0" -> {
                    TextResponse.Error(UpdateError.RATE_LIMITED)
                }
                else -> {
                    Log.w(LOG_TAG, "update metadata request failed, HTTP $code")
                    TextResponse.Error(UpdateError.NETWORK)
                }
            }
        } catch (_: Exception) {
            TextResponse.Error(UpdateError.NETWORK)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Opens an HTTPS connection with our headers and timeouts, rejecting non-GitHub hosts. */
    internal fun openConnection(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "refusing non-https update URL" }
        require(parsed.host == API_HOST || isTrustedDownloadHost(parsed.host)) {
            "refusing update URL outside GitHub"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        require(connection is HttpsURLConnection) { "refusing non-TLS update connection" }
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        return connection
    }

    private fun InputStream.readCapped(maxBytes: Int): String = use { input ->
        val buffer = ByteArray(16 * 1024)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (bytes.size() + read > maxBytes) throw IOException("update metadata too large")
            bytes.write(buffer, 0, read)
        }
        bytes.toString(Charsets.UTF_8.name())
    }
}
