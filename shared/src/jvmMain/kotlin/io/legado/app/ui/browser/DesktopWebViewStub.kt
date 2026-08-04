package io.legado.app.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

/**
 * Desktop WebView 平台占位实现 (供 [LocalWebViewSlot] 注入)。
 *
 * 桌面端真实浏览器已由 [io.legado.desktop.ui.browser.DesktopWebViewSlot] 提供 (系统引擎
 * 独立窗口: WebView2/webkit2gtk/WKWebView), 本占位仅兜底无引擎场景, 不伪造渲染。
 */
@Composable
fun DesktopWebViewStub(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "desktop WebView 不可用\n$url",
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
    }
}
