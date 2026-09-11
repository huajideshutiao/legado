package io.legado.app.help.image

import coil3.request.ImageRequest
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.analyzeRule.AnalyzeUrlFactories
import kotlin.coroutines.coroutineContext

/**
 * 按 [sourceOrigin] (书源 bookUrl) + 图片 [imageUrl] 解析真实请求 (url + 防盗链 header)。
 * android/jvm/ios 三端共享单份 (原 jvmAndAndroidMain / iosMain 两份重复实现合并, 差异只剩
 * cookieJar 伪头去留, 上移到 [SourceHeaderNetworkClient] 的装配参数)。
 *
 * 对齐原版 `AnalyzeUrl(url, source).getGlideUrl()`: 构造 AnalyzeUrl 取解析后的 url 与 headerMap
 * —— 请求头规则 JS 在 AnalyzeUrl 环境执行 (java = AnalyzeUrl 实例, `urlNoQuery`/`url` 可用;
 * 若走 source.getHeaderMap(), java 是书源包装器, 无 urlNoQuery, header 规则里的
 * `java.urlNoQuery` 会取到 undefined), cookie 由 AnalyzeUrlCore 的 setCookie 按其 domain 合并
 * (缺 cookie 会让需登录站点的封面 403/裂图)。
 *
 * 与改造前的三点差异 (均为向原版对齐):
 * 1. 一并返回解析后的 url —— header 规则 JS 改写 url、`url,{options}` 后缀剥离此前在封面链上丢失;
 * 2. 书源查不到时不再整链跳过解析, 以 `source = null` 构造 AnalyzeUrl (原版同样解析 URL 形态);
 * 3. cookie 合并/cookieJar 伪头交给 AnalyzeUrlCore.resolveImageRequest (即原版 setCookie) 统一负责,
 *    不再在多个源集手工重复一份 mergeCookies + domain 推导。
 *
 * 返回裸 Map 而非 Coil3 NetworkHeaders: desktop 模块只依赖 shared 的 api 面,
 * coil3-network 是 implementation 依赖不可见; 裸 OkHttp 消费点也直接用 Map。
 *
 * [SourceHelp.getSource] 为 suspend (先命中阅读/朗读会话缓存, 否则查库), 调用方需在协程内;
 * 本函数由 [SourceHeaderNetworkClient] 在 Coil3 磁盘缓存查询之后调用 (内存/磁盘命中不触发)。
 */
suspend fun resolveSourceRequest(
    sourceOrigin: String, imageUrl: String
): Pair<String, Map<String, String>> {
    val source: BaseSource? = SourceHelp.getSource(sourceOrigin)
    // 注: native (iOS) 端未注册 AnalyzeUrlFactories 实现, create 回落裸 AnalyzeUrlCore,
    // 与 iOS 主请求链路一致。
    val analyzeUrl = AnalyzeUrlFactories.create(
        rawUrl = imageUrl,
        source = source,
        coroutineContext = coroutineContext
    )
    val (url, headerMap) = analyzeUrl.resolveImageRequest()
    // 刻意偏离原版 (沿袭改造前行为, 非回归): 原版 `getGlideUrl()` 不剔 proxy, 而 AnalyzeUrl init
    // 的 urlHeaders 回填会把 URL 级 `{headers:{proxy}}` 重新塞回 headerMap 并发出 (source 级
    // proxy 已在 init 抽出作代理配置)。这里继续剔除, 不把代理配置当真请求头发出去。
    headerMap.remove("proxy")
    return url to headerMap
}

/** 消费点构造 ImageRequest 时便捷设置 sourceOrigin (替代 `.extras.set(SourceOriginKey, ...)`)。 */
fun ImageRequest.Builder.sourceOrigin(sourceOrigin: String?): ImageRequest.Builder =
    apply { extras.set(SourceOriginKey, sourceOrigin) }
