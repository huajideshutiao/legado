package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// @Keep 移除：shared 无 androidx.annotation 依赖；kotlinx.serialization 编解码不依赖反射，
// JS 桥/Gson 反射保活改由 consumer-rules.pro -keep 登记（照 OldRssSource/StrResponse 先例）。
@Serializable
data class AppReleaseInfo(
    val appVariant: AppVariant,
    val createdAt: Long,
    val note: String,
    val name: String,
    val downloadUrl: String,
    val assetUrl: String,
    val versionName: String
)

@Serializable
enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }

}

@Serializable
data class GithubRelease(
    val name: String?,
    @SerialName("tag_name")
    val tagName: String?,
    val assets: List<Asset>?,
    val body: String,
    @SerialName("prerelease")
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

@Serializable
data class Asset(
    @SerialName("browser_download_url")
    val apkUrl: String,
    @SerialName("content_type")
    val contentType: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("download_count")
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
        // 下沉 commonMain: java.time.Instant (JVM-only) → kotlin.time.Instant (KMP 标准库)
        val instant = Instant.parse(createdAt)
        val timestamp: Long = instant.toEpochMilliseconds()

        val appVariant = when {
            name.contains("releaseA", ignoreCase = true) -> AppVariant.BETA_RELEASEA
            isBetaTag -> AppVariant.BETA_RELEASE
            else -> AppVariant.OFFICIAL
        }

        return AppReleaseInfo(appVariant, timestamp, note, name, apkUrl, url, releaseVersion)
    }
}
