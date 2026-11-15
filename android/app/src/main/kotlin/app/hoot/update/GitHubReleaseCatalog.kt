package app.hoot.update

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleaseCatalog(
    private val apiRoot: String = "https://api.github.com/repos/sugan0025/HOOT"
) {
    suspend fun fetchLatestAndroidRelease(): AndroidRelease? {
        val androidReleases = mutableListOf<JSONObject>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val releases = JSONArray(request("$apiRoot/releases?per_page=100&page=$page"))
            if (releases.length() == 0) break
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                val tag = release.optString("tag_name")
                if (AndroidRelease.ANDROID_TAG.matches(tag) &&
                    !release.optBoolean("draft") && !release.optBoolean("prerelease")) {
                    androidReleases.add(release)
                }
            }
            if (releases.length() < 100) break
        }

        val latest = androidReleases.maxByOrNull { semverRank(it.getString("tag_name")) } ?: return null
        val assets = latest.optJSONArray("assets") ?: return null
        val metadataAsset = findAsset(assets, AndroidRelease.METADATA_ASSET_NAME)
            ?: throw IllegalStateException("Android release is missing its update metadata asset")
        val apkAsset = findAsset(assets, AndroidRelease.APK_ASSET_NAME)
            ?: throw IllegalStateException("Android release is missing ${AndroidRelease.APK_ASSET_NAME}")
        val metadata = JSONObject(request(metadataAsset.url))
        val release = AndroidRelease.fromJson(metadata.toString())
        require(release.tag == latest.getString("tag_name")) { "Release metadata tag does not match GitHub" }
        require(release.apkSizeBytes == apkAsset.sizeBytes) { "Release APK size does not match GitHub" }
        require(release.apkUrl == apkAsset.url) { "Release APK URL does not match GitHub" }
        return release.copy(releaseNotes = latest.optString("body", release.releaseNotes).ifBlank { release.releaseNotes })
    }

    private fun findAsset(assets: JSONArray, expectedName: String): ReleaseAsset? {
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == expectedName) {
                val url = asset.optString("browser_download_url")
                if (!AndroidRelease.isTrustedReleaseUrl(url)) throw IllegalArgumentException("Untrusted GitHub release asset URL")
                return ReleaseAsset(expectedName, url, asset.optLong("size", -1))
            }
        }
        return null
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "HOOT-Android-Updater")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            if (status == 403 || status == 429) throw RateLimitedException("GitHub rate limit reached")
            if (status !in 200..299) throw IllegalStateException("GitHub update check returned HTTP $status")
            val finalUrl = connection.url.toURI()
            require(finalUrl.scheme.equals("https", true) && finalUrl.host?.lowercase() in ALLOWED_API_HOSTS) {
                "GitHub redirected outside trusted HTTPS hosts"
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub update request failed", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private data class SemVer(val major: Long, val minor: Long, val patch: Long) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    }

    private fun semverRank(tag: String): SemVer {
        val parts = tag.removePrefix("android-v").split('.').map { it.toLong() }
        return SemVer(parts[0], parts[1], parts[2])
    }

    class RateLimitedException(message: String) : Exception(message)

    companion object {
        private const val TAG = "HootUpdater"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val MAX_RELEASE_PAGES = 20
        private val ALLOWED_API_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
