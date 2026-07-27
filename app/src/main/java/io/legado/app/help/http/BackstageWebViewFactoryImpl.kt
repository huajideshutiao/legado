package io.legado.app.help.http

/**
 * [BackstageWebViewFactory] 安卓实现: 直接 new [BackstageWebView] 返回。
 *
 * BackstageWebView 自身已实现 [BackstageWebViewHandle], 工厂只做创建。
 * 注册时机: App.onCreate 调 [registerAndroidBackstageWebView]。
 *
 * P1-1a: 解除 AnalyzeUrl 对 BackstageWebView 直接 new 的依赖,
 * 让 AnalyzeUrl 主体下沉 shared 后经 [BackstageWebViewProviders] 拿到本工厂。
 */
object BackstageWebViewFactoryImpl : BackstageWebViewFactory {

    override fun create(
        url: String?,
        html: String?,
        encode: String?,
        tag: String?,
        headerMap: Map<String, String>?,
        sourceRegex: String?,
        overrideUrlRegex: String?,
        javaScript: String?,
        delayTime: Long,
    ): BackstageWebViewHandle = BackstageWebView(
        url = url,
        html = html,
        encode = encode,
        tag = tag,
        headerMap = headerMap,
        sourceRegex = sourceRegex,
        overrideUrlRegex = overrideUrlRegex,
        javaScript = javaScript,
        delayTime = delayTime,
    )
}

/** 安卓宿主启动早期注册 BackstageWebView 工厂。 */
fun registerAndroidBackstageWebView() {
    BackstageWebViewProviders.register(BackstageWebViewFactoryImpl)
}
