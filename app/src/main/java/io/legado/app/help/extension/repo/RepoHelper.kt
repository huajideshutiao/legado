@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.legado.app.help.extension.repo

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.extension.model.MangaExtension
import io.legado.app.help.extension.model.MangaExtensionRepo
import io.legado.app.help.extension.model.NetworkExtensionStore
import io.legado.app.help.extension.model.NetworkLegacyExtension
import io.legado.app.help.extension.model.NetworkRepoInfo
import io.legado.app.help.extension.model.RepoKind
import io.legado.app.help.extension.model.toAvailable
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.FilesJsonStore
import io.legado.app.utils.GSON
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.zip.GZIPInputStream

/**
 * 扩展仓库访问: URL 校验、repo.json/index.min.json 拉取解析、索引本地缓存。
 * 缓存只用于加速首屏展示, 不进备份, 网络失败时可重拉。
 */
internal object RepoHelper {

    /** 默认仓库 (签名指纹硬编码, 不可移除): 漫画 keiyoushi + 视频 yuzono。 */
    val defaultRepo = MangaExtensionRepo(
        indexUrl = DEFAULT_MANGA_INDEX_URL,
        name = DEFAULT_MANGA_REPO_NAME,
        signingKeyFingerprint = DEFAULT_MANGA_FINGERPRINT,
        isDefault = true,
        kind = RepoKind.MANGA,
    )

    /** 视频默认仓库 (Aniyomi 系老格式索引); 指纹实取自 yuzono/anime-repo repo.json。 */
    val defaultAnimeRepo = MangaExtensionRepo(
        indexUrl = DEFAULT_ANIME_INDEX_URL,
        name = DEFAULT_ANIME_REPO_NAME,
        signingKeyFingerprint = DEFAULT_ANIME_FINGERPRINT,
        isDefault = true,
        kind = RepoKind.ANIME,
    )

    private const val DEFAULT_MANGA_INDEX_URL =
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
    private const val DEFAULT_MANGA_REPO_NAME = "Keiyoushi"
    private const val DEFAULT_MANGA_FINGERPRINT =
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    private const val DEFAULT_ANIME_INDEX_URL =
        "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
    private const val DEFAULT_ANIME_REPO_NAME = "Yuzono"
    private const val DEFAULT_ANIME_FINGERPRINT =
        "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"

    private const val INDEX_FILE_NAME = "mangaExtensionIndex.json"
    private const val REPO_FILE_SUFFIX = "repo.json"

    /** Mihon CreateExtensionRepo 同款严格面: https:// 开头, 完整指向 index.min.json 或 index.pb。 */
    private val INDEX_URL_REGEX = Regex("""^https://.*/index\.(min\.json|pb)$""")

    /**
     * 校验用户输入的仓库地址: 仅接受完整 https 索引地址, 经 URL 规范化, 不做补全 (Mihon 同款)。
     */
    fun normalizeIndexUrl(raw: String): String {
        val input = raw.trim().ifEmpty { throw NoStackTraceException("仓库地址为空") }
        val url = input.toHttpUrlOrNull()?.toString()
            ?: throw NoStackTraceException("仓库地址无效: $input")
        if (!INDEX_URL_REGEX.matches(url)) {
            throw NoStackTraceException("仓库地址须完整指向 index.min.json 或 index.pb: $input")
        }
        return url
    }

    /**
     * index 同目录的 repo.json, 取仓库展示名与可信签名指纹 (去尾段拼接, min.json/pb 两格式通用)。
     */
    fun fetchRepoMeta(indexUrl: String): Pair<String, String> {
        val repoJsonUrl = indexUrl.substringBeforeLast('/') + "/" + REPO_FILE_SUFFIX
        val body = httpGetBytes(repoJsonUrl)
        val info = runCatching { GSON.decodeFromString(NetworkRepoInfo.serializer(), body.decodeToString()) }
            .getOrElse { throw NoStackTraceException("仓库元信息解析失败: $repoJsonUrl") }
        val meta = info.meta ?: throw NoStackTraceException("仓库元信息缺失: $repoJsonUrl")
        return meta.name to meta.signingKeyFingerprint
    }

    /**
     * 拉取并解析仓库索引, 格式嗅探 (Mihon ExtensionStoreService 同款判定): 先剥 gzip,
     * 首字节 '[' → 老格式 min.json 数组; '{' → 新版 JSON 索引 (按任务范围不支持); 其余 →
     * protobuf (index.pb, Mihon index_v2)。空仓库或解析失败按错误抛出, 不静默产出空列表。
     */
    fun fetchIndex(repo: MangaExtensionRepo): List<MangaExtension.Available> {
        val bytes = decompressIfGzipped(httpGetBytes(repo.indexUrl))
        val items = when (bytes.firstOrNull()) {
            '['.code.toByte() -> parseLegacyIndex(repo, bytes)
            '{'.code.toByte() -> throw NoStackTraceException("不支持的新版 JSON 索引: ${repo.name}")
            else -> parseProtoIndex(repo, bytes)
        }
        if (items.isEmpty()) {
            throw NoStackTraceException("扩展索引为空: ${repo.name}")
        }
        return items
    }

    private fun parseLegacyIndex(
        repo: MangaExtensionRepo,
        bytes: ByteArray,
    ): List<MangaExtension.Available> {
        val items = runCatching {
            GSON.decodeFromString(ListSerializer(NetworkLegacyExtension.serializer()), bytes.decodeToString())
        }.getOrElse { throw NoStackTraceException("扩展索引解析失败: ${repo.name}") }
        return items.map { it.toAvailable(repo) }
    }

    private fun parseProtoIndex(
        repo: MangaExtensionRepo,
        bytes: ByteArray,
    ): List<MangaExtension.Available> {
        val store = runCatching {
            ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
        }.getOrElse { throw NoStackTraceException("扩展索引解析失败 (protobuf): ${repo.name}") }
        return store.extensionList?.toAvailable(repo).orEmpty()
    }

    private fun decompressIfGzipped(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }
            .getOrDefault(bytes)
    }

    fun loadCachedIndex(): List<MangaExtension.Available> {
        val json = FilesJsonStore.readText(INDEX_FILE_NAME) ?: return emptyList()
        return runCatching {
            GSON.decodeFromString(ListSerializer(MangaExtension.Available.serializer()), json)
        }.getOrNull().orEmpty()
    }

    fun saveCachedIndex(list: List<MangaExtension.Available>) {
        FilesJsonStore.writeText(
            INDEX_FILE_NAME,
            GSON.encodeToString(ListSerializer(MangaExtension.Available.serializer()), list),
        )
    }

    private fun httpGetBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw NoStackTraceException("请求失败 HTTP ${response.code}: $url")
            }
            return response.body.bytes()
        }
    }
}
