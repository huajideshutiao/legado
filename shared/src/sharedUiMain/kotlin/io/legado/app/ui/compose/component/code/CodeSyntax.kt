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

    // 引号串用占有量词 *+ (原版为 *): 未闭合引号 + 超长文本时 * 会逐位置回溯 O(n²)
    // 递归深栈 → StackOverflowError (书源编辑打开即崩, 2026-08 实测); *+ 不回溯,
    // 未闭合引号直接失败, 匹配语义不变 (闭合串照常匹配) 且线性复杂度
    val json =
        Regex("""(?<!\\)(?:"(?:\\.|[^\\"\n])*+"|'(?:\\.|[^\\'\n])*+'|`(?:\\.|[^\\`\n])*+`)|[\[\]{}]""")

    val wrap = Regex("""\\n""")

    val operation = Regex("""!=|[:=><%+\-^&|?*]""")

    // 首字符分派 (原版为 27 词平铺交替): Java 正则对 alternation 逐个分支尝试,
    // 平铺时每个位置最多试 27 次; 按首字母分组后最多试 2-5 个 (词集 1:1, 匹配结果不变)
    val js = Regex(
        """\b(?:v(?:ar)|l(?:et)|c(?:onst|ase|ontinue|atch|lass)|i(?:f|n)|e(?:lse)|f(?:or|alse|inally|unction)|w(?:hile)|d(?:o)|s(?:witch)|b(?:reak)|r(?:eturn)|n(?:ew|ull)|t(?:his|rue|ry|ypeof|hrow)|u(?:ndefined))\b"""
    )
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
    val spans = mergeAdjacentSpans(matchCodeSpans(text, rules))
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            addStyle(SpanStyle(color = span.color), span.start, span.end)
        }
    }
}

/**
 * 相邻同色 span 合并 (prev.end == start 且颜色相同): AnnotatedString 相邻同 style 区间
 * 不自动合并, 大文本下 operator 等规则产生数千 span, 合并后布局/绘制按区间数线性下降。
 * 只合并连续同色区间, 不改变上色规则 (哪些文本上色、什么颜色均不变)。
 * 要求输入已按 start 升序 ([matchCodeSpans] 与增量路径的 kept 均满足)。
 */
internal fun mergeAdjacentSpans(spans: List<CodeSpan>): List<CodeSpan> {
    if (spans.size < 2) return spans
    val merged = ArrayList<CodeSpan>(spans.size)
    for (span in spans) {
        val last = merged.lastOrNull()
        if (last != null && last.end == span.start && last.color == span.color) {
            merged[merged.size - 1] = CodeSpan(last.start, span.end, span.color)
        } else {
            merged.add(span)
        }
    }
    return merged
}

internal class CodeSpan(val start: Int, val end: Int, val color: Color)
