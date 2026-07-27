package io.legado.app.help.image

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.ImageResult
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.source.SourceHelp

/**
 * Coil3 Extras key: 携带书源 bookUrl (sourceOrigin), 供 [SourceOriginHeaderInterceptor] 解析防盗链 header。
 *
 * 消费点构造 [ImageRequest] 时 `.extras.set(SourceOriginKey, sourceOrigin)`,
 * Interceptor 在 suspend chain 内自动 resolve header 注入, 消费点无需在 @Composable 内调 suspend。
 */
val SourceOriginKey = Extras.Key<String?>(default = null)

/**
 * 按 [sourceOrigin] (书源 bookUrl) 解析防盗链 header, 转 Coil3 [NetworkHeaders]。
 *
 * [SourceHelp.getSource] 为 suspend (调 DB/书源缓存), 调用方需在协程内。
 * 无书源/书源无 header 时返回 null。
 */
suspend fun resolveSourceHeaders(sourceOrigin: String?): NetworkHeaders? {
    if (sourceOrigin.isNullOrEmpty()) return null
    val source: BaseSource = SourceHelp.getSource(sourceOrigin) ?: return null
    val headerMap = source.getHeaderMap().takeIf { it.isNotEmpty() } ?: return null
    return NetworkHeaders.Builder().apply {
        headerMap.forEach { (k, v) -> add(k, v) }
    }.build()
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
        val headers = resolveSourceHeaders(sourceOrigin) ?: return chain.proceed()
        val newRequest = request.newBuilder()
            .httpHeaders(headers)
            .build()
        return chain.withRequest(newRequest).proceed()
    }
}

/** 消费点构造 ImageRequest 时便捷设置 sourceOrigin (替代 `.extras.set(SourceOriginKey, ...)`)。 */
fun ImageRequest.Builder.sourceOrigin(sourceOrigin: String?): ImageRequest.Builder =
    apply { extras.set(SourceOriginKey, sourceOrigin) }
