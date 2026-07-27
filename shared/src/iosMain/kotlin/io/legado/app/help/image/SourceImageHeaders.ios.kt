package io.legado.app.help.image

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.ImageResult
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.http.mergeCookies
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.utils.NetworkUtils

/**
 * Coil3 Extras key: 携带书源 bookUrl (sourceOrigin), 供 [SourceOriginHeaderInterceptor] 解析防盗链 header。
 *
 * 与 jvmAndAndroidMain 的 SourceImageHeaders.jvmAndAndroid.kt 同名同语义 (iOS 侧最小等价);
 * 逻辑仅依赖 commonMain 符号 + coil3 commonMain API, 后续可抽 commonMain 通用层合并两份。
 */
val SourceOriginKey = Extras.Key<String?>(default = null)

/**
 * 按 [sourceOrigin] (书源 bookUrl) 解析防盗链 header (对齐原版 `AnalyzeUrl.getGlideUrl()`)。
 *
 * 与 jvmAndAndroid 版差异: 不写入 [cookieJarHeader] 内部标记头 —— 该标记在 OkHttp 端由
 * CookieJar 桥接拦截器摘除, iOS Ktor 客户端无此桥 (IosHttpProvider 未注册 CookieJarBridge),
 * 写入会作为真实请求头发到服务器, 故一律移除。
 *
 * [SourceHelp.getSource] 为 suspend (调 DB/书源缓存), 调用方需在协程内。
 * 无书源/最终无 header 时返回 null。
 */
suspend fun resolveSourceHeaders(
    sourceOrigin: String?,
    imageUrl: String? = null
): Map<String, String>? {
    if (sourceOrigin.isNullOrEmpty()) return null
    val source: BaseSource = SourceHelp.getSource(sourceOrigin) ?: return null
    val headerMap = LinkedHashMap(source.getHeaderMap())
    // 原版 AnalyzeUrl init 把 header 里的 proxy 抽出作代理配置, 不当请求头发出
    headerMap.remove("proxy")

    // 对齐原版 AnalyzeUrl.setCookie(): 数据库 cookie 与 header 中的临时 cookie 合并, 后者优先
    val domain = NetworkUtils.getSubDomain(
        source.getKey().takeIf { it.startsWith("http") } ?: imageUrl ?: sourceOrigin
    )
    val cookie = SourceNetworkProviders.impl?.getCookie(domain) ?: ""
    if (cookie.isNotEmpty()) {
        mergeCookies(cookie, headerMap["Cookie"])?.let { headerMap["Cookie"] = it }
    }
    // iOS 无 CookieJar 桥, 内部标记头不发出 (见 KDoc)
    headerMap.remove(cookieJarHeader)

    if (headerMap.isEmpty()) return null
    return headerMap
}

/**
 * Coil3 Interceptor: 从 request extras 取 [SourceOriginKey], 解析书源防盗链 header 注入 httpHeaders。
 *
 * 注册到 iOS ImageLoader.components (BookImageLoader.ios.kt), 消费点只传 sourceOrigin。
 * 已带 httpHeaders 的 request 不覆盖 (消费点显式注入优先)。
 */
class SourceOriginHeaderInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val sourceOrigin = request.extras[SourceOriginKey]
        if (sourceOrigin.isNullOrEmpty()) {
            return chain.proceed()
        }
        val headers = resolveSourceHeaders(sourceOrigin, request.data as? String)
            ?: return chain.proceed()
        val networkHeaders = NetworkHeaders.Builder().apply {
            headers.forEach { (k, v) -> add(k, v) }
        }.build()
        val newRequest = request.newBuilder()
            .httpHeaders(networkHeaders)
            .build()
        return chain.withRequest(newRequest).proceed()
    }
}

/** 消费点构造 ImageRequest 时便捷设置 sourceOrigin (替代 `.extras.set(SourceOriginKey, ...)`)。 */
fun ImageRequest.Builder.sourceOrigin(sourceOrigin: String?): ImageRequest.Builder =
    apply { extras.set(SourceOriginKey, sourceOrigin) }
