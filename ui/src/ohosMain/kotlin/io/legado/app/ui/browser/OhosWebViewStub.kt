package io.legado.app.ui.browser

import kotlin.concurrent.Volatile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.ArkUIView2
import androidx.compose.ui.napi.js
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.ui.compose.theme.AppTheme

/**
 * ArkTS 侧 `registerComposeInteropBuilder` 的注册 key (需与 Index.ets 端字面量一致)。
 *
 * 该 builder 在 Compose 槽位内创建**可见** Web 组件 (about:blank 起载, 配置由 [OhosWebViewSlot]
 * 经 [OhosVisibleWebViewBridge] 命令下发), 位置/尺寸随 Compose 槽位。
 */
const val ARKUI_BUILDER_FOREGROUND_WEB: String = "legadoForegroundWeb"

/**
 * 鸿蒙前台 WebView slot (ArkTS 可见 Web 组件 + napi 桥)。
 *
 * # 渲染模型 (复用仓库既有 ArkUIView2 interop, 不另造机制)
 * 鸿蒙 `@ohos.web.webview` (Web_Controller) 仅提供 ArkTS API, 无 NDK C 接口, K/N 无法直接实例化;
 * 但仓库已有成熟范式把 ArkTS 原生组件混排进 Compose 树: [ArkUIView2] + ArkTS
 * `registerComposeInteropBuilder` —— Markdown 查看器 (`MarkdownContent.ohos.kt`) 与视频 surface
 * (`OhosVideoPlayPlatformProvider`) 都走这条。本 slot 照抄该范式: ArkTS 侧以
 * [ARKUI_BUILDER_FOREGROUND_WEB] 为 key 注册一个含 Web 组件的 @Builder, 在槽位内创建可见 Web;
 * Kotlin 侧只负责下发加载配置、转发页面事件、接 [WebViewCallbacks.host]。
 *
 * # 与后台 WebView 的分工
 * 书源后台抓取规则走隐藏 Web 组件 (`WebViewBridgeHandler.ets` + `legado.webViewCallback`);
 * 本 slot 走前台可见 Web + `legado.webControllerCallback`, 两条链各有独立 Web 实例与 tsfn, 互不抢占。
 *
 * # 接线内容
 * - 加载配置随参数 ([ARKUI_BUILDER_FOREGROUND_WEB] 的 `parameter`) 兜底下发, 并以
 *   [OhosVisibleWebViewBridge.show] 命令补全 headers/宽视口/登录标记 (ArkTS 未就绪时缓存待用);
 * - [WebViewCallbacks.host]: evaluateJavascript (异步, 经 [OhosVisibleWebViewBridge.evaluateJavascript]) /
 *   canGoBack / goBack / exitFullScreen / getUrl / reload, 供 WebViewRoute 执行验证回传与返回导航;
 * - [WebViewCallbacks.onPageFinished] / [WebViewCallbacks.onUrlChanged] / [WebViewCallbacks.onReceivedTitle] /
 *   [WebViewCallbacks.onProgressChanged] / [WebViewCallbacks.onCloseWindow]: ArkTS 页面事件经
 *   [OhosVisibleWebViewBridge] 回推后转发;
 * - cookie 同步: onPageFinished 时把页面 host 域 cookie 写回 [CookieStoreProviders] (对齐 Android/iOS
 *   slot 语义), [WebViewConfig.isLogin] 时再按 [WebViewConfig.sourceKey] 写一份供 OkHttp 复用登录态。
 *
 * # 未就绪降级
 * ArkTS 未调 `legado.registerWebControllerCallback` 时 [OhosVisibleWebViewBridge.isReady] 为 false,
 * 命令静默丢弃, 本 slot 只显示占位文本 (与历史行为一致, 不影响其他端)。
 */
@Composable
fun OhosWebViewSlot(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    // 事件回调读最新 callbacks/config (事件由 ArkTS 异步回推, 不能捕获首帧实例);
    // DisposableEffect(Unit) 不会随重组重跑, 故必须经 rememberUpdatedState 拿当前值
    val latestCallbacks by rememberUpdatedState(callbacks)
    val latestConfig by rememberUpdatedState(config)

    // host 接线: 路由执行验证回传 (evaluateJavascript) 与返回导航
    SideEffect {
        callbacks.host = object : WebViewHost {
            override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
                OhosVisibleWebViewBridge.evaluateJavascript(script, onResult)
            }

            override fun canGoBack(): Boolean = OhosVisibleWebViewBridge.canGoBack()
            override fun goBack() = OhosVisibleWebViewBridge.goBack()
            override fun exitFullScreen() = OhosVisibleWebViewBridge.exitFullScreen()
            override fun getUrl(): String? = OhosVisibleWebViewBridge.getUrl()
            override fun reload() = OhosVisibleWebViewBridge.reload()
        }
    }

    // 页面事件 → callbacks (+ cookie 同步); 离开组合时解绑
    DisposableEffect(Unit) {
        OhosVisibleWebViewBridge.setPageListener { event ->
            when (event.event) {
                "onPageFinished" -> {
                    val cfg = latestConfig
                    val url = event.url
                    val cookie = event.cookie
                    if (!url.isNullOrBlank() && !cookie.isNullOrBlank()) {
                        val store = CookieStoreProviders.get()
                        store?.setCookie(url, cookie)
                        if (cfg.isLogin && cfg.sourceKey.isNotBlank()) {
                            store?.setCookie(cfg.sourceKey, cookie)
                        }
                    }
                    latestCallbacks.onPageFinished?.invoke(url)
                    url?.let { latestCallbacks.onUrlChanged?.invoke(it) }
                }

                "onUrlChanged" -> event.url?.let { latestCallbacks.onUrlChanged?.invoke(it) }
                "onReceivedTitle" -> latestCallbacks.onReceivedTitle?.invoke(event.title)
                "onProgressChanged" -> event.progress?.let {
                    latestCallbacks.onProgressChanged?.invoke(it)
                }

                "onCloseWindow" -> latestCallbacks.onCloseWindow?.invoke()
            }
        }
        onDispose {
            OhosVisibleWebViewBridge.clearPageListener()
            latestCallbacks.host = null
        }
    }

    // 配置变化时下发完整加载配置 (url/html/headers/宽视口/登录标记)。
    // 参数为配置值而非对象: 路由重建同值 WebViewConfig 实例时不重复发命令
    LaunchedEffect(
        config.url,
        config.html,
        config.headerMap,
        config.wideViewPort,
        config.isLogin,
        config.sourceKey,
    ) {
        OhosVisibleWebViewBridge.show(config)
    }

    // 可见 Web 由 ArkTS interop builder 在本槽位内创建 (与 Markdown 查看器同一范式);
    // 参数仅作首帧兜底 (builder 可能先于 show 命令到达), 完整配置以 show 命令为准。
    if (OhosVisibleWebViewBridge.isReady()) {
        ArkUIView2(
            name = ARKUI_BUILDER_FOREGROUND_WEB,
            modifier = modifier.fillMaxSize(),
            parameter = js {
                "url"(config.url)
                "html"(config.html ?: "")
                "wideViewPort"(if (config.wideViewPort) "1" else "0")
                "isLogin"(if (config.isLogin) "1" else "0")
                "sourceKey"(config.sourceKey)
            },
            background = Color.Transparent,
            interactive = true,
        )
    } else {
        // 桥未就绪: 保留历史占位文本 (不渲染空 Web, 也不影响其他端)
        Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "ohos WebView 桥未就绪\n${config.url}",
                color = AppTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 鸿蒙 WebView 桥接接口 (expect/actual 等价骨架)。
 *
 * 真实实现 [OhosVisibleWebViewBridge] 由 @CName `legado_register_web_controller_fn` 注入
 * (napi 桥就绪时); 未注入时 [NoOpOhosWebViewBridge] 占位。
 */
interface OhosWebViewBridge {

    /** 加载 URL, ArkTS 侧 Web_Controller.loadUrl(url)。 */
    fun loadUrl(url: String)

    /** 执行 JS, ArkTS 侧 webview.controller.runJavaScript(script)。 */
    fun runJavaScript(script: String): String?

    /** 取当前 url 的 cookie, ArkTS 侧 Web_Controller.getCookie() → 同步 CookieStoreProviders。 */
    fun getCookies(url: String): String?

    companion object {
        @Volatile
        private var impl: OhosWebViewBridge = NoOpOhosWebViewBridge

        /** 取当前 actual (未注入真实桥接时返回 NoOp)。 */
        fun get(): OhosWebViewBridge = impl

        /** napi 桥就绪后注入真实 actual (legado_napi.cpp registerWebController 经 @CName 调用)。 */
        fun register(bridge: OhosWebViewBridge) {
            impl = bridge
        }
    }
}

/** 占位 actual: 所有方法 no-op, napi 桥接未接入期间兜底。 */
private object NoOpOhosWebViewBridge : OhosWebViewBridge {
    override fun loadUrl(url: String) = Unit
    override fun runJavaScript(script: String): String? = null
    override fun getCookies(url: String): String? = null
}
