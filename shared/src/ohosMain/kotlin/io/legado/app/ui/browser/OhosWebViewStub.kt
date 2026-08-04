package io.legado.app.ui.browser

import kotlin.concurrent.Volatile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 鸿蒙 WebView slot 桥接骨架 (依赖阻塞, 待 NAPI 桥接)。
 *
 * # 阻塞原因
 * 鸿蒙 `@ohos.web.webview` (Web_Controller) 仅提供 ArkTS API, 无 NDK C 接口,
 * Kotlin/Native 无法直接调用; 同时 Compose for OHOS 走 fusion-renderer (ArkUI RenderNode
 * GPU 合成), 不支持在 Compose 树内嵌入 ArkUI 原生 Web 组件。需经 napi 双向桥接:
 *
 * 1. ArkTS 侧: 在 EntryAbility 持有 Web 组件实例, 通过 @State 暴露 loadUrl/runJavaScript/
 *    getCookies 等方法, 注册为 napi 模块导出 (legado_napi.cpp dlopen/dlsym);
 * 2. Kotlin 侧: 通过 [OhosWebViewBridge] 接口调用, 实际实现由 napi_threadsafe_function
 *    调度到 ArkTS 主线程执行 (模式同 [io.legado.app.napi.OhosNativeBridge] toast/notification);
 * 3. Compose 渲染: Web 组件由 ArkTS 直接挂载到屏幕 (非 Compose 树内), Kotlin 侧 Composable
 *    仅做透明占位 + 触发 ArkTS Web 显示/隐藏, 路由返回时由 ArkTS dismiss。
 *
 * # 桥接骨架 (替代 expect/actual)
 * 因 commonMain 公共源集不允许平台特有 API (约束: 不下沉 desktop/iOS/ohos 代码到 shared),
 * 故在 ohosMain 内用 [OhosWebViewBridge] 接口 + [NoOpOhosWebViewBridge] actual 占位实现
 * 等价 expect/actual 骨架, napi 桥接完成后注入真实 actual 即可生效。
 *
 * # 当前状态
 * 上述 napi 桥接 (legado_napi.cpp registerWebController / WebControllerProxy) 未实现,
 * [OhosWebViewBridge.get] 返回 NoOp, 故本 slot 仍为占位; 调用方 (登录 Overlay/ReadRssRoute/
 * WebViewRoute) 行为与 stub 一致, 不影响其他端。
 *
 * # TODO 待办 (解除阻塞需要的工作)
 * - [ ] legado_napi.cpp: 新增 registerWebController(tsfn) 注入口, ArkTS 侧 EntryAbility
 *       创建 Web 组件并持有 controller 引用
 * - [ ] OhosWebViewBridge actual: 实现 loadUrl/runJavaScript/getCookies, 通过 tsfn 调度
 * - [ ] EntryAbility.ets: 承载 Web 组件, 监听 napi 调用触发 loadUrl + onPageFinished 回调
 * - [ ] cookie 同步: onPageFinished 时 ArkTS 取 Web Cookie → napi 回调 → CookieStoreProviders
 * - [ ] Compose 渲染: 透明 Box 占位 + SideEffect 触发 ArkTS Web 显示 (当前仅占位)
 *
 * 对照 app 端 AndroidWebView / iOS IosWebViewSlot / desktop DesktopWebViewSlot 真实实现。
 */
@Composable
fun OhosWebViewSlot(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    // 触发 bridge 调用 (当前 NoOp, napi 桥接完成后改为真实 loadUrl + 占位透明 Box)
    OhosWebViewBridge.get()?.loadUrl(config.url)
    // TODO(ohos): NAPI 桥接接入后填充 callbacks.host (evaluateJavascript/canGoBack/goBack)
    // 与 callbacks.onPageFinished, 否则验证回传降级为 refetch 分支 (见 WebViewRoute)
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ohos WebView 待 NAPI 桥接\n${config.url}",
            color = AppTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 鸿蒙 WebView 桥接接口 (expect/actual 等价骨架)。
 *
 * napi 桥接完成后由 [register] 注入真实 actual (调用 ArkTS Web_Controller);
 * 当前仅 [NoOpOhosWebViewBridge] 占位。
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

        /** napi 桥接就绪后注入真实 actual (legado_napi.cpp registerWebController 调用)。 */
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
