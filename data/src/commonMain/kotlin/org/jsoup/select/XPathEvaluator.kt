package org.jsoup.select

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.select.Elements
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.scan.BalanceScan
import kotlin.reflect.KClass

/**
 * Lightweight XPath evaluator for ksoup
 * Supports basic XPath 1.0 patterns used in legado book sources
 */
class XPathEvaluator {

    companion object {
        fun evaluate(xpath: String, element: Element): List<Node> {
            val evaluator = XPathEvaluator()
            return evaluator.eval(xpath, element)
        }

        fun evaluateElements(xpath: String, element: Element): Elements {
            val result = evaluate(xpath, element)
            return Elements(result.filterIsInstance<Element>())
        }
    }

    fun eval(xpath: String, element: Element): List<Node> {
        val trimmed = xpath.trim()

        // Handle attribute selection: path/@attr
        if (trimmed.contains("/@")) {
            val parts = trimmed.split("/@", limit = 2)
            val elementPath = parts[0].ifEmpty { "." }
            val attrName = parts[1]

            val elements = if (elementPath == ".") {
                listOf(element)
            } else {
                selectByPath(elementPath, element)
            }

            return elements.mapNotNull { el ->
                val attrValue = el.attr(attrName)
                if (attrValue.isNotEmpty()) {
                    TextNode(attrValue)
                } else {
                    null
                }
            }
        }

        // Handle regular element selection
        return selectByPath(trimmed, element)
    }

    private fun selectByPath(path: String, element: Element): List<Element> {
        val trimmed = path.trim()

        //处理开头的 / 或 //
        val isAbsolute = trimmed.startsWith("/")
        val isRecursiveRoot = trimmed.startsWith("//")
        val relativePath = when {
            isRecursiveRoot -> trimmed.substring(2)
            isAbsolute -> trimmed.substring(1)
            else -> trimmed
        }

        val root = if (isAbsolute) element.ownerDocument() ?: element else element

        //按 // 分割路径, 每段是相对路径, 段间为后代递归关系
        //如 "div//a/b" → ["div", "a/b"]；引号内与 `[]` 内的 `/` 不作分隔符
        val segments = BalanceScan.splitQuoted(relativePath, '/', pair = true).filter { it.isNotEmpty() }
        if (segments.isEmpty()) return listOf(root)

        //第一段: 若路径以 // 开头则递归搜索, 否则相对搜索
        var currentNodes = if (isRecursiveRoot) {
            selectDescendants(segments[0], root)
        } else {
            selectRelative(segments[0], root)
        }

        //后续每段都是后代递归搜索
        for (i in 1 until segments.size) {
            val nextNodes = mutableListOf<Element>()
            for (node in currentNodes) {
                nextNodes.addAll(selectDescendants(segments[i], node))
            }
            currentNodes = nextNodes
        }

        return currentNodes
    }

    /**
     * 后代递归搜索: 在 element 的所有后代中匹配 segment (segment 可含多级相对路径)
     */
    private fun selectDescendants(segment: String, element: Element): List<Element> {
        val parts = parsePathParts(segment)
        if (parts.isEmpty()) return listOf(element)

        //对第一段做递归后代搜索
        var currentNodes = selectStep(parts[0], element, descendants = true)

        //segment 内剩余路径段用相对路径处理 (如 "div/a" 中的 "/a")
        if (parts.size == 1) return currentNodes

        for (part in parts.drop(1)) {
            val nextNodes = mutableListOf<Element>()
            for (node in currentNodes) {
                nextNodes.addAll(selectStep(part, node, descendants = false))
            }
            currentNodes = nextNodes
        }
        return currentNodes
    }

    /**
     * 递归收集所有后代元素 (不含自身)
     */
    private fun collectAllDescendants(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        for (child in element.children()) {
            result.add(child)
            result.addAll(collectAllDescendants(child))
        }
        return result
    }

    private fun selectRelative(path: String, element: Element): List<Element> {
        if (path.isEmpty()) return listOf(element)

        var currentNodes = listOf(element)

        for (part in parsePathParts(path)) {
            val nextNodes = mutableListOf<Element>()
            for (node in currentNodes) {
                nextNodes.addAll(selectStep(part, node, descendants = false))
            }
            currentNodes = nextNodes
        }

        return currentNodes
    }

    private fun parsePathParts(path: String): List<PathPart> {
        val parts = mutableListOf<PathPart>()
        //引号内与 `[]` 内的 `/` 不作层级分隔：`//a[@href='/b/c']` 的值里带斜杠, 按裸 split 会被切成三截
        //→ getElementsByTag("a[@href='") 空 → 整条规则静默返回 0 结果
        val segments = BalanceScan.splitQuoted(path, '/', pair = false).filter { it.isNotEmpty() }

        for (segment in segments) {
            when {
                segment == ".." -> parts.add(PathPart.Parent)
                segment == "." -> parts.add(PathPart.Current)
                segment == "*" -> parts.add(PathPart.AllChildren)
                segment.contains("[") -> {
                    val (tagName, predicates) = parsePredicates(segment)
                    parts.add(PathPart.TagWithPredicate(tagName, predicates))
                }
                else -> parts.add(PathPart.Tag(segment))
            }
        }

        return parts
    }

    /**
     * 切出标签名与它的**全部**顶层谓词。
     * 谓词边界用计数式 [BalanceScan.chomp] 逐个拉出，并把连续的多个谓词（`div[a][b]`）全部
     * 收集后求交（基准是 XPath 1.0 的位置步语义）。
     * 本文件原先用 `indexOf('[')` + `lastIndexOf(']')` 只取一段，`div[a][b]` 会切成垃圾谓词
     * `a][b`，一条正则都不命中 → 落到谓词末尾的“不支持”分支，两个谓词**静默失效**、元素全量返回。
     *
     * 残缺（有 `[` 但无配对 `]`）时与原实现同形：整段当标签名、不带谓词，
     * 于是 `getElementsByTag("div[")` 取不到任何东西（宁可漏选，也不静默多选）。
     */
    private fun parsePredicates(segment: String): Pair<String, List<String>> {
        val first = segment.indexOf('[')
        if (first == -1) {
            return segment to emptyList()
        }

        val predicates = ArrayList<String>(2)
        var pos = first
        while (pos < segment.length && segment[pos] == '[') {
            //谓词内允许带引号的字面量（`@id='x'`），引号内符号不计层；引号内反斜杠无效
            val end = BalanceScan.chomp(
                segment, pos, '[', ']',
                quote = true, escape = BalanceScan.Escape.OUTSIDE_QUOTES
            )
            if (end < 0) {
                return segment to emptyList()
            }
            predicates.add(segment.substring(pos + 1, end - 1).trim()) //谓词两侧空白容忍, 不致 `div[ 1 ]` 被判成不支持
            pos = end
            while (pos < segment.length && segment[pos] == ' ') pos++ //谓词间空白容忍
        }

        return segment.substring(0, first) to predicates
    }

    /**
     * 单个路径步的选取。与 [selectDescendants] 共用同一份 `when`（此前两处各写一遍，
     * 同名分支只有一处差异：[PathPart.AllChildren] 与空标签的 [PathPart.TagWithPredicate]
     * 在后代步下取全部后代、在相对步下只取直接子级，故用 [descendants] 显式区分）。
     */
    private fun selectStep(part: PathPart, element: Element, descendants: Boolean): List<Element> {
        return when (part) {
            is PathPart.Parent -> {
                val parent = element.parent()
                if (parent is Element) listOf(parent) else emptyList()
            }
            is PathPart.Current -> listOf(element)
            is PathPart.AllChildren ->
                if (descendants) collectAllDescendants(element) else element.children()
            is PathPart.Tag -> element.getElementsByTag(part.name)
            is PathPart.TagWithPredicate -> {
                val elements = if (part.tag.isEmpty()) {
                    if (descendants) collectAllDescendants(element) else element.children()
                } else {
                    element.getElementsByTag(part.tag)
                }
                filterByPredicate(elements, part.predicates)
            }
        }
    }

    /** 多个谓词求交：`div[a][b]` 两个谓词都要成立（基准为 XPath 1.0 的位置步语义）。 */
    private fun filterByPredicate(elements: List<Element>, predicates: List<String>): List<Element> {
        if (predicates.isEmpty()) return elements

        //谓词各解析一次成 AST, 再逐个元素复用 (旧实现每个元素重跑一遍全部正则)
        val exprs = predicates.map { parsePredicate(it) }
        return elements.filter { element -> exprs.all { it.eval(element) } }
    }


    /** 谓词切词。引号内不作任何切分, 引号内反斜杠无效 (与 [parsePredicates] 同口径)。 */
    private fun tokenizePredicate(src: String): List<PTok> {
        val toks = ArrayList<PTok>(8)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                c == '(' -> { toks.add(PTok.Open); i++ }
                c == ')' -> { toks.add(PTok.Close); i++ }
                c == ',' -> { toks.add(PTok.Comma); i++ }
                c == '=' -> { toks.add(PTok.Rel(RelOp.Eq)); i++ }
                c == '!' -> {
                    if (i + 1 >= src.length || src[i + 1] != '=') {
                        throw NoStackTraceException(unsupported(src))
                    }
                    toks.add(PTok.Rel(RelOp.Ne)); i += 2
                }

                c == '<' -> {
                    if (i + 1 < src.length && src[i + 1] == '=') {
                        toks.add(PTok.Rel(RelOp.Le)); i += 2
                    } else {
                        toks.add(PTok.Rel(RelOp.Lt)); i++
                    }
                }

                c == '>' -> {
                    if (i + 1 < src.length && src[i + 1] == '=') {
                        toks.add(PTok.Rel(RelOp.Ge)); i += 2
                    } else {
                        toks.add(PTok.Rel(RelOp.Gt)); i++
                    }
                }

                c == '@' -> {
                    var j = i + 1
                    //属性名可含 `:`/`.`/`-` (HTML `data-*`、XML 命名空间前缀都是合法名字)
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] in "_:.-")) j++
                    if (j == i + 1) throw NoStackTraceException(unsupported(src))
                    toks.add(PTok.Attr(src.substring(i + 1, j))); i = j
                }

                c == '\'' || c == '"' -> {
                    val close = src.indexOf(c, i + 1)
                    if (close < 0) throw NoStackTraceException("XPath 谓词引号未配对: [$src]")
                    toks.add(PTok.Str(src.substring(i + 1, close))); i = close + 1
                }

                c.isDigit() -> {
                    var j = i
                    var dotted = false
                    while (j < src.length) {
                        val d = src[j]
                        if (d.isDigit()) {
                            j++
                        } else if (d == '.' && !dotted) {
                            dotted = true; j++
                        } else {
                            break
                        }
                    }
                    val value = src.substring(i, j).toDoubleOrNull()
                        ?: throw NoStackTraceException(unsupported(src))
                    toks.add(PTok.Num(value)); i = j
                }

                c.isLetter() -> {
                    var j = i
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '-' || src[j] == '_')) j++
                    val word = src.substring(i, j)
                    var k = j
                    while (k < src.length && src[k] == ' ') k++
                    when {
                        //名字后紧跟 `(` 才是函数; 否则是节点测试 (`[a]` 这种) 或不认识的裸词, 一律抛错,
                        //不得像旧实现那样非锚定匹配后当命中
                        k < src.length && src[k] == '(' -> toks.add(PTok.Func(word.lowercase()))
                        word == "and" || word == "or" || word == "not" -> toks.add(PTok.Kw(word))
                        else -> throw NoStackTraceException(unsupported(src))
                    }
                    i = j
                }

                else -> throw NoStackTraceException(unsupported(src))
            }
        }
        return toks
    }

    private fun unsupported(predicate: String) = "XPath 谓词不支持: [$predicate]"

    /**
     * 谓词支持的形态 (基准 W3C XPath 1.0 §2.1 谓词步与 §3.1 运算符, 只取书源规则需要的子集):
     *
     * - 位置: `[3]`、`position()=3`、`position()>1`、`position()>=2 and position()<=4`、`last()`、
     *   `position()=last()`
     * - 属性: `@href` (存在)、`@href='x'`、`@href!='x'`、`@price<5` 与 `>`/`<=`/`>=`
     *   (两侧都能化成数时按数比, 否则与规范一致: 关系运算恒 false)
     * - 函数: `contains(@a,'x')`、`starts-with(@a,'x')`、`not(<表达式>)`、`true()`、`false()`
     * - 布尔组合与分组: `and`、`or` (`or` 优先级低于 `and`)、`( ... )`
     *
     * 形态对不上就抛 [NoStackTraceException]。旧实现末尾一句 `return true` 把坏规则伪装成
     * "全部命中" (archive `modules/jsoup-compat` 那份同名手写件同样如此), 那是原版缺陷, 不复刻。
     *
     * 仍未实现的合法形态 (同样抛错, 它们不是标准外写法, 只是本件这个子集没做): 子元素与文本节点
     * 测试、各类轴 —— `[a]`、`[a='x']`、`[text()='x']`、`[*]`、`[node()]`、`[../@id='x']`、`[@*]`,
     * 以及 `matches()`/`ends-with()`/`string()`/`substring()`/`string-length()`/`count()`/
     * `normalize-space()`/`local-name()`。
     * 语法不整 (残缺括号、多余 token、不认识的函数) 同样按不支持抛错, 不静默放行。
     */
    private fun parsePredicate(predicate: String): PredExpr {
        val toks = tokenizePredicate(predicate)
        if (toks.isEmpty()) throw NoStackTraceException("XPath 谓词为空")
        val parser = PredParser(toks, predicate)
        val expr = parser.parseExpression()
        if (!parser.atEnd()) throw NoStackTraceException(unsupported(predicate))
        return expr
    }

    /** 词法单元。 */
    private sealed class PTok {
        object Open : PTok()
        object Close : PTok()
        object Comma : PTok()
        class Rel(val op: RelOp) : PTok()
        class Kw(val word: String) : PTok()
        class Func(val name: String) : PTok()
        class Attr(val name: String) : PTok()
        class Num(val value: Double) : PTok()
        class Str(val value: String) : PTok()
    }

    private enum class RelOp { Eq, Ne, Lt, Le, Gt, Ge }

    /** 谓词表达式 AST 节点。 */
    private fun interface PredExpr {
        fun eval(element: Element): Boolean
    }

    /** 操作数在某个元素上的取值。 */
    private fun interface Operand {
        fun of(element: Element): PV
    }

    /** 数字字面量操作数: 只有它能让"纯数字谓词"按位置测试解释 (`[3]`)。 */
    private class NumberLiteral(val value: Double) : Operand {
        override fun of(element: Element): PV = PV.Num(value)
    }

    /** 属性操作数: 单用它当谓词时是"存在性测试" (`[@a]` 与值是否为空串无关)。 */
    private class AttrOperand(val name: String) : Operand {
        override fun of(element: Element): PV =
            if (element.hasAttr(name)) PV.Text(element.attr(name)) else PV.Missing
    }

    /**
     * 操作数取值。属性缺失用 [PV.Missing] 表示 (XPath 1.0 的空节点集): 参与相等/关系比较结果
     * 恒 false, 按布尔取假, 按字符串取空串。旧实现把"没有该属性"与"属性值为空串"混成一件事,
     * 于是 `[@a='']` 会把压根没有 a 属性的元素也算命中。
     */
    private sealed class PV {
        class Text(val v: String) : PV()
        class Num(val v: Double) : PV()
        class Flag(val v: Boolean) : PV()
        object Missing : PV()

        fun bool(): Boolean = when (this) {
            is Text -> v.isNotEmpty()
            is Num -> !v.isNaN() && v != 0.0
            is Flag -> v
            Missing -> false
        }

        /** 字符串取值 (空节点集按规范取空串; 整数值不带 `.0`)。 */
        fun text(): String = when (this) {
            is Text -> v
            is Num -> when {
                v.isNaN() -> "NaN"
                v.isInfinite() -> v.toString()
                v == kotlin.math.floor(v) -> v.toLong().toString()
                else -> v.toString()
            }
            is Flag -> v.toString()
            Missing -> ""
        }

        /** 数值取值; 非数字文本为 NaN (与规范 number() 一致)。 */
        fun num(): Double = when (this) {
            is Num -> v
            is Text -> v.trim().toDoubleOrNull() ?: Double.NaN
            is Flag -> if (v) 1.0 else 0.0
            Missing -> Double.NaN
        }
    }

    /** 递归下降解析器: `or` 优先级最低, 其次 `and`, 再一元/比较/分组。 */
    private inner class PredParser(private val toks: List<PTok>, private val src: String) {
        private var pos = 0

        fun atEnd() = pos >= toks.size

        fun parseExpression(): PredExpr = parseOr()

        private fun parseOr(): PredExpr {
            var left = parseAnd()
            while (true) {
                val t = toks.getOrNull(pos)
                if (t is PTok.Kw && t.word == "or") {
                    pos++
                    val right = parseAnd()
                    val prev = left
                    left = PredExpr { e -> prev.eval(e) || right.eval(e) }
                } else {
                    return left
                }
            }
        }

        private fun parseAnd(): PredExpr {
            var left = parseUnary()
            while (true) {
                val t = toks.getOrNull(pos)
                if (t is PTok.Kw && t.word == "and") {
                    pos++
                    val right = parseUnary()
                    val prev = left
                    left = PredExpr { e -> prev.eval(e) && right.eval(e) }
                } else {
                    return left
                }
            }
        }

        private fun parseUnary(): PredExpr {
            val t = toks.getOrNull(pos) ?: throw NoStackTraceException(unsupported(src))
            if (t is PTok.Kw && t.word == "not") {
                pos++
                expect(PTok.Open)
                val inner = parseExpression()
                expect(PTok.Close)
                return PredExpr { e -> !inner.eval(e) }
            }
            return parsePrimary()
        }

        private fun parsePrimary(): PredExpr {
            val operand = parseOperand()
            val opTok = toks.getOrNull(pos)
            if (opTok is PTok.Rel) {
                pos++
                val rhs = parseOperand()
                val op = opTok.op
                return PredExpr { e -> compare(op, operand.of(e), rhs.of(e)) }
            }
            return when (operand) {
                is NumberLiteral -> PredExpr { e -> positionEquals(e, operand.value) }
                //裸属性 = 存在性测试: 规范里属性节点集非空即为真, 与属性值是不是空串无关
                is AttrOperand -> PredExpr { e -> e.hasAttr(operand.name) }
                else -> PredExpr { e -> operand.of(e).bool() }
            }
        }

        private fun parseOperand(): Operand {
            val t = toks.getOrNull(pos) ?: throw NoStackTraceException(unsupported(src))
            return when (t) {
                is PTok.Attr -> {
                    pos++
                    AttrOperand(t.name)
                }

                is PTok.Num -> {
                    pos++
                    NumberLiteral(t.value)
                }

                is PTok.Str -> {
                    pos++
                    val value = t.value
                    Operand { PV.Text(value) }
                }

                is PTok.Func -> {
                    pos++
                    parseFunction(t.name)
                }

                PTok.Open -> {
                    pos++
                    val inner = parseExpression()
                    expect(PTok.Close)
                    Operand { e -> PV.Flag(inner.eval(e)) }
                }

                else -> throw NoStackTraceException(unsupported(src))
            }
        }

        /** 本件认得的函数只有这几个; 名字对不上直接抛错 (不静默当命中)。 */
        private fun parseFunction(name: String): Operand {
            expect(PTok.Open)
            return when (name) {
                "position", "last" -> {
                    expect(PTok.Close)
                    Operand { e ->
                        val (at, total) = siblingPositionAndTotal(e)
                        PV.Num(if (name == "position") at.toDouble() else total.toDouble())
                    }
                }

                "true", "false" -> {
                    expect(PTok.Close)
                    Operand { PV.Flag(name == "true") }
                }

                "contains", "starts-with" -> {
                    val a = parseOperand()
                    expect(PTok.Comma)
                    val b = parseOperand()
                    expect(PTok.Close)
                    Operand { e ->
                        val target = a.of(e).text()
                        val arg = b.of(e).text()
                        PV.Flag(if (name == "contains") target.contains(arg) else target.startsWith(arg))
                    }
                }

                else -> throw NoStackTraceException(unsupported(src))
            }
        }

        private fun expect(tok: PTok) {
            if (toks.getOrNull(pos) !== tok) throw NoStackTraceException(unsupported(src))
            pos++
        }
    }

    /** 相等/关系比较。空节点集参与比较恒 false (§3.1: 节点集与另一侧逐个比, 空集无对象)。 */
    private fun compare(op: RelOp, left: PV, right: PV): Boolean {
        if (left is PV.Missing || right is PV.Missing) return false
        return when (op) {
            RelOp.Eq -> valuesEqual(left, right)
            RelOp.Ne -> !valuesEqual(left, right)
            RelOp.Lt -> orderOf(left, right)?.let { it < 0 } == true
            RelOp.Le -> orderOf(left, right)?.let { it <= 0 } == true
            RelOp.Gt -> orderOf(left, right)?.let { it > 0 } == true
            RelOp.Ge -> orderOf(left, right)?.let { it >= 0 } == true
        }
    }

    private fun valuesEqual(left: PV, right: PV): Boolean = when {
        left is PV.Flag || right is PV.Flag -> left.bool() == right.bool()
        left is PV.Num || right is PV.Num -> left.num() == right.num()
        else -> left.text() == right.text()
    }

    /** 关系比较只在两侧都能化成数时有定义; NaN (非数字文本、空串) 一律无结果。 */
    private fun orderOf(left: PV, right: PV): Int? {
        val a = left.num()
        val b = right.num()
        if (a.isNaN() || b.isNaN()) return null
        return a.compareTo(b)
    }

    /** 纯数字谓词 `[n]`: 规范里是位置测试, 序号取同名兄弟的 1 基序号 (§2.1)。 */
    private fun positionEquals(element: Element, n: Double): Boolean =
        n >= 1 && n == kotlin.math.floor(n) && siblingPositionAndTotal(element).first.toDouble() == n

    /**
     * 元素在其父节点**同名**子元素序列中的 1 基序号，与同名子元素总数。
     *
     * 基准为 W3C XPath 1.0 的谓词位置语义（节点先经节点测试过滤、位置从 1 开始）：
     * archive 在 `modules/jsoup-compat` 自带一份同名的手写实现（`selectDescendants` 的 `[n]` 走 0 基
     * 且未按标签名过滤、`position()=n` 走 1 基全兄弟），三处互相矛盾。本件不拿“原版也这么错”当包袱，
     * 只按规范定对错，并让 `[n]`/`position()=n`/`last()` 共用本函数一个序号口径。
     */
    private fun siblingPositionAndTotal(element: Element): Pair<Int, Int> {
        //无父节点（文档根）时它就是候选集里唯一节点: 序号 1、总数 1, `[1]`/`last()` 都应命中
        val parent = element.parent() ?: return 1 to 1
        val tag = element.tagName()
        var position = 0
        var total = 0
        for (child in parent.children()) {
            if (child.tagName() == tag) {
                total++
                if (child === element) position = total
            }
        }
        //找不到自身序号时不做"当成第 1 个"的回退: 那会把错选伪装成命中
        return position to total
    }
}

sealed class PathPart {
    object Parent : PathPart()
    object Current : PathPart()
    object AllChildren : PathPart()
    data class Tag(val name: String) : PathPart()
    data class TagWithPredicate(val tag: String, val predicates: List<String>) : PathPart()
}

// Extension function for Element to support selectXpath
fun Element.selectXpath(xpath: String): Elements {
    return XPathEvaluator.evaluateElements(xpath, this)
}

// KClass 版（原 java.lang.Class 版泄漏 JVM 类型, commonMain 不可用）。
// type 仅用于筛节点种类, 现仅 AnalyzeByXPath 以 Element/Node 调用, 结果最终只保留 Element。
fun <T : Node> Element.selectXpath(xpath: String, type: KClass<T>): Elements {
    val result = XPathEvaluator.evaluate(xpath, this)
    val elements = Elements()
    result.forEach { node ->
        if (node is Element && type.isInstance(node)) {
            elements.add(node)
        }
    }
    return elements
}
