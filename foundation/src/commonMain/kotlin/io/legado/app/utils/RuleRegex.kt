package io.legado.app.utils

import io.legado.app.help.casUpdate
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic

/**
 * 规则正则的执行能力 (书源规则 / 替换规则 / TXT 目录规则 / 筛选规则的 `##正则##`、`:regex`、
 * 正文去重标题、JSONPath 的 `match()`)。
 *
 * # 为什么必须做成平台能力
 *
 * 这类正则的**模式串来自书源与用户**, 且在解析链最内层执行, 各端引擎的失败模式不同:
 *
 * - JVM/Android: `java.util.regex` 在分组量词迭代长度固定时走循环而非递归 (JDK `GroupCurly.match0`),
 *   且编译被 `catch (StackOverflowError)` 包住转成正常的语法错误; 超时后 Android 经
 *   [RegexErrorHandler] 重启应用止损。
 * - native (iOS/鸿蒙): `kotlin.text.Regex` 是 Harmony 派生实现 —— 编译是**递归下降**解析
 *   (按模式嵌套深度递归, 无栈保护), 匹配时 `GroupQuantifierSet.matches` 每消费一次分组体就
 *   **递归一层** (`innerSet.next = this`)。复杂规则实测递归 2969 帧后写穿线程栈保护页直接闪退
 *   (崩点距栈顶 95 字节, 系统报 "Could not determine thread index for stack guard region");
 *   且该实现**没有任何中断检查点**, 不响应协程取消, 超时后线程会持续占满一核直到进程重启。
 *
 * 故本接口接收**模式串**而非已编译的 [Regex]: 调用方一律不得自行 `Regex(pattern)`, 编译与匹配
 * 都交给平台实现 (iOS 走系统 ICU 引擎, 编译与匹配都不递归)。
 *
 * # 失败口径 (统一契约)
 *
 * - **模式串无法编译**: [replace]/[find]/[findAll]/[matchesEntire] 向上抛异常 (与原写法
 *   `Regex(pattern)` 抛异常一致)。不得静默当“无匹配/不匹配”: 那会让“规则写错”与“页面无该字段”
 *   不可区分。需要容忍的调用方自己捕获。
 * - **超时**: 抛 [io.legado.app.exception.RegexTimeoutException]; 同样由调用方决定是否降级。
 * - **只有 [validate] 不抛异常**, 它把编译错误文本作为返回值。
 *
 * # 默认实现
 *
 * [DefaultRuleRegex] 即 `kotlin.text.Regex` 的直接调用, 不提供时间上限与栈保护。
 *
 * 模式参考 [RegexReplacers] / [RegexErrorHandlers]。
 */
interface RuleRegex {

    /**
     * 正则替换 (对应 `##匹配##替换` 的替换段)。
     *
     * @param source      待替换文本
     * @param pattern     模式串 (未经编译)
     * @param multiline   多行语义 (对应 `Pattern.MULTILINE`)
     * @param replacement 替换串 (`$1`/`${name}` 组引用由实现解析)
     * @param replaceFirst 只替换首个匹配 (对应 `####替换###` 形式)
     * @param timeout     单次替换的时间上限 (毫秒); <=0 表示不限时。
     *                    只有自带超时能力的实现会用到它, 其余实现忽略该参数。
     */
    fun replace(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        replacement: String,
        replaceFirst: Boolean,
        timeout: Long
    ): String

    /**
     * 查找首个匹配。
     *
     * @return 匹配区间与各分组区间; 无匹配返回 null
     */
    fun find(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): RuleMatchSpans?

    /**
     * 查找全部匹配 (对应 `:regex` / `##` 分组提取)。
     */
    fun findAll(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): List<RuleMatchSpans>

    /**
     * 校验模式串能否编译 (对应目录规则编辑保存前的语法校验)。
     *
     * @return null 表示可编译; 非 null 为可直接展示给用户的错误说明
     */
    fun validate(pattern: String, multiline: Boolean): String?

    /**
     * 整串匹配 (对应 `String.matches(Regex)` / `Matcher.matches()`)。
     *
     * 不能由 [find] 的结果推导: [find] 返回的是**最左首个**匹配, 而整串语义允许回溯到更靠后的
     * 候选分支。模式串含多个候选分支且短分支在前时 (如 `a|ab` 对输入 `ab`), 最左匹配是 `a`
     * (不覆盖整串), 但整串匹配成立 —— 用 [find] 判整串会把它误判为不匹配。
     *
     * 实现须在引擎内表达整串约束 (如首尾锚点), 不得靠拼接模式串后由调用方比较区间。
     */
    fun matchesEntire(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): Boolean
}

/**
 * 一个匹配的整体区间与各分组区间 (下标为 UTF-16 code unit, 与 [String.substring] 一致)。
 *
 * 用区间而非子串: 各平台引擎的字符串类型不同 (Kotlin [String] / NSString), 统一回落到
 * 区间后由调用方在 [String] 上取子串, 避免每层都做一次字符串拷贝。
 */
class RuleMatchSpans(
    /** 整体匹配区间 (对应 group 0)。 */
    val range: IntRange,
    /** 各分组区间, 下标 0 即整体匹配; 未参与匹配的分组为 null。 */
    val groupRanges: List<IntRange?>
) {
    /** 取分组文本; 未参与匹配返回 null (对齐 `MatchResult.groups[i]?.value`)。 */
    fun groupValue(source: CharSequence, index: Int): String? {
        val r = groupRanges.getOrNull(index) ?: return null
        return source.substring(r.first, r.last + 1)
    }

    /**
     * 取分组文本, 未参与匹配抛异常 (对齐 `Matcher.group(n)!!` / `MatchResult.groups[i]!!`)。
     */
    fun groupValueOrThrow(source: CharSequence, index: Int): String =
        groupValue(source, index)
            ?: throw IndexOutOfBoundsException("No group $index in match result")

    /** 整体匹配文本 (对齐 `MatchResult.value`)。 */
    fun value(source: CharSequence): String = source.substring(range.first, range.last + 1)
}

/**
 * [RuleRegex] provider 容器。宿主启动早期注册一次 (任何规则解析之前)。
 *
 * 未注册时 [get] 返回 [DefaultRuleRegex] (安卓/桌面/鸿蒙三端均如此), 即 `kotlin.text.Regex` 直通。
 */
object RuleRegexes {

    @Volatile
    private var impl: RuleRegex? = null

    /** 注册平台实现; 传 null 恢复默认实现。 */
    fun register(impl: RuleRegex?) {
        this.impl = impl
    }

    fun get(): RuleRegex = impl ?: DefaultRuleRegex
}

/**
 * 默认实现: 直接用 `kotlin.text.Regex`, **不提供时间上限与栈保护**。
 *
 * 安卓与鸿蒙注册本实现, 桌面注册 [io.legado.app.utils.JvmRuleRegex] (该实现多补一个字符类
 * 标志), 未注册时也回退到这里。
 *
 * 两端的引擎不是同一回事, 失败模式也不同: 安卓的 `java.util.regex` 由 ICU 实现 (分组量词
 * 迭代走循环而非递归), 不会写穿线程栈; 鸿蒙走 K/N stdlib 的 Harmony 派生实现, 匹配按迭代
 * 次数递归 —— 本实现不提供栈保护, 该端仍有此风险 (见接口注释的 native 一节)。
 *
 * 均不响应超时入参: JVM/Android 的超时由替换路径上的 [RegexReplacers] 另行提供,
 * 其余调用点只依赖模式串编译缓存。iOS 走 [io.legado.app.utils.RuleRegexes] 注册的系统引擎实现。
 *
 * 编译结果带缓存: 调用方传的是模式串, 而阅读热路径上每章×每规则都会调用本实现,
 * 不缓存则每次重新编译 (原 `ReplaceRule.regex` 是挂实例的懒编译)。
 */
object DefaultRuleRegex : RuleRegex {

    override fun replace(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        replacement: String,
        replaceFirst: Boolean,
        timeout: Long
    ): String {
        val regex = compileOrThrow(pattern, multiline)
        val text = source.toString()
        return if (replaceFirst) {
            val match = regex.find(text) ?: return ""
            match.value.replaceFirst(regex, replacement)
        } else {
            text.replace(regex, replacement)
        }
    }

    override fun find(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): RuleMatchSpans? {
        val regex = compileOrThrow(pattern, multiline)
        return regex.find(source.toString())?.toRuleMatchSpans()
    }

    override fun findAll(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): List<RuleMatchSpans> {
        val regex = compileOrThrow(pattern, multiline)
        return regex.findAll(source.toString()).map { it.toRuleMatchSpans() }.toList()
    }

    override fun validate(pattern: String, multiline: Boolean): String? = try {
        compileOrThrow(pattern, multiline)
        null
    } catch (e: Exception) {
        e.message
    }

    /**
     * 整串匹配: 用首尾锚点把约束交给引擎, 而非比较 [find] 结果的区间。
     *
     * 首尾锚点 (`\A` / `\z`) 在 native 端确认可用: 前者在起始位置不等于 0 时返回失败, 后者在
     * 起始位置小于串长时返回失败。不能写成 `^(?:...)$`: native 端未开启多行时 `^`/`$` 的语义
     * 受行终止符影响, 且 `$` 允许匹配到末尾换行之前 (JDK 同), 都不是整串语义。
     */
    override fun matchesEntire(
        source: CharSequence,
        pattern: String,
        multiline: Boolean,
        timeout: Long
    ): Boolean {
        val regex = compileOrThrow("\\A(?:$pattern)\\z", multiline)
        return regex.find(source.toString()) != null
    }

    /**
     * 编译并返回正则; 编译失败抛异常, 由调用方按自己的容忍度处理。
     *
     * 编译结果带缓存: 调用方传的是模式串, 而阅读热路径上每章×每规则都会调用本实现,
     * 不缓存则每次重新编译 (原 `ReplaceRule.regex` 是挂实例的懒编译)。
     */
    private fun compileOrThrow(pattern: String, multiline: Boolean): Regex {
        val key = (if (multiline) 1 else 0) to pattern
        cacheRef.value[key]?.let { return it }
        val regex = if (multiline) Regex(pattern, RegexOption.MULTILINE) else Regex(pattern)
        cacheRef.casUpdate { cur ->
            // 淘汰按插入序取最早的键 (先进先出): 已有键被覆盖不改变其位置,
            // 故 keys.first() 就是最早插入且未再被写过的那个
            if (!cur.containsKey(key) && cur.size >= CACHE_LIMIT) {
                val oldest = cur.keys.firstOrNull()
                (if (oldest == null) cur else cur - oldest) + (key to regex)
            } else {
                cur + (key to regex)
            }
        }
        return regex
    }

    /** key → 编译结果; 不可变快照, 写时整体替换。 */
    private val cacheRef = atomic<Map<Pair<Int, String>, Regex>>(emptyMap())

    private const val CACHE_LIMIT = 32
}

/** `MatchResult` → [RuleMatchSpans] (区间口径: `range` 直接可用, 分组取 `groups[i]?.range`)。 */
internal fun MatchResult.toRuleMatchSpans(): RuleMatchSpans {
    val groupRanges = ArrayList<IntRange?>(groups.size)
    for (i in 0 until groups.size) {
        groupRanges.add(groups[i]?.range)
    }
    return RuleMatchSpans(range, groupRanges)
}
