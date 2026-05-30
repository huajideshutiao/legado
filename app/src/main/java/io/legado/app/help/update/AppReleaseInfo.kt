package io.legado.app.help.update

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import io.legado.app.exception.NoStackTraceException
import java.time.Instant

data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String,
    val versionName: String
)

enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }

}

@Keep
data class GithubRelease(
    val name: String?,
    @SerializedName("tag_name")
    val tagName: String?,
    val assets: List<Asset>?,
    val body: String,
    @SerializedName("prerelease")
    val isPreRelease: Boolean,
) {
    fun gitReleaseToAppReleaseInfo(): List<AppReleaseInfo> {
        assets ?: throw NoStackTraceException("获取新版本出错")
        val releaseVersion = if (tagName == "beta") name.orEmpty() else tagName.orEmpty()
        return assets
            .filter { it.isValid }
            .map { it.assetToAppReleaseInfo(body, releaseVersion, tagName == "beta") }
    }
}

@Keep
data class Asset(
    @SerializedName("browser_download_url")
    val apkUrl: String,
    @SerializedName("content_type")
    val contentType: String,
    @SerializedName("created_at")
    val createdAt: String,
    @SerializedName("download_count")
    val downloadCount: Int,
    val id: Int,
    val name: String,
    val state: String,
    val url: String
) {
    val isValid: Boolean
        get() = (contentType == "application/vnd.android.package-archive") && (state == "uploaded")

    fun assetToAppReleaseInfo(
        note: String,
        releaseVersion: String,
        isBetaTag: Boolean
    ): AppReleaseInfo {
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilli()

        val appVariant = when {
            name.contains("共存") -> AppVariant.BETA_RELEASEA
            isBetaTag -> AppVariant.BETA_RELEASE
            else -> AppVariant.OFFICIAL
        }

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, url, releaseVersion)
    }
}
