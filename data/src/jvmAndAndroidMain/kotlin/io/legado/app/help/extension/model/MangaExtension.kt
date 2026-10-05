package io.legado.app.help.extension.model

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.Source

/**
 * 漫画扩展的三种形态。
 *
 * 加载契约 (feature 标记 + tachiyomi.extension.* / tachiyomix.* metadata) 与
 * Tachiyomi/Mihon 系扩展 apk 及 keiyoushi 仓库索引严格对齐, 字段不可自行增改。
 */
sealed interface MangaExtension {

    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double?
    val lang: String?
    val contentWarning: ContentWarning

    /**
     * 已在设备上的扩展 (共享 apk 由系统包管理器持有, 私有 apk 落在 filesDir/exts),
     * 无论是否成功加载出源。
     */
    sealed interface Installed : MangaExtension {

        val isShared: Boolean

        /** 扩展 apk 签名证书的 SHA-256 摘要 (小写 hex)。 */
        val signatures: List<String>

        val hasUpdate: Boolean

        /**
         * 签名匹配的仓库给出的最新条目。签名不同的仓库提供的 apk 无法覆盖当前
         * 安装, 更新只可能来自这些仓库。
         */
        fun findListing(available: Collection<Available>): Available? {
            return available
                .filter { it.pkgName == pkgName && it.repo.signingKeyFingerprint.lowercase() in signatures }
                .maxWithOrNull(compareBy<Available> { it.versionCode }.thenBy { it.libVersion })
        }

        fun findUpdate(available: Collection<Available>): Available? {
            val installedLibVersion = libVersion
            return findListing(available)?.takeIf {
                it.versionCode > versionCode || (installedLibVersion != null && it.libVersion > installedLibVersion)
            }
        }
    }

    /**
     * 尚未安装、由仓库索引给出的条目。
     */
    @kotlinx.serialization.Serializable
    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentWarning: ContentWarning,
        val sources: List<Source>,
        val apkUrl: String,
        val iconUrl: String,
        val repo: MangaExtensionRepo,
    ) : MangaExtension {

        @kotlinx.serialization.Serializable
        data class Source(
            val id: Long,
            val lang: String,
            val name: String,
            val baseUrl: String = "",
        )
    }

    /**
     * 已安装且源已注册可用的扩展。
     */
    data class Loaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentWarning: ContentWarning,
        override val isShared: Boolean,
        override val signatures: List<String>,
        val pkgFactory: String?,
        val sources: List<Source>,
        /** 视频扩展装载出的源 (animeSources 非空即视频扩展, sources 为空; 漫画侧相反)。 */
        val animeSources: List<AnimeSource> = emptyList(),
        override val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        val repo: MangaExtensionRepo? = null,
    ) : Installed

    /**
     * 已安装但未加载成功、不提供源的扩展。lang 由源推导、libVersion 来自
     * metadata, 在此两者都可能未知。
     */
    data class NotLoaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val isShared: Boolean,
        override val contentWarning: ContentWarning,
        override val signatures: List<String>,
        override val libVersion: Double? = null,
        override val lang: String? = null,
        override val hasUpdate: Boolean = false,
        val repo: MangaExtensionRepo? = null,
        val reason: Reason,
    ) : Installed {

        sealed interface Reason {
            /** 签名尚未被信任, 由用户确认后可加载。 */
            data class Untrusted(val signatureHash: String) : Reason

            /** 内容分级被用户设置过滤。 */
            data object Filtered : Reason

            /** 完全没有签名可校验。 */
            data object Unsigned : Reason

            /** 构建所用的扩展库版本本应用无法运行。 */
            data object UnsupportedLibVersion : Reason

            /** 必需的包 metadata 缺失。 */
            data object Malformed : Reason

            /** 类或源实例化时抛出异常。 */
            data class Failed(val message: String, val stackTrace: String) : Reason
        }
    }
}

/**
 * 扩展内容分级。来自 metadata tachiyomix.contentWarning (1=MIXED 2=NSFW,
 * 缺失时按 tachiyomi.extension.nsfw==1 视为 NSFW) 或仓库索引 nsfw 字段。
 */
enum class ContentWarning {
    SAFE, MIXED, NSFW
}

/**
 * 扩展安装流程状态。
 */
sealed class InstallStep {
    data class Progress(val progress: Int = 0) : InstallStep()
    data object Downloading : InstallStep()
    data object Installing : InstallStep()
    data object Installed : InstallStep()
    data class Error(val error: String?) : InstallStep()
}
