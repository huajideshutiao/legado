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
 * 消费点构造 [ImageRequest] 时 `.extras.set(SourceOriginKey, sourceOrigin)`,
 * Interceptor 在 suspend chain 内自动 resolve header 注入, 消费点无需在 @Composable 内调 suspend。
 */
val SourceOriginKey = Extras.Key<String?>(default = null)

/**
 * 按 [sourceOrigin] (书源 bookUrl) 解析防盗链 header。
 *
 * 对齐原版 `AnalyzeUrl.getGlideUrl()`: source header + [SourceNetworkProviders] 中的 cookie,
 * 缺 cookie 会让需登录站点的封面 403/裂图。[imageUrl] 用于 source key 非 http 时的 domain 兜底,
 * 与原版 `AnalyzeUrl.domain` 取值一致。
 *
 * 返回裸 Map 而非 Coil3 [NetworkHeaders]: desktop 模块只依赖 shared 的 api 面,
 * coil3-network 是 implementation 依赖不可见; 裸 OkHttp 消费点也直接用 Map。
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
    if (source.enabledCookieJar == true) headerMap[cookieJarHeader] = "1"
    else headerMap.remove(cookieJarHeader)

    if (headerMap.isEmpty()) return null
    return headerMap
}

/**
 * Coil3 Interceptor: 从 request extras 取 [SourceOriginKey], 解析书源防盗链 header 注入 httpHeaders。
 *
 * 下沉到 shared 供 app/desktop 共用 (Coil3 批 2: 防盗链 header 注入单一数据源,
 * 消费点只传 sourceOrigin, 不各自 resolve)。注册到 ImageLoader.components。
 *
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
