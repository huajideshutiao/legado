package io.legado.desktop.help.http

import io.legado.app.help.http.BackstageWebViewFactory
import io.legado.app.help.http.BackstageWebViewHandle
import io.legado.app.help.http.BackstageWebViewProviders

/**
 * 桌面端 [BackstageWebViewFactory] stub 实现: create 直接抛 [UnsupportedOperationException]。
 *
 * 后台 WebView 重度依赖 android.webkit.WebView (Chrome WebView component), 桌面端无对应
 * JVM 等价 (JavaFX WebView 需引入 JavaFX 依赖且 API 差异大, 不在 KP 范围)。当前桌面端
 * 注册 stub 工厂, 让 AnalyzeUrl 走 webView 路径时抛异常被 runCatching 吞掉, 自动回退到
 * HTTP 路径 (用 OkHttpClient 直接请求, 不执行 JS), 表现为:
 *
 * - 不依赖 webView 的书源 (绝大多数): 行为完全正常
 * - 依赖 webView 的书源 (少数, ruleUrl 含 @js: 注入或需要 JS 渲染): 加载失败,
 *   调用方显示 "获取数据失败" 提示, 不影响其他书源
 *
 * 注册时机: desktop main 入口早期, 任何 shared commonMain 中 AnalyzeUrl 调用
 * `BackstageWebViewProviders.get().create(...)` 之前。
 *
 * 模式参考 app 端 `registerAndroidBackstageWebView` (BackstageWebViewFactoryImpl.kt),
 * 仅 create 实现差异 (app 端 new BackstageWebView 返回 handle, 桌面端抛异常 stub)。
 *
 * # 未注册影响
 * 未注册时 `BackstageWebViewProviders.get()` 抛 IllegalStateException
 * "BackstageWebViewFactory 未注册", 与本 stub 抛 UnsupportedOperationException 行为等价
 * (都被调用方 runCatching 吞掉), 但显式注册保持与 app 端调用模式一致, 避免未来 shared
 * 内增加 "未注册时直接抛 ISE 跳过 webView" 的优化分支导致桌面端行为变化。
 */
private object DesktopBackstageWebViewFactory : BackstageWebViewFactory {

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
    ): BackstageWebViewHandle {
        // 桌面端无 WebView, 直接抛异常让调用方 runCatching 回退到 HTTP 路径
        throw UnsupportedOperationException(
            "桌面端不支持 BackstageWebView (依赖 android.webkit.WebView), " +
                "请改用 HTTP 路径或选用不依赖 webView 的书源"
        )
    }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopBackstageWebView() {
    BackstageWebViewProviders.register(DesktopBackstageWebViewFactory)
}
