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
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import okio.FileSystem

/**
 * 原 Glide OkHttpStreamFetcher 的进程级失败 url 跳过表 (Coil3 迁移复刻)。
 * 非 2xx 响应/封面解密失败的 url 进表, 列表滚动时不再反复请求死链。
 */
private val failUrls = java.util.Collections.synchronizedSet(HashSet<String>())

/** 解密后封面字节 LRU (url -> bytes), 避免 Coil3 内存缓存未命中时反复下载+跑 JS。 */
private val decodedBytesCache: MutableMap<String, ByteArray> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
                size > 32
        }
    )

/**
 * 已加载失败 url 的跳过 Interceptor (对齐原 OkHttpStreamFetcher.failUrl 语义)。
 *
 * - 命中表: 直接失败 ("跳过加载失败的图片"), 走调用方 error 分支 (默认封面)
 * - 未命中且 proceed 结果为 HTTP 非 2xx: 进表 (仅 [coil3.network.HttpException],
 *   避免把"仅 wifi 加载"这类策略性失败误判为死链)
 */
class FailedUrlSkipInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http", ignoreCase = true)) return chain.proceed()
        if (failUrls.contains(url)) {
            throw NoStackTraceException("跳过加载失败的图片")
        }
        val result = chain.proceed()
        if (result is ErrorResult && result.throwable is coil3.network.HttpException) {
            failUrls.add(url)
        }
        return result
    }
}

/**
 * 封面解密 Interceptor: 书源带 coverDecodeJs 时下载原始字节跑 JS 解密,
 * 再以 [DecodedCoverBytes] 数据回灌 Coil3 管线解码 (对齐原 OkHttpStreamFetcher
 * ImageUtils.decode(isCover=true) 行为)。无 coverDecodeJs 的书源零开销直通。
 *
 * 解密后字节的缓存策略：进程内 LRU + Coil3 磁盘缓存（通过 DecodedCoverKeyer/DecodedCoverFetcher）。
 * 进程重启后仍可从磁盘缓存命中，避免每次打开书架都重新下载+执行解密 JS。
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
        // 进程内缓存命中：直接用解密后字节，不下载不执行JS
        val decoded = decodedBytesCache[url] ?: run {
            // 先查 Coil3 磁盘缓存是否有解密后的字节
            val diskKey = "coverDecode:$url"
            val imageLoader = coil3.SingletonImageLoader.get(chain.request.context)
            val diskCache = imageLoader.diskCache
            val diskSnapshot = diskCache?.openSnapshot(diskKey)
            if (diskSnapshot != null) {
                try {
                    val bytes = diskSnapshot.data.toFile().readBytes()
                    if (bytes.isNotEmpty()) {
                        decodedBytesCache[url] = bytes
                        return@run bytes
                    }
                } finally {
                    diskSnapshot.close()
                }
            }
            // 缓存未命中：下载原始字节 + 执行解密JS
            val raw = downloadBytes(url, sourceOrigin)
            val bytes = runScriptWithContext {
                ImageUtils.decode(url, raw, isCover = true, source)
            } ?: run {
                failUrls.add(url)
                throw NoStackTraceException("封面二次解密失败")
            }
            decodedBytesCache[url] = bytes
            // 写入磁盘缓存，下次进程重启也可命中
            diskCache?.openEditor(diskKey)?.let { editor ->
                editor.data.toFile().writeBytes(bytes)
                editor.commit()
            }
            bytes
        }
        val newRequest = request.newBuilder()
            .data(DecodedCoverBytes(url, decoded))
            .build()
        return chain.withRequest(newRequest).proceed()
    }

    /** 带书源防盗链 header 下载原始封面字节 (走共享 OkHttpClient, 继承 CookieJar/限流)。 */
    private suspend fun downloadBytes(url: String, sourceOrigin: String): ByteArray {
        val client = OkHttpClientProviders.get().okHttpClient
        val builder = Request.Builder().url(url)
        resolveSourceHeaders(sourceOrigin, url)?.forEach { (name, value) ->
            builder.addHeader(name, value)
        }
        return withContext(Dispatchers.IO) {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    failUrls.add(url)
                    throw NoStackTraceException("加载封面失败 HTTP ${resp.code}")
                }
                resp.body?.bytes() ?: throw NoStackTraceException("封面响应体为空")
            }
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
