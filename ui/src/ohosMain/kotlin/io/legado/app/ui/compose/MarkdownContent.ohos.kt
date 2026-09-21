package io.legado.app.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.ArkUIView2
import androidx.compose.ui.napi.js
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.compose.theme.AppTheme

/**
 * ArkTS 侧 `registerComposeInteropBuilder` 的注册 key (需与 Index.ets 端字面量一致)。
 * 该 builder 在 Compose 槽位内创建 Web 组件 (about:blank 起载, 内容由 loadData 提供)。
 */
const val ARKUI_BUILDER_MARKDOWN_WEB: String = "legadoMarkdownWeb"

// ohos actual: WebView 渲染 (ArkUIView2 混排鸿蒙 Web 组件, marked.js + highlight.js)。
// 完整 viewer HTML 由 K/N 运行时从 composeResources 直读模板 + js/css 内联拼装
// (OhosMarkdownViewer.buildHtml → legado.buildMarkdownViewerHtml → ArkTS loadData),
// 支持表格/嵌套列表/图片加载/亮暗主题, 与帮助页同源资产, 无平台端资源副本。
@Composable
actual fun MarkdownContent(content: String, modifier: Modifier) {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val density = LocalDensity.current
    // 基准字号 14sp → vp (Web CSS px 与 ArkUI vp 1:1, 不乘密度)
    val baseFontVp = with(density) { 14.sp.toPx() } / density.density
    // 内容/主题/字号变化时经 tsfn 推送渲染请求; ArkTS 侧 Web 就绪后注入 renderMarkdown
    LaunchedEffect(content, isDark, baseFontVp) {
        OhosNativeBridge.sendMarkdown(
            OhosNativeBridge.MarkdownRenderPayload(
                content = content,
                isDark = isDark,
                fontSize = baseFontVp,
            )
        )
    }
    // 混排 ArkTS Web 组件: 位置/尺寸随 Compose 槽位 (对话框正文区), Web 内部滚动。
    // 参数全走字符串 (CPF js DSL 的最稳类型); ArkTS 侧实际以 tsfn push 的内容为准,
    // 此处参数仅作首帧兜底 (builder 可能先于 push 到达)。
    ArkUIView2(
        name = ARKUI_BUILDER_MARKDOWN_WEB,
        modifier = modifier,
        parameter = js {
            "content"(content)
            "isDark"(if (isDark) "1" else "0")
            "fontSize"(baseFontVp.toString())
        },
        background = Color.Transparent,
        interactive = true,
    )
}
