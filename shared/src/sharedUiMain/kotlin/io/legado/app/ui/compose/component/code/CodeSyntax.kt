package io.legado.app.ui.compose.component.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 语法着色规则 (1:1 移植 app 端 `ui/widget/code/CodeViewExtensions.kt` 的四组 pattern)。
 *
 * 颜色取自原版 `addLegadoPattern/addJsonPattern/addJsPattern` 传入值:
 * md_orange_900 #E65100 / md_blue_grey_500 #607D8B / md_light_blue_600 #039BE5,
 * json 组用 ThemeStore 动态 accentColor (见 [rememberCodeSyntax])。
 */
@Immutable
data class CodeSyntaxRule(val regex: Regex, val color: Color)

@Immutable
data class CodeSyntaxScheme(val rules: List<CodeSyntaxRule>) {
    companion object {
        val None = CodeSyntaxScheme(emptyList())
    }
}

/** 四组正则, 与原版 Pattern 字面量逐字一致 */
object CodePatterns {

    val legado = Regex("""\|\||&&|%%|@@|@(?:js|Json|css|XPath):""")

    val json =
        Regex("""(?<!\\)(?:"(?:\\.|[^\\"\n])*"|'(?:\\.|[^\\'\n])*'|`(?:\\.|[^\\`\n])*`)|[\[\]{}]""")

    val wrap = Regex("""\\n""")

    val operation = Regex("""!=|[:=><%+\-^&|?*]""")

    val js =
        Regex("""\b(?:var|let|const|if|else|for|while|do|switch|case|break|continue|return|new|this|true|false|null|undefined|in|typeof|try|catch|finally|throw|function|class)\b""")
}

private val ColorOrange900 = Color(0xFFE65100)
private val ColorBlueGrey500 = Color(0xFF607D8B)
private val ColorLightBlue600 = Color(0xFF039BE5)

/**
 * 按字段类型组合 pattern 集 (调用方自选)。参数语义对齐原版三个扩展函数。
 *
 * @param legado 规则符号组 `|| && %% @@ @js:/@Json:/@css:/@XPath:`
 * @param json 引号串 + 花/方括号 (着 accent 动态色)
 * @param js `\n` 转义 + 运算符 + JS 关键字三组
 */
@Composable
fun rememberCodeSyntax(
    legado: Boolean = false,
    json: Boolean = false,
    js: Boolean = false,
): CodeSyntaxScheme {
    val accent = AppTheme.colors.accent
    return remember(legado, json, js, accent) {
        val rules = buildList {
            if (legado) add(CodeSyntaxRule(CodePatterns.legado, ColorOrange900))
            if (json) add(CodeSyntaxRule(CodePatterns.json, accent))
            if (js) {
                add(CodeSyntaxRule(CodePatterns.wrap, ColorBlueGrey500))
                add(CodeSyntaxRule(CodePatterns.operation, ColorOrange900))
                add(CodeSyntaxRule(CodePatterns.js, ColorLightBlue600))
            }
        }
        CodeSyntaxScheme(rules)
    }
}

/**
 * 全量着色 (legado + json + js), 对齐 app 端 CodeDialog 的三连 add。
 */
@Composable
fun rememberFullCodeSyntax(): CodeSyntaxScheme =
    rememberCodeSyntax(legado = true, json = true, js = true)

/**
 * 只读展示用: 一次性算好着色文本 (按 text/scheme 记忆, 无需 VisualTransformation 的缓存折中)。
 */
@Composable
fun rememberHighlightedCode(text: String, syntax: CodeSyntaxScheme): AnnotatedString =
    remember(text, syntax) { buildHighlightedCode(text, syntax.rules) }

/**
 * 全 pattern 匹配汇总, 按 (start 升序, end 降序) 排序, 再顺序保留 `end > 已覆盖最大 end` 的 span。
 *
 * 去重规则 1:1 复刻 CodeView.highlightSyntax; [CodeTextField] 的增量着色也复用本函数
 * (只对变更区间所在行调用, 再平移回全文坐标)。
 */
internal fun matchCodeSpans(text: String, rules: List<CodeSyntaxRule>): ArrayList<CodeSpan> {
    val spans = ArrayList<CodeSpan>()
    if (text.isEmpty()) return spans
    for (rule in rules) {
        for (m in rule.regex.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (end > start) spans.add(CodeSpan(start, end, rule.color))
        }
    }
    spans.sortWith(compareBy({ it.start }, { -it.end }))
    val filtered = ArrayList<CodeSpan>(spans.size)
    var lastMaxEnd = -1
    for (span in spans) {
        if (span.end > lastMaxEnd) {
            filtered.add(span)
            lastMaxEnd = span.end
        }
    }
    return filtered
}

/** 生成着色后的 AnnotatedString (全量, 供只读展示用)。 */
internal fun buildHighlightedCode(text: String, rules: List<CodeSyntaxRule>): AnnotatedString {
    if (rules.isEmpty()) return AnnotatedString(text)
    val spans = matchCodeSpans(text, rules)
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            addStyle(SpanStyle(color = span.color), span.start, span.end)
        }
    }
}

internal class CodeSpan(val start: Int, val end: Int, val color: Color)
