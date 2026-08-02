package io.legado.app.help.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.FileSystem

/**
 * 封面加载失败/解密 Fetcher 组 (iOS 版, 对照 jvmAndAndroidMain
 * CoverDecodeFetcher.jvmAndAndroid.kt 同名同语义; 差异: 下载走 KmpHttpClient
 * 内部 Ktor client (suspend 非阻塞), 进程级缓存用 atomicfu 锁保证 Kotlin/Native 线程安全)。
 */

/** 失败 url 进程级跳过表 (原 Glide OkHttpStreamFetcher.failUrl 语义)。 */
private val failUrlsLock = SynchronizedObject()
private val failUrls = HashSet<String>()

private fun isFailUrl(url: String): Boolean =
    synchronized(failUrlsLock) { failUrls.contains(url) }

private fun markFailUrl(url: String) {
    synchronized(failUrlsLock) { failUrls.add(url) }
}

/** 解密后封面字节 LRU (url -> bytes), 避免 Coil3 内存缓存未命中时反复下载+跑 JS。 */
private const val DECODED_CACHE_MAX_SIZE = 32
private val decodedCacheLock = SynchronizedObject()
private val decodedBytesCache = LinkedHashMap<String, ByteArray>()

private fun decodedCacheGet(url: String): ByteArray? = synchronized(decodedCacheLock) {
    val bytes = decodedBytesCache.remove(url) ?: return@synchronized null
    decodedBytesCache[url] = bytes
    bytes
}

private fun decodedCachePut(url: String, bytes: ByteArray): Unit = synchronized(decodedCacheLock) {
    decodedBytesCache.remove(url)
    decodedBytesCache[url] = bytes
    if (decodedBytesCache.size > DECODED_CACHE_MAX_SIZE) {
        decodedBytesCache.remove(decodedBytesCache.keys.first())
    }
}

/**
 * Coil3 Fetcher 包装 (最外层): 失败 url 跳过 + 封面解密, 全部下沉 fetcher 层
 * (对齐原 Glide OkHttpStreamFetcher.loadData/onResponse: 失败判断与解密都只在真正取数据时执行,
 * 内存缓存命中不解析不解密; 本层跑在 fetcherCoroutineContext (IO), 不再每请求跑主线程 DB 查询)。
 *
 * - 无 coverDecodeJs 的书源: 委托内层 [SourceOriginHeaderFetcher] → 网络 fetcher,
 *   网络请求的 HTTP 非 2xx ([coil3.network.HttpException]) 进失败表
 * - 带 coverDecodeJs: 下载原始字节跑 JS 解密 (共享 [ImageUtils.decode], native QuickJs 引擎),
 *   经 [DecodedCoverFetcher] 包成字节源回灌管线解码; 解密后字节缓存 = 进程内 LRU
 */
class CoverDecodeFetcher(
    private val url: String,
    private val options: Options,
    private val delegate: Fetcher,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (!url.startsWith("http", ignoreCase = true)) return delegate.fetch()
        if (isFailUrl(url)) {
            throw NoStackTraceException("跳过加载失败的图片")
        }
        // 只在 wifi 加载图片: 只拦网络获取, 内存/磁盘缓存命中仍正常显示 (对齐原版 OkHttpStreamFetcher)
        if (options.extras[LoadOnlyWifiKey] == true && !isWifiConnect()) {
            throw NoStackTraceException("只在wifi加载图片")
        }
        val sourceOrigin = options.extras[SourceOriginKey]
        val source = if (sourceOrigin.isNullOrEmpty()) {
            null
        } else {
            SourceHelp.getSource(sourceOrigin) as? BookSource
        }
        if (source?.coverDecodeJs.isNullOrBlank()) {
            return try {
                delegate.fetch()
            } catch (e: coil3.network.HttpException) {
                markFailUrl(url)
                throw e
            }
        }
        val decoded = decodedCacheGet(url) ?: run {
            val raw = downloadBytes(url, sourceOrigin)
            val bytes = runScriptWithContext {
                ImageUtils.decode(url, raw, isCover = true, source)
            } ?: run {
                markFailUrl(url)
                throw NoStackTraceException("封面二次解密失败")
            }
            decodedCachePut(url, bytes)
            bytes
        }
        return DecodedCoverFetcher(DecodedCoverBytes(url, decoded)).fetch()
    }

    /** 带书源防盗链 header 下载原始封面字节 (KmpHttpClient 内部 Ktor client, 继承 timeout 配置)。 */
    private suspend fun downloadBytes(url: String, sourceOrigin: String?): ByteArray {
        val client = requireNotNull(OkHttpClientProviders.get().okHttpClient.ktorClient) {
            "KmpHttpClient 未初始化 (需经 KmpHttpClientBuilder.build 创建)"
        }
        val response = client.get(url) {
            resolveSourceHeaders(sourceOrigin, url)?.forEach { (name, value) ->
                header(name, value)
            }
        }
        if (!response.status.isSuccess()) {
            markFailUrl(url)
            throw NoStackTraceException("加载封面失败 HTTP ${response.status.value}")
        }
        return response.bodyAsBytes()
    }

    /** 包住 [SourceOriginHeaderFetcher.Factory], 只处理 http(s) Uri。 */
    class Factory(
        private val delegate: Fetcher.Factory<Uri>,
    ) : Fetcher.Factory<Uri> {

        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val inner = delegate.create(data, options, imageLoader) ?: return null
            return CoverDecodeFetcher(data.toString(), options, inner)
        }
    }
}

/**
 * 解密后的封面字节 (data=本类型时走 [DecodedCoverFetcher] 直接解码)。
 * 缓存 key 由 [DecodedCoverKeyer] 按 url 稳定生成 (字节本身无稳定 toString)。
 */
class DecodedCoverBytes(
    val url: String,
    val bytes: ByteArray,
)

/** [DecodedCoverBytes] 的内存缓存 Keyer: 按原始 url 稳定命中 (变换/尺寸维度由 Coil3 追加)。 */
class DecodedCoverKeyer : Keyer<DecodedCoverBytes> {
    override fun key(data: DecodedCoverBytes, options: Options): String = "coverDecode:${data.url}"
}

/** [DecodedCoverBytes] 的 Fetcher: 字节直接包成 [SourceFetchResult] 交给解码器。 */
class DecodedCoverFetcher(
    private val data: DecodedCoverBytes,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        return SourceFetchResult(
            source = ImageSource(Buffer().write(data.bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<DecodedCoverBytes> {
        override fun create(
            data: DecodedCoverBytes,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = DecodedCoverFetcher(data)
    }
}
