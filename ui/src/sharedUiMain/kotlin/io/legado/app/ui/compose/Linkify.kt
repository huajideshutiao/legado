package io.legado.app.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import io.legado.app.utils.findLinkifyMatches

/**
 * 复刻 Linkify.WEB_URLS：用 LinkAnnotation.Url 标注纯文本中的网址片段。
 *
 * Text / ClickableText 组件内置处理 LinkAnnotation.Url 的点击打开（LocalUriHandler），
 * 逐项 SelectionContainer 负责长按选择，与短按链接不冲突（手势时长不同）。
 *
 * URL 查找与清洗逻辑在 [findLinkifyMatches] (commonMain, 可单测)：
 * 不吞书源 `,{...}` 请求规格、HTML 尖括号/引号包裹、尾部标点等噪声。
 *
 * @param text 原始文本（可能包含 URL）
 * @param linkColor 链接视觉颜色
 * @return 带 LinkAnnotation.Url 的 AnnotatedString
 */
fun linkifyText(text: String, linkColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var last = 0
        findLinkifyMatches(text).forEach { match ->
            append(text.substring(last, match.range.first))
            withLink(
                LinkAnnotation.Url(
                    match.url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                )
            ) {
                append(match.url)
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
}
