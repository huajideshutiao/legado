package io.legado.app.utils.scan

/**
 * 成对符号平衡扫描共用件。
 *
 * 全项目原先有 4 份各自手写的「游标 + depth + 引号态」平衡组拉取实现
 * （`RuleAnalyzer.chompCodeBalanced` / `chompRuleBalanced`、`ExploreOption.tryParseSegment`、
 * `org.jsoup.select.XPathEvaluator.parsePredicate`），彼此在符号集合、引号是否参与、
 * 转义生效范围三处语义并不相同。本件把差异显式收敛成 [chomp] 的参数，
 * 扫描主体只保留一份，逐位对齐替换前的行为。
 *
 * 参数用散开的原始类型而非 spec 对象是刻意的：`RuleAnalyzer` 每遇到一个筛选器就要 `chomp`
 * 一次，属于书源规则热路径，不为一次扫描分配临时对象。
 *
 * 失败一律返回 -1 且**不改动**调用方游标（对齐原实现「未平衡则位置不回写」）；
 * 未平衡时是抛错、静默跳一段还是丢整段，由调用方决定，不在此件里体现。
 */
internal object BalanceScan {

    /** 转义字符。原 RuleAnalyzer 的 ESC 常量下沉到此，供两种模式共用。 */
    private const val ESC = '\\'

    /** [chomp] 的 primary 参数取此值表示不另设主对。 */
    const val NO_PRIMARY: Char = 0.toChar()

    /** [splitQuoted] 的引号哨兵：正常文本不会出现 0 号字符。 */
    private const val NO_QUOTE: Char = 0.toChar()

    /** 转义生效范围。三态不可合并为布尔：原始三处实现恰好各占一种。 */
    internal enum class Escape {
        /** 完全不认反斜杠（`ExploreOption` 的 `<name(...)>` content 段） */
        NEVER,

        /** 仅引号外认反斜杠（xpath/jsoup 规则：实测引号内转义无效） */
        OUTSIDE_QUOTES,

        /** 引号内外都认反斜杠（内嵌 JS 代码：`'a\''` 里的引号不算闭合） */
        ALWAYS,
    }

    /**
     * 从 [start]（必须正指向 [open]）拉出一个平衡组。
     *
     * @param open [close] 待拉出组的符号对，调用方保证 `text[start] == open`
     * @param primaryOpen primaryClose 恒计层的主符号对；给定时 [open]/[close] 只在主对归零后才计层。
     *   内嵌 JS 代码需要 `[]` 恒计层（`a[1]` 的方括号不能打断 `(...)` 组），故传 `'['`；
     *   纯括号切分留 [NO_PRIMARY]。主对与传入对是同一对时不重复计层。
     * @param quote 是否跟踪单/双引号，引号内的符号一律不计层。
     * @param escape 反斜杠的生效范围，见 [Escape]。它与 [quote] 是两个独立轴：
     *   `quote = false` 时 `OUTSIDE_QUOTES` 与 `ALWAYS` 等价，只有 `NEVER` 才是真不认转义。
     * @return 组结束符的**后一位**下标；扫到串尾仍未平衡、或 [start] 已在串尾（空区间）则返回 -1。
     */
    fun chomp(
        text: String,
        start: Int,
        open: Char,
        close: Char,
        primaryOpen: Char = NO_PRIMARY,
        primaryClose: Char = NO_PRIMARY,
        quote: Boolean = true,
        escape: Escape = Escape.NEVER,
    ): Int {
        //start 已在串尾时下面循环会一次不进、直接返回 start，与"未平衡返回 -1"的约定相反，
        //故单独挡掉（当前调用方均先校验 text[start] == open，此路不可达）
        if (start >= text.length) return -1
        val pOpen = if (primaryOpen == NO_PRIMARY) open else primaryOpen
        val pClose = if (primaryClose == NO_PRIMARY) close else primaryClose
        val secondaryEnabled = primaryOpen != NO_PRIMARY && primaryOpen != open

        var pos = start //声明临时变量记录匹配位置，匹配成功后才由调用方同步
        var primary = 0 //主对嵌套深度
        var secondary = 0 //传入对嵌套深度，仅主对归零时累加

        var inSingleQuote = false //单引号
        var inDoubleQuote = false //双引号

        do {
            //越界判断用 >= 而非 ==：转义跳过下一个字符时可能一步顶出串尾，
            //原实现写的是 == length，此时会在下一轮 queue[pos++] 抛越界（规则以 `\` 结尾且未闭合）。
            //那是崩溃路径上的既有缺陷，此处按“未平衡”返回，不再越界。
            if (pos >= text.length) break
            val c = text[pos++]

            if (c == ESC && escape == Escape.ALWAYS) { //代码模式：转义先于引号判定，引号内同样生效
                pos++
                continue
            }

            if (quote) {
                if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote //匹配具有语法功能的单引号
                else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote //匹配具有语法功能的双引号
                if (inSingleQuote || inDoubleQuote) continue //语法单元未匹配结束，直接进入下个循环
            }

            if (c == ESC && escape == Escape.OUTSIDE_QUOTES) { //规则模式：不在引号中的转义字符才将下个字符转义
                pos++
                continue
            }

            if (c == pOpen) primary++ //开始嵌套一层
            else if (c == pClose) primary-- //闭合一层嵌套
            else if (secondaryEnabled && primary == 0) {
                //处于默认嵌套中的非默认字符不需要平衡，仅主对全部闭合时此字符才进行嵌套
                if (c == open) secondary++
                else if (c == close) secondary--
            }

        } while (primary > 0 || secondary > 0) //拉出一个平衡字串

        return if (primary > 0 || secondary > 0) -1 else pos
    }

    /**
     * 在 `[from, until)` 内找第一个不在 [open]/[close] 嵌套中的 [separator]。
     *
     * 计层允许走负（多余的结束符把深度压到 0 以下后，此后分隔符一律不算命中），
     * 与替换前 `ExploreOption` 的行为一致，不做纠正。
     *
     * @return 分隔符下标，未找到返回 -1
     */
    fun indexOfTopLevel(
        text: String,
        from: Int,
        until: Int,
        separator: Char,
        open: Char = '(',
        close: Char = ')',
    ): Int {
        var depth = 0
        var i = from
        while (i < until) {
            when (text[i]) {
                open -> depth++
                close -> depth--
                separator -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /**
     * 按第一个不在 [open]/[close] 嵌套中的 [separator] 切分 `[from, until)`，逐段回调半开区间。
     *
     * 每段重新起算深度与原单遍累加等价：分隔符只可能出现在深度 0，故段起点深度必为 0。
     */
    inline fun splitTopLevel(
        text: String,
        from: Int,
        until: Int,
        separator: Char,
        open: Char = '(',
        close: Char = ')',
        onSegment: (start: Int, endExclusive: Int) -> Unit,
    ) {
        var tokenStart = from
        while (true) {
            val at = indexOfTopLevel(text, tokenStart, until, separator, open, close)
            if (at < 0) break
            onSegment(tokenStart, at)
            tokenStart = at + 1
        }
        onSegment(tokenStart, until)
    }

    /**
     * 按分隔符 [separator] 切分，**引号内**与 [open]/[close] 嵌套内的分隔符一律不算分隔符。
     *
     * [pair] 决定命中的是“连续两个”还是“单个”分隔符，两趟配合即可区分 XPath 的 `//`（后代步）
     * 与 `/`（子步）：`pair = true` 只在成对处切开并吞掉第二个字符，`pair = false` 只在单个处切开。
     *
     * 不与 [splitTopLevel] 合并：那件不认引号、且允许深度走负（对齐 `ExploreOption` 替换前的行为，
     * 已锁在 `ExploreOptionUrlScanTest`），本件服务 XPath 路径，两者口径不同、各有调用方。
     */
    fun splitQuoted(
        text: String,
        separator: Char,
        pair: Boolean,
        open: Char = '[',
        close: Char = ']',
    ): List<String> {
        val out = ArrayList<String>(4)
        var depth = 0
        var quote = NO_QUOTE
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != NO_QUOTE) {
                if (c == quote) quote = NO_QUOTE //引号闭合，引号内字符不参与切分与计层
            } else when (c) {
                '\'', '"' -> quote = c
                open -> depth++
                close -> if (depth > 0) depth--
                separator -> if (depth == 0) {
                    val paired = i + 1 < text.length && text[i + 1] == separator
                    if (paired == pair) {
                        out.add(text.substring(start, i))
                        if (paired) i++ //成对分隔符吞掉第二个，避免 `a//b` 被当成三个 token
                        start = i + 1
                    }
                }
            }
            i++
        }
        out.add(text.substring(start))
        return out
    }
}
