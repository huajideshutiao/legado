package io.legado.app.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * 复刻 Linkify.WEB_URLS：用 LinkAnnotation.Url 标注纯文本中的网址片段。
 *
 * Text / ClickableText 组件内置处理 LinkAnnotation.Url 的点击打开（LocalUriHandler），
 * 逐项 SelectionContainer 负责长按选择，与短按链接不冲突（手势时长不同）。
 *
 * @param text 原始文本（可能包含 URL）
 * @param linkColor 链接视觉颜色
 * @return 带 LinkAnnotation.Url 的 AnnotatedString
 */
fun linkifyText(text: String, linkColor: Color): AnnotatedString {
    val urlRegex = Regex("https?://[^\\s]+")
    return buildAnnotatedString {
        var last = 0
        urlRegex.findAll(text).forEach { match ->
            append(text.substring(last, match.range.first))
            val url = match.value
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                )
            ) {
                append(url)
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
}
