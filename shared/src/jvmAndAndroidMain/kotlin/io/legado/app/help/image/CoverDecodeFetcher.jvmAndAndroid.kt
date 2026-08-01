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
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
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
 * Coil3 Fetcher 包装 (最外层): 失败 url 跳过 + 封面解密, 全部下沉 fetcher 层
 * (对齐原 Glide OkHttpStreamFetcher.loadData/onResponse: 失败判断与解密都只在真正取数据时执行,
 * 内存缓存命中不解析不解密; 本层跑在 fetcherCoroutineContext (IO), 磁盘缓存读取不再卡主线程)。
 *
 * - 无 coverDecodeJs 的书源: 委托内层 [SourceOriginHeaderFetcher] → 网络 fetcher,
 *   网络请求的 HTTP 非 2xx ([coil3.network.HttpException]) 进失败表
 * - 带 coverDecodeJs: 下载原始字节跑 JS 解密, 经 [DecodedCoverFetcher] 包成字节源回灌管线解码;
 *   解密后字节缓存 = 进程内 LRU + 磁盘缓存 (key "coverDecode:url"; 书架封面带 #covers 后缀落持久区)
 */
class CoverDecodeFetcher(
    private val url: String,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val delegate: Fetcher,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (!url.startsWith("http", ignoreCase = true)) return delegate.fetch()
        if (failUrls.contains(url)) {
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
                failUrls.add(url)
                throw e
            }
        }
        // 进程内缓存命中: 直接用解密后字节, 不下载不执行JS
        val decoded = decodedBytesCache[url] ?: run {
            // 先查磁盘缓存是否有解密后的字节 (进程重启后仍可命中, 不重复下载+跑 JS)
            // 书架封面 (PersistentCoverKey) 带 #covers 后缀落持久区, 与请求 diskCacheKey(url#covers) 对齐
            val diskKey = if (options.extras[PersistentCoverKey] == true) {
                coverDiskCacheKey("coverDecode:$url")
            } else {
                "coverDecode:$url"
            }
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
            // 缓存未命中: 下载原始字节 + 执行解密JS
            val raw = downloadBytes(url, sourceOrigin)
            val bytes = runScriptWithContext {
                ImageUtils.decode(url, raw, isCover = true, source)
            } ?: run {
                failUrls.add(url)
                throw NoStackTraceException("封面二次解密失败")
            }
            decodedBytesCache[url] = bytes
            diskCache?.openEditor(diskKey)?.let { editor ->
                editor.data.toFile().writeBytes(bytes)
                editor.commit()
            }
            bytes
        }
        return DecodedCoverFetcher(DecodedCoverBytes(url, decoded)).fetch()
    }

    /** 带书源防盗链 header 下载原始封面字节 (走共享 OkHttpClient, 继承 CookieJar/限流)。 */
    private suspend fun downloadBytes(url: String, sourceOrigin: String?): ByteArray {
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
            return CoverDecodeFetcher(data.toString(), options, imageLoader, inner)
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
