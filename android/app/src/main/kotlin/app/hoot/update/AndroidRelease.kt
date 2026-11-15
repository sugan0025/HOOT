package app.hoot.update

import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class AndroidRelease(
    val tag: String,
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256: String,
    val signerSha256: String
) {
    fun toJson(): String = JSONObject()
        .put("tag", tag)
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("releaseNotes", releaseNotes)
        .put("apkUrl", apkUrl)
        .put("apkSizeBytes", apkSizeBytes)
        .put("sha256", sha256)
        .put("signerSha256", signerSha256)
        .toString()

    companion object {
        const val APPLICATION_ID = "app.hoot"
        const val APK_ASSET_NAME = "Hoot-Android.apk"
        const val METADATA_ASSET_NAME = "Hoot-Android-update.json"

        fun fromJson(json: String): AndroidRelease {
            val data = JSONObject(json)
            require(data.optInt("schemaVersion") == 1) { "Unsupported Hoot update metadata version" }
            require(data.getString("applicationId") == APPLICATION_ID) { "Update is for a different app" }
            val apkUrl = data.getString("apkUrl")
            require(isTrustedReleaseUrl(apkUrl)) { "Update link is not an official HTTPS release asset" }
            val digest = data.getString("sha256").lowercase(Locale.ROOT)
            val signer = data.getString("signerSha256").lowercase(Locale.ROOT)
            require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK SHA-256 digest" }
            require(signer.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK signing certificate digest" }
            val versionCode = data.getLong("versionCode")
            val size = data.getLong("apkSizeBytes")
            require(versionCode > 0) { "Invalid Android version code" }
            require(size in 1..MAX_APK_SIZE_BYTES) { "APK size is outside the supported limit" }
            val tag = data.getString("tag")
            require(ANDROID_TAG.matches(tag)) { "Invalid Android release tag" }
            require(apkUrl.contains("/releases/download/$tag/")) { "APK link does not match its release tag" }
            return AndroidRelease(
                tag = tag,
                versionCode = versionCode,
                versionName = data.getString("versionName"),
                releaseNotes = data.optString("releaseNotes").take(MAX_NOTES_LENGTH),
                apkUrl = apkUrl,
                apkSizeBytes = size,
                sha256 = digest,
                signerSha256 = signer
            )
        }

        fun isTrustedReleaseUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.path.orEmpty().startsWith("/sugan0025/HOOT/releases/download/")
        } catch (_: Exception) {
            false
        }

        const val MAX_APK_SIZE_BYTES = 500L * 1024 * 1024
        const val MAX_NOTES_LENGTH = 12_000
        val ANDROID_TAG = Regex("android-v\\d+\\.\\d+\\.\\d+")
    }
}

/** Metadata asset attached alongside each signed Android release APK. */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long)
