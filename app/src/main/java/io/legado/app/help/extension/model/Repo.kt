@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.legado.app.help.extension.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * 漫画扩展仓库。持久化为偏好中的 JSON 数组 (自动随备份进出);
 * indexUrl 归一化后以 …/index.min.json (老格式) 或 …/index.pb (Mihon index_v2) 结尾。
 */
@Serializable
data class MangaExtensionRepo(
    val indexUrl: String,
    val name: String,
    /** 仓库签名指纹 (SHA-256 hex), 命中即视为可信来源。 */
    val signingKeyFingerprint: String,
    val isDefault: Boolean = false,
    /** 仓库家族; 老数据缺省为漫画。 */
    val kind: RepoKind = RepoKind.MANGA,
)

/** 仓库家族: 漫画 (Mihon/Tachiyomi 系) / 视频 (Aniyomi 系)。 */
@Serializable
enum class RepoKind { MANGA, ANIME }

/**
 * keiyoushi 布局 index.min.json 条目。
 */
@Serializable
data class NetworkLegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<NetworkExtensionSource>? = null,
)

@Serializable
data class NetworkExtensionSource(
    /** 历史索引中 id 可能是数字字符串, KS_JSON 宽松模式可还原为 Long。 */
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String = "",
)

/**
 * keiyoushi 布局 repo.json 元信息 (添加仓库时取展示名与可信签名指纹)。
 */
@Serializable
data class NetworkRepoInfo(
    @kotlinx.serialization.SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta? = null,
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String = "",
        val signingKeyFingerprint: String,
    )
}

/**
 * 索引条目 → 可安装扩展。apkUrl/iconUrl 以 indexUrl 去掉 index.min.json 后的
 * 仓库根路径拼接; sources 为空时造 id=0 兜底项, 保证 pkgName 反查等语义不中断。
 */
fun NetworkLegacyExtension.toAvailable(repo: MangaExtensionRepo): MangaExtension.Available {
    val storeBaseUrl = repo.indexUrl.removeSuffix("index.min.json").trimEnd('/')
    return MangaExtension.Available(
        name = name.substringAfter("Aniyomi: ").substringAfter("Tachiyomi: "),
        pkgName = pkg,
        apkUrl = "$storeBaseUrl/apk/$apk",
        iconUrl = "$storeBaseUrl/icon/$pkg.png",
        libVersion = version.substringBeforeLast('.').toDouble(),
        versionCode = code,
        versionName = version,
        lang = lang,
        contentWarning = if (nsfw == 1) ContentWarning.NSFW else ContentWarning.SAFE,
        sources = sources.orEmpty().map { source ->
            MangaExtension.Available.Source(
                id = source.id,
                name = source.name,
                lang = source.lang,
                baseUrl = source.baseUrl,
            )
        }.ifEmpty {
            listOf(
                MangaExtension.Available.Source(
                    id = 0,
                    name = this@toAvailable.name.substringAfter("Aniyomi: ").substringAfter("Tachiyomi: "),
                    lang = lang,
                )
            )
        },
        repo = repo,
    )
}

/**
 * Mihon 0.20.1+ index_v2 (index.pb) proto 面。字段号逐字对齐 mihonapp/mihon
 * NetworkExtensionStore (kotlinx-protobuf 声明式); 缺字段以默认值兜底
 * (上游无默认值, 此处放宽以容忍仓库侧 schema 演进)。
 */
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources? = null,
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        /** 上游枚举 CONTENT_WARNING_UNSPECIFIED/SAFE/MIXED/NSFW = 0/1/2/3; 用 Int 承接, 未知值不炸整表。 */
        @ProtoNumber(7) val contentWarning: Int = 0,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )
}

/**
 * index.pb 扩展条目 → 可安装扩展。apkUrl/iconUrl 直接取 resources.* (GitHub Releases 直链),
 * 不做老格式拼接; sources 为空时造 id=0 兜底项 (语义同老格式映射)。
 */
fun NetworkExtensionStore.ExtensionList.toAvailable(repo: MangaExtensionRepo): List<MangaExtension.Available> =
    extensions.map { extension ->
        val langs = extension.sources.map { it.language }.toSet()
        val lang = if (langs.size == 1) langs.first() else "all"
        MangaExtension.Available(
            name = extension.name.substringAfter("Aniyomi: ").substringAfter("Tachiyomi: "),
            pkgName = extension.packageName,
            apkUrl = extension.resources?.apkUrl.orEmpty(),
            iconUrl = extension.resources?.iconUrl.orEmpty(),
            libVersion = extension.extensionLib.toDoubleOrNull()
                ?: extension.versionName.substringBeforeLast('.').toDoubleOrNull()
                ?: 0.0,
            versionCode = extension.versionCode,
            versionName = extension.versionName,
            lang = lang,
            contentWarning = when (extension.contentWarning) {
                2 -> ContentWarning.MIXED
                3 -> ContentWarning.NSFW
                else -> ContentWarning.SAFE
            },
            sources = extension.sources.map { source ->
                MangaExtension.Available.Source(
                    id = source.id,
                    name = source.name,
                    lang = source.language,
                    baseUrl = source.homeUrl,
                )
            }.ifEmpty {
                listOf(
                    MangaExtension.Available.Source(
                        id = 0,
                        name = extension.name,
                        lang = lang,
                    )
                )
            },
            repo = repo,
        )
    }
