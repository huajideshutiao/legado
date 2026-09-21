package io.legado.app.help.http

import kotlin.concurrent.Volatile

/**
 * 后台 WebView 工厂。原 app 端 [BackstageWebView] 重度依赖 android.webkit.WebView,
 * 不能下沉 shared, 抽象为工厂接口 + provider 注入。
 *
 * shared 内 AnalyzeUrl 经 [BackstageWebViewProviders.get] 获取工厂实例,
 * 调用 [create] + [BackstageWebViewHandle.getStrResponse] 完成后台 WebView 请求,
 * 不反向依赖 app 的具体 WebView 实现。
 *
 * 宿主(安卓 App.onCreate)启动早期经 [BackstageWebViewProviders.register] 注册一次。
 *
 * P1-1a: 为 AnalyzeUrl 主体下沉 shared 做前置, 解除 BackstageWebView 安卓绑定阻塞。
 */
object BackstageWebViewProviders {
    @Volatile
    private var factory: BackstageWebViewFactory? = null

    /** 宿主启动早期注册一次(任何 AnalyzeUrl webView 调用之前)。 */
    fun register(factory: BackstageWebViewFactory) {
        this.factory = factory
    }

    /** 获取已注册工厂, 未注册抛 IllegalStateException。 */
    fun get(): BackstageWebViewFactory =
        factory ?: error("BackstageWebViewFactory 未注册(宿主启动早期 register)")
}

/**
 * 后台 WebView 工厂接口。create 返回 [BackstageWebViewHandle] 持有底层 WebView 实例,
 * 调用方经 handle.getStrResponse() 拿响应, 结束后自动销毁(由实现保证)。
 */
interface BackstageWebViewFactory {

    /**
     * 创建后台 WebView 实例并返回 handle。
     *
     * @param url 目标 URL(可选, 与 html 二选一)
     * @param html 直接加载的 HTML 内容(可选, 与 url 二选一)
     * @param encode HTML 编码(默认 utf-8)
     * @param tag cookie 保存标签(通常 source.getKey(), 用于 setCookie 到 CookieStore)
     * @param headerMap 请求头(含 UA 等)
     * @param sourceRegex 资源嗅探正则(匹配到即返回该资源 URL 作为响应)
     * @param overrideUrlRegex URL 重定向嗅探正则(匹配到即返回该 URL 作为响应)
     * @param javaScript 页面加载后注入执行的 JS(为空走默认 outerHTML 抓取)
     * @param delayTime JS 执行延迟(毫秒, 默认 1000)
     */
    fun create(
        url: String? = null,
        html: String? = null,
        encode: String? = null,
        tag: String? = null,
        headerMap: Map<String, String>? = null,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        javaScript: String? = null,
        delayTime: Long = 1000L,
    ): BackstageWebViewHandle
}

/**
 * 后台 WebView 实例 handle。调用方只关心 [getStrResponse], 销毁由实现内部处理
 * (coroutine cancel / getStrResponse 完成 / onError 触发时自动 destroy)。
 */
interface BackstageWebViewHandle {
    /** 挂起等待页面加载+JS 执行完成, 返回响应。超时/异常由实现抛出。 */
    suspend fun getStrResponse(): StrResponse
}
