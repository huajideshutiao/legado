package io.legado.app.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.ui.compose.theme.AppTheme

/**
 * WebView 渲染 slot 的 CompositionLocal: 默认兜底 [SharedWebViewPlaceholder]。
 *
 * 宿主端用 [CompositionLocalProvider] 覆盖注入 Android WebView、iOS WKWebView、
 * desktop 系统浏览器或鸿蒙 NAPI Web 能力,
 * 供 shared 路由 ([io.legado.app.ui.route.LoginRoute] / [io.legado.app.ui.route.ReadRssRoute] /
 * [io.legado.app.ui.route.WebViewRoute]) 渲染 WebView, 避免 shared 路由硬编码平台 WebView 组件。
 *
 * 签名 `(String, Modifier) -> Unit`: 参数为待加载 URL + 外部 Modifier。
 * cookie 持久化 (onPageFinished) 由平台 actual 内部处理, slot 调用方无需传回调。
 *
 * 模式参考 [io.legado.app.ui.bookshelf.LocalBookCoverSlot]。
 */
val LocalWebViewSlot = staticCompositionLocalOf<@Composable (String, Modifier) -> Unit> {
    @Composable { url, modifier -> SharedWebViewPlaceholder(url, modifier) }
}

/**
 * WebView 平台未注入时的兜底占位 (对照 [io.legado.app.ui.bookshelf.SharedBookCover] 占位语义)。
 *
 * 非伪造 WebView, 仅显示提示文本, 等待宿主端通过 [LocalWebViewSlot] 注入真实实现。
 */
@Composable
fun SharedWebViewPlaceholder(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "WebView 待平台注入\n$url",
            color = AppTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
