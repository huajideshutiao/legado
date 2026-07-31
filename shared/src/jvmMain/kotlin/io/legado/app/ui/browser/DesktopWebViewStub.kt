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
 * TODO: desktop WebView 待 JavaFX (javafx.scene.web.WebView) 经 SwingNode/Compose 互操作集成;
 * 当前仅占位, 不伪造渲染, 等待 JavaFX WebView 接入。
 */
@Composable
fun DesktopWebViewStub(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "desktop WebView 待 JavaFX 集成\n$url",
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
    }
}
