package io.legado.desktop.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.platform.rememberString
import java.awt.Desktop
import java.net.URI

/**
 * Desktop WebView slot: 系统浏览器打开 URL (真实功能, 非 mock)。
 *
 * Compose Desktop 基于 Skia 渲染, 无内置 WebView; JavaFX WebView 需额外模块配置。
 * Login 场景需嵌入式 WebView 闭合 cookie 回调, 暂用系统浏览器作为过渡;
 * 后续可接入 JCEF (JBR Chromium Embedded) 实现嵌入渲染 + cookie 同步。
 */
@Composable
fun DesktopWebViewSlot(url: String, modifier: Modifier = Modifier) {
    val openLabel = rememberString("open_in_browser")
    val hintLabel = rememberString("web_view_open_hint")

    LaunchedEffect(url) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(hintLabel, Modifier.padding(16.dp))
            Button(onClick = {
                runCatching { Desktop.getDesktop().browse(URI(url)) }
            }) {
                Text(openLabel)
            }
        }
    }
}
