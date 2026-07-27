package io.legado.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.theme.AppColors
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens

// ohos actual: multiplatformMarkdown 库未发布 ohosArm64 变体,
// 用 Compose 富文本 API 自实现简易 Markdown 渲染 (AnnotatedString + Column)
@Composable
actual fun MarkdownContent(content: String, modifier: Modifier) {
    val colors = AppTheme.colors
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingView(block, colors)
                is MdBlock.Paragraph -> Text(
                    text = parseInline(block.content, colors),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is MdBlock.Quote -> QuoteView(block, colors)
                is MdBlock.CodeBlock -> CodeBlockView(block, colors)
                is MdBlock.ListItem -> ListItemView(block, colors)
                MdBlock.HorizontalRule -> HorizontalRuleView(colors)
            }
        }
    }
}

// ============ Block Model ============

private sealed class MdBlock {
    data class Heading(val level: Int, val content: String) : MdBlock()
    data class Paragraph(val content: String) : MdBlock()
    data class Quote(val content: String) : MdBlock()
    data class CodeBlock(val code: String, val language: String?) : MdBlock()
    data class ListItem(
        val ordered: Boolean,
        val order: Int,
        val content: String,
        val indent: Int,
    ) : MdBlock()
    object HorizontalRule : MdBlock()
}

// ============ Block Parser ============

private fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val lines = content.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 代码块 ```
        if (trimmed.startsWith("```")) {
            val lang = trimmed.substring(3).trim().takeIf { it.isNotEmpty() }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // 跳过结束 ```
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), lang))
            continue
        }

        // 标题 # ~ ###### (容忍尾部 ### 形式)
        val headingMatch = HeadingRegex.matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            blocks.add(MdBlock.Heading(level, text))
            i++
            continue
        }

        // 水平分割线 --- *** ___
        if (HorizontalRuleRegex.matches(trimmed)) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // 引用 > (合并连续行)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MdBlock.Quote(quoteLines.joinToString("\n")))
            continue
        }

        // 无序列表 - * +
        val unorderedMatch = UnorderedListRegex.matchEntire(line)
        if (unorderedMatch != null) {
            val indent = line.takeWhile { it == ' ' }.length
            val itemContent = unorderedMatch.groupValues[1].trim()
            blocks.add(MdBlock.ListItem(ordered = false, order = 0, content = itemContent, indent = indent))
            i++
            continue
        }

        // 有序列表 1. 2.
        val orderedMatch = OrderedListRegex.matchEntire(line)
        if (orderedMatch != null) {
            val order = orderedMatch.groupValues[1].toIntOrNull() ?: 1
            val indent = line.takeWhile { it == ' ' }.length
            val itemContent = orderedMatch.groupValues[2].trim()
            blocks.add(MdBlock.ListItem(ordered = true, order = order, content = itemContent, indent = indent))
            i++
            continue
        }

        // 段落 (合并连续非特殊行; 仅当后续行真正命中块级语法时断开)
        val paraLines = mutableListOf(trimmed)
        i++
        while (i < lines.size) {
            val nextLine = lines[i]
            val nextTrim = nextLine.trim()
            if (nextTrim.isEmpty() ||
                HeadingRegex.matches(nextTrim) ||
                nextTrim.startsWith(">") ||
                nextTrim.startsWith("```") ||
                HorizontalRuleRegex.matches(nextTrim) ||
                UnorderedListRegex.matches(nextLine) ||
                OrderedListRegex.matches(nextLine)
            ) break
            paraLines.add(nextTrim)
            i++
        }
        blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
    }
    return blocks
}

private val HeadingRegex = Regex("^(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$")
private val HorizontalRuleRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val UnorderedListRegex = Regex("^\\s*[-*+]\\s+(.+)$")
private val OrderedListRegex = Regex("^\\s*(\\d+)\\.\\s+(.+)$")

// ============ Inline Parser ============

private data class InlineMatch(
    val start: Int,
    val end: Int,
    val type: InlineStyle,
    val content: String,
    val url: String? = null,
)

private enum class InlineStyle { BoldItalic, Bold, Italic, Code, Strike, Link, Image }

private val BoldItalicRegex = Regex("\\*\\*\\*(.+?)\\*\\*\\*|___(.+?)___")
private val BoldRegex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
private val ItalicRegex = Regex("\\*(.+?)\\*|_(.+?)_")
private val CodeRegex = Regex("``(.+?)``|`(.+?)`")
private val StrikeRegex = Regex("~~(.+?)~~")
private val ImageRegex = Regex("!\\[(.*?)]\\(([^)]+?)\\)")
private val LinkRegex = Regex("\\[(.+?)]\\(([^)]+?)\\)")

// 找最早出现的内联匹配 (start 最小者优先, 同位置按 BoldItalic > Bold > Italic > ... 优先级)
private fun findEarliestInlineMatch(text: String, from: Int): InlineMatch? {
    var best: InlineMatch? = null
    fun consider(m: InlineMatch) {
        val current = best
        if (current == null || m.start < current.start) best = m
    }
    BoldItalicRegex.find(text, from)?.let { mr ->
        val c = mr.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.BoldItalic, c))
    }
    BoldRegex.find(text, from)?.let { mr ->
        val c = mr.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.Bold, c))
    }
    ItalicRegex.find(text, from)?.let { mr ->
        val c = mr.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.Italic, c))
    }
    CodeRegex.find(text, from)?.let { mr ->
        val c = mr.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@let
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.Code, c))
    }
    StrikeRegex.find(text, from)?.let { mr ->
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.Strike, mr.groupValues[1]))
    }
    ImageRegex.find(text, from)?.let { mr ->
        // 图片无加载器 (ohos 端 Coil3 缺失), 降级显示 alt 文本
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.Image, mr.groupValues[1]))
    }
    LinkRegex.find(text, from)?.let { mr ->
        consider(InlineMatch(mr.range.first, mr.range.last + 1, InlineStyle.Link, mr.groupValues[1], mr.groupValues[2]))
    }
    return best
}

private fun parseInline(text: String, colors: AppColors): AnnotatedString = buildAnnotatedString {
    var pos = 0
    while (pos < text.length) {
        val match = findEarliestInlineMatch(text, pos)
        if (match == null) {
            append(text.substring(pos))
            break
        }
        if (match.start > pos) append(text.substring(pos, match.start))
        when (match.type) {
            InlineStyle.BoldItalic -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            ) { append(match.content) }
            InlineStyle.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.content) }
            InlineStyle.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.content) }
            InlineStyle.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.primaryText.copy(alpha = 0.1f),
                )
            ) { append(match.content) }
            InlineStyle.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.content) }
            InlineStyle.Link -> withLink(
                LinkAnnotation.Url(
                    match.url ?: "",
                    TextLinkStyles(SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)),
                )
            ) { append(match.content) }
            InlineStyle.Image -> {
                // 图片无加载器, 降级显示 alt 文本 (前缀 [图])
                val alt = match.content
                if (alt.isNotEmpty()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.secondaryText)) {
                        append("[图] $alt")
                    }
                }
            }
        }
        pos = match.end
    }
}

// ============ Block Views ============

@Composable
private fun HeadingView(block: MdBlock.Heading, colors: AppColors) {
    val (size, topPad) = when (block.level) {
        1 -> 22.sp to 12.dp
        2 -> 20.sp to 10.dp
        3 -> 18.sp to 8.dp
        4 -> 16.sp to 6.dp
        5 -> 15.sp to 4.dp
        else -> 14.sp to 4.dp
    }
    Text(
        text = parseInline(block.content, colors),
        color = colors.primaryText,
        fontWeight = FontWeight.Bold,
        fontSize = size,
        modifier = Modifier.padding(top = topPad, bottom = 4.dp),
    )
}

@Composable
private fun QuoteView(block: MdBlock.Quote, colors: AppColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp),
    ) {
        // 左侧引用条
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.secondaryText.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = parseInline(block.content, colors),
            color = colors.secondaryText,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock, colors: AppColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(DesignTokens.shapeSm)
            .background(colors.primaryText.copy(alpha = 0.08f))
            .padding(8.dp),
    ) {
        Text(
            text = block.code,
            color = colors.primaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ListItemView(block: MdBlock.ListItem, colors: AppColors) {
    val marker = if (block.ordered) "${block.order}." else "•"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.indent.coerceAtMost(8) * 2).dp, top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            text = marker,
            color = colors.primaryText,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = parseInline(block.content, colors),
            color = colors.primaryText,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HorizontalRuleView(colors: AppColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(colors.secondaryText.copy(alpha = 0.3f))
    )
}
