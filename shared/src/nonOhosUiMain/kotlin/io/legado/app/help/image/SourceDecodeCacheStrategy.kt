package io.legado.app.help.image

import coil3.Extras
import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponseBody
import coil3.network.NetworkResponse
import coil3.request.Options
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path

/** Coil3 Extras key: 请求是否为封面图 (default=true 保持封面语义, 兼容未显式标注的调用)。 */
val IsCoverKey = Extras.Key<Boolean>(default = true)

/**
 * Coil3 Extras key: 携带书源 bookUrl (sourceOrigin)。
 *
 * 消费点构造 [coil3.request.ImageRequest] 时 `.extras.set(SourceOriginKey, sourceOrigin)`;
 * 真实读取者在网络层 [SourceHeaderNetworkClient] (磁盘查询之后解析防盗链 header + 改写 url)
 * 与本策略 [write] (解密落盘取书源), 均在 IO/网络线程内读, 消费点无需在 @Composable 内调 suspend。
 */
val SourceOriginKey = Extras.Key<String?>(default = null)

/**
 * 图片链缓存策略 (android/jvm/ios 三端共享单份, 替代原 CoverDecodeFetcher 两份手写解密落盘):
 *
 * - read: 磁盘命中原样返回 —— 写盘时已解密 (见 write), 冷启动命中即解密后字节,
 *   不重新下载、不重跑解密 JS;
 * - write: 对配置了解密规则的书源, 把网络响应字节解密后再交由 NetworkFetcher 写入磁盘缓存
 *   —— 对齐原版 Glide `DiskCacheStrategy.DATA` (缓存 fetcher 输出流 = 解密后字节) 的效果,
 *   写盘成功后 NetworkFetcher 直接以 snapshot 内容作为返回源, 显示与磁盘内容天然一致;
 *   非解密请求 (无书源 / 无 coverDecodeJs / 正文图无 imageDecode) 原样透传, 行为同默认策略。
 *
 * 与原手写方案的差异: 解密后字节占用网络 url 自身的磁盘 key (url / url#covers), 书源修改
 * 或删除解密算法后旧条目无法自证版本 —— 原手写 `coverDecode:` key 方案同样不校验算法版本,
 * 缺陷等价, 换取三端单份实现与 Glide 同款自动化。
 *
 * 拦截职责划分 (对齐"缓存先于拦截"): failUrls 死链跳过在
 * [ImageGuardNetworkClient] (磁盘查询之后), 本策略只负责 2xx 响应的解密落盘
 * (NetworkFetcher 对非 2xx/非 304 抛 HttpException, 不会进 write)。
 *
 * 注意: [CacheStrategy] 为 Coil3 实验 API (@ExperimentalCoilApi), 升级 Coil 时需复核签名。
 */
@OptIn(ExperimentalCoilApi::class)
internal object SourceDecodeCacheStrategy : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult = CacheStrategy.ReadResult(cacheResponse)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        // 304: 合并磁盘缓存头, 不落 body (对齐 DefaultCacheStrategy; 非 2xx 不会进 write)
        if (networkResponse.code == 304 && cacheResponse != null) {
            val headers = NetworkHeaders.Builder().apply {
                cacheResponse.headers.asMap().forEach { (k, vs) -> vs.forEach { add(k, it) } }
                networkResponse.headers.asMap().forEach { (k, vs) -> vs.forEach { add(k, it) } }
            }.build()
            return CacheStrategy.WriteResult(networkResponse.copy(headers = headers, body = null))
        }
        // 解密规则: 封面 → coverDecodeJs, 正文图 → contentRule?.imageDecode (对齐 ImageUtils.getRuleJs;
        // IsCoverKey 默认 true, Android/桌面封面请求未显式标注即封面语义; 当前无 IsCoverKey=false
        // 写入点 —— 正文图走 ImageBitmapLoader 自下载链路不经 Coil, 此分支为对齐语义的预留)
        val sourceOrigin = options.extras[SourceOriginKey] ?: return passthrough(networkResponse)
        val source = SourceHelp.getSource(sourceOrigin) as? BookSource
            ?: return passthrough(networkResponse)
        val isCover = options.extras[IsCoverKey] ?: true
        val ruleJs = if (isCover) source.coverDecodeJs else source.contentRule.imageDecode
        if (ruleJs.isNullOrBlank()) return passthrough(networkResponse)

        val url = networkRequest.url
        val raw = Buffer()
        networkResponse.body?.use { it.writeTo(raw) } ?: return passthrough(networkResponse)
        // 空 body 不能走 passthrough: use 已关闭原 body, 交回后 writeToDiskCache 对已关闭
        // source 调 readAll 必抛; 换成空字节 body 正常写盘
        if (raw.size == 0L) {
            return CacheStrategy.WriteResult(networkResponse.copy(body = BytesResponseBody(ByteArray(0))))
        }

        // 解密 (磁盘缓存将写入解密后字节); 失败拉黑 + 抛出, 对齐原手写方案语义
        val decoded = runScriptWithContext {
            ImageUtils.decode(url, raw.readByteArray(), isCover = isCover, source)
        } ?: run {
            markFailUrl(url)
            throw NoStackTraceException("图片二次解密失败")
        }
        return CacheStrategy.WriteResult(networkResponse.copy(body = BytesResponseBody(decoded)))
    }

    /** 非解密请求原样透传 (对齐 DefaultCacheStrategy.write 的普通写盘行为)。 */
    private fun passthrough(networkResponse: NetworkResponse) =
        CacheStrategy.WriteResult(networkResponse)
}

/** 解密后字节的内存 body: 写盘时经 NetworkFetcher.writeToDiskCache 落入磁盘缓存。 */
private class BytesResponseBody(private val bytes: ByteArray) : NetworkResponseBody {
    override suspend fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    override suspend fun writeTo(fileSystem: FileSystem, path: Path) {
        // okio 的 FileSystem.write 是 commonMain 的 inline 成员 (okio 3.18.1 FileSystem.kt:250),
        // 与 coil3 SourceResponseBody.writeTo 同款写法。不能用 sink(path).buffer().use {}:
        // Native 上 okio.BufferedSink 实现的是 okio.Closeable 而非 kotlin.AutoCloseable,
        // 默认导入的 kotlin.io.use 接收者不匹配 → iosArm64 编译报 Unresolved reference。
        fileSystem.write(path) { write(bytes) }
    }

    override fun close() {}
}
