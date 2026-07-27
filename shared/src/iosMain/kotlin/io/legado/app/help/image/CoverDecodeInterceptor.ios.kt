package io.legado.app.help.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.request.ErrorResult
import coil3.request.ImageResult
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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.FileSystem

/**
 * 封面加载失败/解密 Interceptor 组 (iOS 版, 对照 jvmAndAndroidMain
 * CoverDecodeInterceptor.jvmAndAndroid.kt 同名同语义; 差异: 下载走 KmpHttpClient
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
 * 已加载失败 url 的跳过 Interceptor (对齐 jvmAndAndroidMain FailedUrlSkipInterceptor 语义)。
 *
 * - 命中表: 直接失败 ("跳过加载失败的图片"), 走调用方 error 分支 (默认封面/占位)
 * - 未命中且 proceed 结果为 HTTP 非 2xx ([coil3.network.HttpException]): 进表,
 *   列表滚动时不再反复请求死链 (策略性失败不误判)
 */
class FailedUrlSkipInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http", ignoreCase = true)) return chain.proceed()
        if (isFailUrl(url)) {
            throw NoStackTraceException("跳过加载失败的图片")
        }
        val result = chain.proceed()
        if (result is ErrorResult && result.throwable is coil3.network.HttpException) {
            markFailUrl(url)
        }
        return result
    }
}

/**
 * 封面解密 Interceptor: 书源带 coverDecodeJs 时下载原始字节跑 JS 解密
 * (共享 [ImageUtils.decode], native QuickJs 引擎), 再以 [DecodedCoverBytes]
 * 数据回灌 Coil3 管线解码。无 coverDecodeJs 的书源零开销直通。
 */
class CoverDecodeInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http", ignoreCase = true)) return chain.proceed()
        val sourceOrigin = request.extras[SourceOriginKey]
        if (sourceOrigin.isNullOrEmpty()) return chain.proceed()
        val source = SourceHelp.getSource(sourceOrigin) as? BookSource
            ?: return chain.proceed()
        if (source.coverDecodeJs.isNullOrBlank()) return chain.proceed()
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
        val newRequest = request.newBuilder()
            .data(DecodedCoverBytes(url, decoded))
            .build()
        return chain.withRequest(newRequest).proceed()
    }

    /** 带书源防盗链 header 下载原始封面字节 (KmpHttpClient 内部 Ktor client, 继承 timeout 配置)。 */
    private suspend fun downloadBytes(url: String, sourceOrigin: String): ByteArray {
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
