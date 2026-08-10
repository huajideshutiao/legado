package io.legado.app.help.image

import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.cookieJarHeader
import io.legado.app.help.http.mergeCookies
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceNetworkProviders
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import io.legado.app.utils.NetworkUtils
import kotlin.coroutines.coroutineContext

/**
 * Coil3 Extras key: 携带书源 bookUrl (sourceOrigin), 供 fetcher 层解析防盗链 header。
 *
 * 消费点构造 [ImageRequest] 时 `.extras.set(SourceOriginKey, sourceOrigin)`,
 * fetcher 在真正取数据时 (IO 线程) 自动 resolve header 注入, 消费点无需在 @Composable 内调 suspend。
 */
val SourceOriginKey = Extras.Key<String?>(default = null)

/** Coil3 Extras key: 非 wifi 且 loadOnlyWifi 时只在 fetcher 层拦网络获取 (对齐原版 Glide `loadOnlyWifiOption`)。 */
val LoadOnlyWifiKey = Extras.Key<Boolean>(default = false)

/** Coil3 Extras key: 封面落持久磁盘分区, 与 `diskCacheKey(coverDiskCacheKey(url))` 配套 (CoverDecodeFetcher 据此给手动写盘 key 加 #covers 后缀)。 */
val PersistentCoverKey = Extras.Key<Boolean>(default = false)

/**
 * 按 [sourceOrigin] (书源 bookUrl) 解析防盗链 header。
 *
 * 对齐原版 `AnalyzeUrl.getGlideUrl()`: 构造 AnalyzeUrl 解析 header (请求头规则 JS 在 AnalyzeUrl
 * 环境执行, java 可访问 urlNoQuery/url 等属性) + [SourceNetworkProviders] 中的 cookie,
 * 缺 cookie 会让需登录站点的封面 403/裂图。[imageUrl] 用于 source key 非 http 时的 domain 兜底,
 * 与原版 `AnalyzeUrl.domain` 取值一致。
 *
 * 返回裸 Map 而非 Coil3 [NetworkHeaders]: desktop 模块只依赖 shared 的 api 面,
 * coil3-network 是 implementation 依赖不可见; 裸 OkHttp 消费点也直接用 Map。
 *
 * [SourceHelp.getSource] 为 suspend (调 DB/书源缓存), 调用方需在协程内。
 * 无书源/最终无 header 时返回 null。
 *
 */
suspend fun resolveSourceHeaders(
    sourceOrigin: String?,
    imageUrl: String? = null
): Map<String, String>? {
    if (sourceOrigin.isNullOrEmpty()) return null
    val source: BaseSource = SourceHelp.getSource(sourceOrigin) ?: return null
    val ctx = coroutineContext
    // 对齐原版 AnalyzeUrl(url).getGlideUrl(): 构造 AnalyzeUrl 取 headerMap。
    // 请求头规则 JS 经 AnalyzeUrlCore.evalJS 执行, java = AnalyzeUrl 实例 (urlNoQuery 可用);
    // 若走 source.getHeaderMap() (BaseSource.evalJS), java 是书源包装器, 无 urlNoQuery,
    // header 规则里 java.urlNoQuery 会取到 undefined (原版图片路径可用的回归)。
    val analyzeUrl = AnalyzeUrlFactories.create(
        rawUrl = imageUrl ?: sourceOrigin,
        source = source,
        coroutineContext = ctx
    )
    val headerMap = LinkedHashMap(analyzeUrl.headerMap)
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
 * Coil3 Fetcher 包装: 从 options extras 取 [SourceOriginKey], 解析书源防盗链 header 注入
 * httpHeaders 后委托内层网络 fetcher (OkHttpNetworkFetcher)。
 *
 * 下沉到 fetcher 层 (对齐原 Glide OkHttpStreamFetcher.loadData): 内存缓存命中不执行;
 * 只在真正取数据时解析, 且天然跑在 fetcherCoroutineContext (IO), 不再每请求跑主线程 DB 查询。
 *
 * 委托而非复制请求构造: 内层 NetworkFetcher 的磁盘缓存/缓存策略/HttpException 语义零漂移,
 * 仅在其 options 的 extras 中注入 httpHeaders key (NetworkFetcher.newRequest 读取,
 * 与旧 Interceptor 一致, 解析出的 header 覆盖消费点显式设置)。
 */
class SourceOriginHeaderFetcher(
    private val data: Uri,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val inner: Fetcher,
    private val delegate: Fetcher.Factory<Uri>,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val sourceOrigin = options.extras[SourceOriginKey]
        val headers = if (sourceOrigin.isNullOrEmpty()) {
            null
        } else {
            resolveSourceHeaders(sourceOrigin, data.toString())
        }
        if (headers.isNullOrEmpty()) return inner.fetch()
        val networkHeaders = NetworkHeaders.Builder().apply {
            headers.forEach { (k, v) -> add(k, v) }
        }.build()
        val newOptions = options.copy(
            extras = options.extras.newBuilder()
                .set(Extras.Key.httpHeaders, networkHeaders)
                .build()
        )
        return delegate.create(data, newOptions, imageLoader)?.fetch() ?: inner.fetch()
    }

    /** 包住网络 fetcher Factory (OkHttp/Ktor), 只处理 http(s) Uri。 */
    class Factory(
        private val delegate: Fetcher.Factory<Uri>,
    ) : Fetcher.Factory<Uri> {

        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val inner = delegate.create(data, options, imageLoader) ?: return null
            return SourceOriginHeaderFetcher(data, options, imageLoader, inner, delegate)
        }
    }
}

/** 消费点构造 ImageRequest 时便捷设置 sourceOrigin (替代 `.extras.set(SourceOriginKey, ...)`)。 */
fun ImageRequest.Builder.sourceOrigin(sourceOrigin: String?): ImageRequest.Builder =
    apply { extras.set(SourceOriginKey, sourceOrigin) }
