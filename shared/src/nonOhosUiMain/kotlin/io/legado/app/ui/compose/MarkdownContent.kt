package io.legado.app.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.legado.app.ui.compose.theme.AppTheme

/** 超过该长度 (字符) 的文档改用 LazyMarkdown 虚拟化渲染。 */
private const val MarkdownLazyThreshold = 16 * 1024

/**
 * 走 mikepenz 核心库而非 -m3 变体: m3 的 markdownTypography() 读 MaterialTheme.typography,
 * 桌面端未引入 material3 会 NoClassDefFoundError, 且项目锁 MD2 视觉。
 *
 * heading 字号照 Markwon 默认倍数 {2, 1.5, 1.17, 1, .83, .67} 乘 14sp 基准
 * (原版 dialog_text_view.xml 的 TextView 无显式 textSize, 走系统默认 14sp)。
 */
@Composable
actual fun MarkdownContent(content: String, modifier: Modifier) {
    val colors = AppTheme.colors
    val typography = remember(colors.accent) {
        val base = TextStyle(fontSize = 14.sp)
        val mono = base.copy(fontFamily = FontFamily.Monospace)
        DefaultMarkdownTypography(
            h1 = base.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
            h2 = base.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold),
            h3 = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            h4 = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            h5 = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            h6 = base.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
            text = base,
            code = mono,
            inlineCode = mono,
            quote = base.copy(fontStyle = FontStyle.Italic),
            paragraph = base,
            ordered = base,
            bullet = base,
            list = base,
            table = base,
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                )
            ),
        )
    }
    // 长文档 (帮助页/更新日志可达数十 KB) 走 LazyMarkdown 虚拟化渲染, 避免整篇组合卡顿;
    // 短文档走 Column 全量渲染, 保持内容紧凑 (LazyColumn 会撑满可用高度, 不适合短内容)
    val markdownState = rememberMarkdownState(content = content)
    Markdown(
        markdownState = markdownState,
        colors = DefaultMarkdownColors(
            // 正文色对齐原版 TextView 的 android:textColor="@color/secondaryText"
            text = colors.secondaryText,
            codeBackground = colors.primaryText.copy(alpha = 0.1f),
            inlineCodeBackground = colors.primaryText.copy(alpha = 0.1f),
            dividerColor = colors.secondaryText,
            tableBackground = colors.primaryText.copy(alpha = 0.02f),
        ),
        typography = typography,
        modifier = modifier,
        imageTransformer = Coil3ImageTransformerImpl,
        success = { state, components, m ->
            if (content.length > MarkdownLazyThreshold) {
                LazyMarkdownSuccess(state, components, m)
            } else {
                MarkdownSuccess(state, components, m)
            }
        },
    )
}
