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
 * 与 jvmAndAndroidMain 的 SourceImageHeaders.jvmAndAndroid.kt 同名同语义 (iOS 侧最小等价);
 * 逻辑仅依赖 commonMain 符号 + coil3 commonMain API, 后续可抽 commonMain 通用层合并两份。
 */
val SourceOriginKey = Extras.Key<String?>(default = null)

/** Coil3 Extras key: 非 wifi 且 loadOnlyWifi 时只在 fetcher 层拦网络获取 (对齐原版 Glide `loadOnlyWifiOption`)。 */
val LoadOnlyWifiKey = Extras.Key<Boolean>(default = false)

/**
 * Coil3 Extras key: 请求是否为封面图 (default=true 保持封面语义, 兼容未显式标注的调用)。
 * fetcher 层据此选解密规则: 封面 → coverDecodeJs, 正文图 → contentRule.imageDecode
 * (对齐原版: 封面链 OkHttpStreamFetcher 用 coverDecodeJs, 正文链 BookHelp.saveImage 用 imageDecode)。
 */
val IsCoverKey = Extras.Key<Boolean>(default = true)

/**
 * 按 [sourceOrigin] (书源 bookUrl) 解析防盗链 header (对齐原版 `AnalyzeUrl.getGlideUrl()`)。
 *
 * 与 jvmAndAndroid 版差异: 不写入 [cookieJarHeader] 内部标记头 —— 该标记在 OkHttp 端由
 * CookieJar 桥接拦截器摘除, iOS Ktor 客户端无此桥 (NativeHttpProvider 未注册 CookieJarBridge),
 * 写入会作为真实请求头发到服务器, 故一律移除。
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
    // 若走 source.getHeaderMap() (BaseSource.evalJS), java 是书源包装器, 无 urlNoQuery (回归)。
    // 注: native 端未注册 AnalyzeUrlFactories, fallback 裸 AnalyzeUrlCore, 与 iOS 主请求链路一致。
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
    // iOS 无 CookieJar 桥, 内部标记头不发出 (见 KDoc)
    headerMap.remove(cookieJarHeader)

    if (headerMap.isEmpty()) return null
    return headerMap
}

/**
 * Coil3 Fetcher 包装: 从 options extras 取 [SourceOriginKey], 解析书源防盗链 header 注入
 * httpHeaders 后委托内层网络 fetcher (KtorNetworkFetcher)。
 *
 * 下沉到 fetcher 层 (对齐原 Glide OkHttpStreamFetcher.loadData): 内存缓存命中不执行;
 * 只在真正取数据时解析, 且天然跑在 fetcherCoroutineContext (IO), 不再每请求跑主线程 DB 查询。
 * 与 jvmAndAndroid 版同语义 (差异仅在内层 delegate 为 Ktor 网络 fetcher)。
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
