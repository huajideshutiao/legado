package io.legado.desktop.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.ui.browser.WebViewCallbacks
import io.legado.app.ui.browser.WebViewConfig
import io.legado.app.ui.browser.WebViewHost
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.browseUrl
import io.legado.desktop.help.webview.DesktopWebViewEngine
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Desktop WebView slot (登录页 / RSS / WebView 路由共用)。
 *
 * 有内嵌引擎时开一个**独立浏览器窗口**加载 [config.url]; 引擎每次导航完成把 cookie 写回
 * CookieStore (对齐 shared androidMain `AndroidWebView` 的 onPageFinished→cookie 落库),
 * 于是登录态能被 OkHttp 复用, 闭合了原先"跳系统浏览器拿不回 cookie"的缺口。
 *
 * 之所以是独立窗口而非嵌进这块 Compose 区域: WebView2 必须跑在自建的 Win32 消息泵线程上,
 * 把它的 HWND 反挂到 AWT Canvas 会跨线程共享输入队列 (详见 WebView2Loop 的说明);
 * 登录/验证本就是弹窗语义, 独立窗口可接受。
 *
 * 引擎不可用时保持原行为: 直接调系统默认浏览器 (cookie 无法回收, 但不崩)。
 *
 * 验证回传: 窗口句柄已具备 [WebViewWindowHandle.evaluateJavascript] (三个系统引擎
 * 都支持任意 JS), 因此这里把 [WebViewCallbacks.host] 桥接为 [DesktopWebViewHost],
 * 并把导航完成事件接回 [WebViewCallbacks.onPageFinished], 于是 WebViewRoute 的
 * outerHTML 抓取与 CF 挑战自动检测 (对照 AndroidWebView 的 WebViewHostImpl + onPageFinished)
 * 在桌面端可用; 独立窗口带 CustomTab 式工具栏 (返回/前进/刷新/关闭/标题/进度,
 * 与书源验证窗口同一套实现), 路由侧 canGoBack/goBack 经句柄转发到窗口手动历史栈
 * (页面可后退则后退, 见 WebViewRoute.handleBack)。
 */
@Composable
fun DesktopWebViewSlot(
    config: WebViewConfig,
    modifier: Modifier = Modifier,
    callbacks: WebViewCallbacks = WebViewCallbacks(),
) {
    val url = config.url
    val engine = remember { DesktopWebViewEngines.get() }
    if (engine == null) {
        SystemBrowserFallback(url, modifier)
    } else {
        EngineWindowSlot(engine, config, modifier, callbacks)
    }
}

@Composable
private fun EngineWindowSlot(
    engine: DesktopWebViewEngine,
    config: WebViewConfig,
    modifier: Modifier,
    callbacks: WebViewCallbacks,
) {
    val url = config.url
    var windowClosed by remember(url) { mutableStateOf(false) }
    var handle by remember(url) { mutableStateOf<WebViewWindowHandle?>(null) }
    // 回调对象由路由 remember 持有, 重组时用最新实例 (对齐 AndroidWebView 的 callbacksRef)
    val callbacksRef by rememberUpdatedState(callbacks)

    fun open(): WebViewWindowHandle? = engine.openWindow(
        WebViewWindowRequest(
            url = url,
            title = "legado",
            // 2026-08-06 功能保留: 登录/验证场景必须带 isLogin (窗口"确定"= cookie 确认,
            // 对照原版 WebViewActivity isLogin 分支) + cookieTag (cookie 按书源回写)
            isLogin = config.isLogin,
            saveResult = config.saveResult,
            cookieTag = config.sourceKey.ifBlank { null },
            // 对照 AndroidWebViewClient.onPageFinished: 导航完成 → 路由侧 CF 检测/验证回传
            // 与页面 URL 状态同步 (页面内跳转后菜单取最新链接)
            onNavigated = { url ->
                callbacksRef.onPageFinished?.invoke(url)
                callbacksRef.onUrlChanged?.invoke(url)
            },
            // 页面元素全屏状态 (当前仅 WebKitGTK 引擎上报, 见 GtkSession.fullscreen-changed):
            // 桥接回路由侧 videoFullScreen —— 顶栏隐藏 + 返回键先退出全屏
            onFullScreenChanged = { full -> callbacksRef.onFullScreenChanged?.invoke(full) },
            onClosed = { windowClosed = true },
        )
    ).also { opened ->
        if (opened != null) {
            callbacksRef.host = DesktopWebViewHost(opened)
        } else {
            AppLog.put("内置浏览器窗口打开失败: $url")
        }
    }

    DisposableEffect(url) {
        val opened = open()
        handle = opened
        onDispose {
            opened?.close()
            callbacks.host = null
        }
    }

    Box(modifier.fillMaxSize()) {
        // 2026-08-06: 窗口打开成功时占位区域留空 (用户反馈: 去掉中间占位界面,
        // 点开直接出窗口); 仅窗口被关闭后显示重开入口, 避免用户无路可回
        if (windowClosed) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "浏览器窗口已关闭",
                    Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                )
                Button(onClick = {
                    handle?.close()
                    handle = open()
                    windowClosed = false
                }) {
                    Text("重新打开")
                }
            }
        }
    }
}

@Composable
private fun SystemBrowserFallback(url: String, modifier: Modifier) {
    val openLabel = rememberString("open_in_browser")
    val hintLabel = rememberString("web_view_open_hint")
    val guide = remember { DesktopWebViewEngines.installGuide() }
    DisposableEffect(url) {
        browseUrl(url)
        onDispose { }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(hintLabel, Modifier.padding(16.dp))
            if (guide != null) {
                Text(
                    guide.message,
                    Modifier.padding(horizontal = 24.dp),
                    textAlign = TextAlign.Center,
                )
                val downloadUrl = guide.downloadUrl
                if (downloadUrl != null) {
                    Button(
                        onClick = { browseUrl(downloadUrl) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("下载 WebView2 运行时")
                    }
                }
            }
            Button(
                onClick = { browseUrl(url) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(openLabel)
            }
        }
    }
}

/**
 * [WebViewHost] 的桌面实现: 桥接独立浏览器窗口 (系统引擎)。
 *
 * 语义对照 Android 端 [io.legado.app.ui.browser.AndroidWebView] 的 WebViewHostImpl:
 * - [evaluateJavascript] 经 [WebViewWindowHandle.evaluateJavascript] 执行 (引擎已归一为纯文本);
 * - canGoBack/goBack 转发窗口句柄的手动历史栈 (无历史时路由照旧出栈);
 * - exitFullScreen 经句柄 evaluateJavascript 执行 document.exitFullscreen()
 *   (标准 Fullscreen API, 页面未处于 JS 全屏时无操作); 页面元素全屏状态经窗口请求回调
 *   onFullScreenChanged 上报路由侧 videoFullScreen (当前仅 WebKitGTK fullscreen-changed
 *   信号, WebView2/WKWebView 无等价事件不跟踪), 原生窗口自身的全屏由系统处理;
 * - getUrl/reload 直通窗口句柄。
 */
private class DesktopWebViewHost(
    private val handle: WebViewWindowHandle,
) : WebViewHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
        // 引擎的 executeScript 是 suspend (COM 消息泵 / FX 线程桥), 起协程适配回调式接口
        scope.launch {
            val result = runCatching { handle.evaluateJavascript(script) }.getOrNull()
            onResult(result)
        }
    }

    override fun canGoBack(): Boolean = handle.canGoBack()

    override fun goBack() = handle.goBack()

    // 对照 AndroidWebView 的 customViewCallback.onCustomViewHidden: Fullscreen API
    // 标准动作, 页面未处于 JS 全屏时无操作 (原生窗口自身的视频全屏不受影响)
    override fun exitFullScreen() {
        scope.launch {
            runCatching { handle.evaluateJavascript("document.exitFullscreen()") }
        }
    }

    override fun getUrl(): String? = handle.currentUrl

    override fun reload() = handle.reload()
}
