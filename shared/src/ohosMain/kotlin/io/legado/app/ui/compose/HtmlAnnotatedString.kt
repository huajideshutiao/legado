package io.legado.app.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

// 鸿蒙: Compose ui-text ohosArm64 变体缺 AnnotatedString.Companion.fromHtml (Android 专属),
// 用 Ksoup 解析 HTML DOM + buildAnnotatedString 手工构建, 替代原 stub 纯文本降级
// 支持: b/strong(粗体) i/em(斜体) u(下划线) s/del/strike(删除线) br(换行) p(段落)
//       a(链接) font color(颜色) h1-h6(标题) hr(分隔) ul/ol/li(列表) blockquote(引用) mark(高亮)
// 不支持的标签降级为递归子节点纯文本
actual fun String.toHtmlAnnotatedString(): AnnotatedString {
    if (isEmpty()) return AnnotatedString("")
    // 解析失败回退纯文本 (与 stub 行为一致, 不抛异常给调用方)
    return runCatching {
        val body = Ksoup.parse(this).body()
        val state = HtmlWalkState()
        buildAnnotatedString {
            body.childNodes().forEach { walk(it, state) }
        }
    }.getOrElse { AnnotatedString(this) }
}

// 块级元素: 进入/退出时确保换行 (近似浏览器块级布局)
private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "main", "aside", "nav",
    "address", "figure", "figcaption",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li", "dl", "dt", "dd",
    "blockquote", "pre", "table", "tr"
)

// 跳过子节点的标签 (脚本/样式不渲染为文本)
private val SKIP_TAGS = setOf("script", "style", "head", "title", "meta", "link", "noscript")

// 连续空白折叠为单空格 (近似浏览器空白处理)
private val whitespaceRegex = Regex("\\s+")

// 链接默认色 (Material Blue 700, 与 MarkdownContent.ohos.kt 链接观感对齐)
private val LINK_COLOR = Color(0xFF1976D2)

// 跟踪 builder 末尾字符, 用于块级换行去重
private class HtmlWalkState {
    // 初始视为换行, 开头不补前置换行
    var lastChar: Char = '\n'
}

private fun AnnotatedString.Builder.walk(node: Node, state: HtmlWalkState) {
    when (node) {
        is TextNode -> {
            // 折叠连续空白 (含换行/缩进) 为单空格; 保留首尾单空格维持行内分词
            val normalized = node.getWholeText().replace(whitespaceRegex, " ")
            if (normalized.isNotEmpty()) {
                append(normalized)
                state.lastChar = normalized.last()
            }
        }
        is Element -> walkElement(node, state)
    }
}

private fun AnnotatedString.Builder.walkElement(el: Element, state: HtmlWalkState) {
    val tag = el.tagName().lowercase()
    when {
        tag in SKIP_TAGS -> return
        tag == "br" -> appendNewline(state)
        tag == "hr" -> {
            ensureNewline(state)
            append("———")
            appendNewline(state)
        }
        tag == "li" -> {
            ensureNewline(state)
            append("• ")
            state.lastChar = ' '
            el.childNodes().forEach { walk(it, state) }
            ensureNewline(state)
        }
        else -> {
            val isBlock = tag in BLOCK_TAGS
            if (isBlock) ensureNewline(state)
            val style = spanStyleFor(tag, el)
            val link = linkFor(el)
            when {
                link != null -> withLink(link) {
                    el.childNodes().forEach { walk(it, state) }
                }
                style != null -> withStyle(style) {
                    el.childNodes().forEach { walk(it, state) }
                }
                else -> el.childNodes().forEach { walk(it, state) }
            }
            if (isBlock) ensureNewline(state)
        }
    }
}

private fun AnnotatedString.Builder.appendNewline(state: HtmlWalkState) {
    append('\n')
    state.lastChar = '\n'
}

private fun AnnotatedString.Builder.ensureNewline(state: HtmlWalkState) {
    if (state.lastChar != '\n') appendNewline(state)
}

// 行内样式映射 (a 标签样式由 linkFor 的 TextLinkStyles 提供, 不在此返回)
private fun spanStyleFor(tag: String, el: Element): SpanStyle? = when (tag) {
    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
    "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "h1", "h2", "h3", "h4", "h5", "h6" -> SpanStyle(fontWeight = FontWeight.Bold)
    "font" -> el.attr("color").takeIf { it.isNotBlank() }
        ?.let { parseColor(it) }
        ?.let { SpanStyle(color = it) }
    "mark" -> SpanStyle(background = Color(0xFFFFFF00))
    else -> null
}

private fun linkFor(el: Element): LinkAnnotation? {
    if (el.tagName().lowercase() != "a") return null
    // href 为空时无点击意义, 退为纯文本 (无 LinkAnnotation)
    val href = el.attr("href").ifBlank { el.absUrl("href") }
    if (href.isEmpty()) return null
    return LinkAnnotation.Url(
        href,
        TextLinkStyles(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)),
    )
}

// 解析 HTML 颜色值 (#RGB / #RRGGBB / #AARRGGBB / 常见颜色名), 失败返回 null
private fun parseColor(value: String): Color? {
    val v = value.trim()
    if (v.isEmpty()) return null
    return when {
        v.startsWith("#") -> parseHexColor(v.removePrefix("#"))
        else -> namedColor(v.lowercase())
    }
}

private fun parseHexColor(hex: String): Color? = runCatching {
    when (hex.length) {
        3 -> Color(
            red = (hex[0].digitToInt(16) * 17) / 255f,
            green = (hex[1].digitToInt(16) * 17) / 255f,
            blue = (hex[2].digitToInt(16) * 17) / 255f,
        )
        6 -> Color(
            red = hex.substring(0, 2).toInt(16) / 255f,
            green = hex.substring(2, 4).toInt(16) / 255f,
            blue = hex.substring(4, 6).toInt(16) / 255f,
        )
        8 -> Color(
            alpha = hex.substring(0, 2).toInt(16) / 255f,
            red = hex.substring(2, 4).toInt(16) / 255f,
            green = hex.substring(4, 6).toInt(16) / 255f,
            blue = hex.substring(6, 8).toInt(16) / 255f,
        )
        else -> null
    }
}.getOrNull()

private fun namedColor(name: String): Color? = when (name) {
    "black" -> Color(0xFF000000)
    "white" -> Color(0xFFFFFFFF)
    "red" -> Color(0xFFFF0000)
    "green" -> Color(0xFF008000)
    "blue" -> Color(0xFF0000FF)
    "yellow" -> Color(0xFFFFFF00)
    "cyan", "aqua" -> Color(0xFF00FFFF)
    "magenta", "fuchsia" -> Color(0xFFFF00FF)
    "gray", "grey" -> Color(0xFF808080)
    "silver" -> Color(0xFFC0C0C0)
    "maroon" -> Color(0xFF800000)
    "olive" -> Color(0xFF808000)
    "lime" -> Color(0xFF00FF00)
    "purple" -> Color(0xFF800080)
    "teal" -> Color(0xFF008080)
    "navy" -> Color(0xFF000080)
    "orange" -> Color(0xFFFFA500)
    "pink" -> Color(0xFFFFC0CB)
    "brown" -> Color(0xFFA52A2A)
    else -> null
}
