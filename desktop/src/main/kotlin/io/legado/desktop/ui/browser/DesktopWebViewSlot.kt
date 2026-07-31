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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.desktop.help.webview.DesktopWebViewEngine
import io.legado.desktop.help.webview.DesktopWebViewEngines
import io.legado.desktop.help.webview.WebViewWindowHandle
import io.legado.desktop.help.webview.WebViewWindowRequest
import java.awt.Desktop
import java.net.URI

/**
 * Desktop WebView slot (登录页 / RSS / WebView 路由共用)。
 *
 * 有内嵌引擎时开一个**独立浏览器窗口**加载 [url]; 引擎每次导航完成把 cookie 写回
 * CookieStore (对齐 shared androidMain `AndroidWebView` 的 onPageFinished→cookie 落库),
 * 于是登录态能被 OkHttp 复用, 闭合了原先"跳系统浏览器拿不回 cookie"的缺口。
 *
 * 之所以是独立窗口而非嵌进这块 Compose 区域: WebView2 必须跑在自建的 Win32 消息泵线程上,
 * 把它的 HWND 反挂到 AWT Canvas 会跨线程共享输入队列 (详见 WebView2Loop 的说明);
 * 登录/验证本就是弹窗语义, 独立窗口可接受。
 *
 * 引擎不可用时保持原行为: 直接调系统默认浏览器 (cookie 无法回收, 但不崩)。
 */
@Composable
fun DesktopWebViewSlot(url: String, modifier: Modifier = Modifier) {
    val engine = remember { DesktopWebViewEngines.get() }
    if (engine == null) {
        SystemBrowserFallback(url, modifier)
    } else {
        EngineWindowSlot(engine, url, modifier)
    }
}

@Composable
private fun EngineWindowSlot(
    engine: DesktopWebViewEngine,
    url: String,
    modifier: Modifier,
) {
    var windowClosed by remember(url) { mutableStateOf(false) }
    var handle by remember(url) { mutableStateOf<WebViewWindowHandle?>(null) }

    fun open(): WebViewWindowHandle? = engine.openWindow(
        WebViewWindowRequest(
            url = url,
            title = "legado",
            onClosed = { windowClosed = true },
        )
    ).also { if (it == null) AppLog.put("内置浏览器窗口打开失败: $url") }

    DisposableEffect(url) {
        val opened = open()
        handle = opened
        onDispose { opened?.close() }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (windowClosed) "浏览器窗口已关闭" else "已在内置浏览器窗口中打开, 完成后回到本页确认",
                Modifier.padding(16.dp),
                textAlign = TextAlign.Center,
            )
            Button(onClick = {
                handle?.close()
                handle = open()
                windowClosed = false
            }) {
                Text(if (windowClosed) "重新打开" else "重新加载")
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
        runCatching { Desktop.getDesktop().browse(URI(url)) }
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
                        onClick = { runCatching { Desktop.getDesktop().browse(URI(downloadUrl)) } },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("下载 WebView2 运行时")
                    }
                }
            }
            Button(
                onClick = { runCatching { Desktop.getDesktop().browse(URI(url)) } },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(openLabel)
            }
        }
    }
}
