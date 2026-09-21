package io.legado.app.help.image

import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.model.script.runScriptWithContext
import io.legado.app.utils.ImageUtils
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path

/**
 * 书源防盗链 header + 书源 JS 改写 URL + 网络响应业务解密注入点 (android/jvm/ios 三端共享单份)。
 *
 * 拦截位置在 Coil3 `NetworkFetcher.fetch()` 的磁盘缓存查询**之后**: NetworkFetcher 先
 * `readFromDiskCache()`, 命中即 return, 只有慢路径才 `executeNetworkRequest` → 本 NetworkClient。
 * 对齐原版 Glide「磁盘缓存命中不进 `OkHttpStreamFetcher.loadData`, header/URL 解析与解密只在真发请求时
 * 发生一次」的语义 —— 内存命中 (不进 fetcher) 与磁盘命中均零查源。
 *
 * 职责闭环 (对齐原版 OkHttpStreamFetcher 响应原地解密与官方标准):
 * - 发请求前: 按 sourceOrigin 查源解析真实 url 与防盗链 headers;
 * - 网络响应后: 原地复用发请求前已获取的 source 实例进行 [ImageUtils.decode] 业务解密 (有 coverDecodeJs
 *   或正文图 imageDecode 时), 将明文 NetworkResponse 交付给 Coil 网络层。
 *   使得 Coil 无论写盘落盘还是写盘失败回退读内存, 均保证为明文字节, 绝不发生密文裂图;
 *   同时单图生命周期内仅查 1 次源, 消除旧分层设计导致的二次查库。
 *
 * 包在 [ImageGuardNetworkClient] 内层: failUrls 的查询与拉黑因此始终按调用方原始 URL 计数,
 * 与原版 `failUrl.add(url.toStringUrl())` (GlideUrl 原始串) 一致; 本类只负责换形与响应解码。
 */
internal class SourceHeaderNetworkClient(
    private val delegate: NetworkClient,
    /** (sourceOrigin, 原始 url) → 解析后请求对象 (含真实 url、请求头与书源对象)。各端 `resolveSourceRequest` 实现。 */
    private val resolve: suspend (sourceOrigin: String, url: String) -> ResolvedSourceRequest,
    /**
     * 是否保留 cookieJar 伪头。
     *
     * - true (android/desktop): 伪头由 app 拦截器摘除 (jvmAndAndroidMain/help/http/HttpHelper.kt
     *   的 CookieJarBridgeHolder.loadRequest/saveResponse), 摘除前用它识别「本次访问结束即落库 cookie」,
     *   必须保留。
     * - false (iOS): 不剔除会把 `CookieJar: 1` 当真请求头发到服务器。注意原因**不是**没注册桥
     *   (IosProviderRegistry 已调 registerSharedCookieJarBridge), 而是该桥只挂在
     *   KmpHttpClient.newCall → executeKtor → KmpRequest.prepareForSend 路径上 (那里摘伪头 +
     *   注入 Cookie); 而 Coil 图片链用 `ktorClient.asNetworkClient()` 直接走 Ktor 插件链,
     *   不经 prepareForSend, 没人摘 → 只能在本层剔除。
     */
    private val keepCookieJarMarker: Boolean,
) : NetworkClient {

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val sourceOrigin = request.extras[SourceOriginKey]
        if (sourceOrigin.isNullOrEmpty()) return delegate.executeRequest(request, block)
        val resolvedRequest = resolve(sourceOrigin, request.url)
        val url = resolvedRequest.url
        val resolved = resolvedRequest.headers
        val source = resolvedRequest.source
        val sourceHeaders =
            if (keepCookieJarMarker) resolved else resolved - cookieJarHeader
        val headers = if (url == request.url && sourceHeaders.isEmpty()) {
            request.headers
        } else {
            NetworkHeaders.Builder().apply {
                request.headers.asMap().forEach { (name, values) -> set(name, values) }
                sourceHeaders.forEach { (name, value) -> set(name, value) }
            }.build()
        }
        val outgoingRequest = NetworkRequest(
            url = url,
            method = request.method,
            headers = headers,
            body = request.body,
            extras = request.extras,
        )
        return delegate.executeRequest(outgoingRequest) { response ->
            val decodedResponse = decodeResponseIfNeeded(
                // 拉黑维度必须与 [ImageGuardNetworkClient] 的查询维度一致 (调用方原始 url);
                // 解密失败时 outgoingRequest.url 已被书源 JS 改写, 拿它记录则守卫永远查不到
                originalUrl = request.url,
                isCover = request.extras[IsCoverKey] ?: true,
                response = response,
                source = source,
            )
            block(decodedResponse)
        }
    }

    private suspend fun decodeResponseIfNeeded(
        originalUrl: String,
        isCover: Boolean,
        response: NetworkResponse,
        source: BaseSource?,
    ): NetworkResponse {
        // 非 2xx 不解密 (由 NetworkFetcher / ImageGuardNetworkClient 处理异常或 304 无 body)
        if (response.code !in 200..299) return response
        val bookSource = source as? BookSource
        val ruleJs = if (isCover) bookSource?.coverDecodeJs else bookSource?.contentRule?.imageDecode
        if (ruleJs.isNullOrBlank()) return response

        val raw = Buffer()
        response.body?.use { it.writeTo(raw) } ?: return response
        if (raw.size == 0L) {
            return response.copy(body = BytesResponseBody(ByteArray(0)))
        }

        val decoded = runScriptWithContext {
            ImageUtils.decode(originalUrl, raw.readByteArray(), isCover = isCover, source)
        } ?: run {
            markFailUrl(originalUrl)
            throw NoStackTraceException("图片二次解密失败")
        }
        return response.copy(body = BytesResponseBody(decoded))
    }
}

/** 解密后字节的内存 body: 交付给 NetworkFetcher 并由其默认写入磁盘缓存。 */
private class BytesResponseBody(private val bytes: ByteArray) : NetworkResponseBody {
    override suspend fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    override suspend fun writeTo(fileSystem: FileSystem, path: Path) {
        fileSystem.write(path) { write(bytes) }
    }

    override fun close() {}
}
