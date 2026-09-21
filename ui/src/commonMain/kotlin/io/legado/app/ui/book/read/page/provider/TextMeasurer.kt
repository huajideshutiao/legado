package io.legado.app.ui.book.read.page.provider

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * 排版测量面收口：把平台字形度量原语抽象成排版链路唯一的入口。
 * 实现：安卓 `AndroidTextMeasurer`（androidMain，TextPaint）、其余三端 `SkiaTextMeasurer`（skikoUiMain）。
 */
interface TextMeasurer {

    /**
     * 逐字素簇宽度写入 [widths]（口径 = TextPaint.getTextWidthsCompat，含 API35 letterSpacing 首末补偿）。
     * 热路径：[widths] 由调用方复用，实现不得新分配。
     */
    fun measureGlyphWidths(text: String, widths: FloatArray)

    /** 整串期望宽度（口径 = measureText + API35 letterSpacing*textSize 补偿）。 */
    fun measureWidth(text: String): Float

    /** letterSpacing*textSize（px），断行宽度余量用。 */
    val letterSpacingPx: Float

    /**
     * ICU（UAX#14）行断点：返回 [text] 上允许断行的 UTF-16 下标升序数组（含 0 与 `text.length`）。
     * 平台无 ICU 断点能力时返回 null，调用方退化为按字素簇累加宽度断行。
     *
     * 对应原版 `useZhLayout=false` 分支的 `StaticLayout`：AOSP 默认 `LineBreakConfig.NONE`
     * 不追加 `-u-lb-*` 关键字（`minikin/Locale.cpp:328`），落到 ICU 默认行分隔表
     * （`minikin/WordBreaker.cpp:52` 的 `ubrk_open(UBRK_LINE)`）。这里取同一张表，
     * 装行由 [LineBreaker] 在 commonMain 统一完成。
     *
     * 实现须复用同一个迭代器实例（每次新建约慢一倍），实例与度量器同生命周期、不跨线程共享。
     */
    fun lineBreakOpportunities(text: String): IntArray? = null

    /**
     * textSize（px）：消除 [android.text.TextPaint.textSize] 直接访问，统一从测量面取值。
     * 排版侧用它把 px 偏移折算回字号倒数单位（如回退量 / 字距补偿）。
     */
    val textSizePx: Float

    /** descent（px）：基线以下的下行度量。 */
    val descent: Float

    /**
     * ascent（px，基线以上为负）：配合 [descent] 还原真实字体高度
     * （`descent - ascent` = app 端 `paint.textHeight`，排版行盒高度与行距基准用）。
     */
    val ascent: Float

    /**
     * leading（px，字体建议的行间空白）：行盒高取 `descent - ascent + leading`，
     * 与 app 端 `TextPaint.textHeight`（`PaintExtensions.android.kt` 里就是这三项之和）
     * 同口径。漏算这一项会让行高比原版小一个 leading，四端每页行数与原版不一致。
     */
    val leading: Float
}

/**
 * 平台真实字形度量注册处：未注册时排版回退等宽近似 [SimpleTextMeasurer]。
 *
 * desktop / iOS / 鸿蒙 三端统一走 `registerSkiaTextMeasurer()` 注册 `SkiaTextMeasurer`。
 * 每次 [createOrNull] 都新建一个私有的平台度量器（`TextPaint` / skia `Font` 都不是线程安全的，
 * 并行排版必须一个 worker 一个实例），再套上 [CachedTextMeasurer] 共享同一张按参数索引的字宽表。
 */
object TextMeasurerProviders {

    @Volatile
    private var factory: TextMeasurerFactory? = null

    private val tablesLock = SynchronizedObject()

    /** 正文 + 标题 = 2 组参数，留 4 张够换一次字号 / 字体时新旧并存。 */
    private val tables = ArrayList<AdvanceTable>(TABLE_CACHE_SIZE)

    /** 宿主启动早期注册一次（任何章节排版之前）。 */
    fun register(factory: TextMeasurerFactory) = synchronized(tablesLock) {
        this.factory = factory
        // 新工厂可能产出不同字形，旧字宽表一起作废
        tables.clear()
    }

    /** 未注册返回 null，由调用方回退 [SimpleTextMeasurer]。[weight] 取 100..900，见 [ReaderFontWeights]。 */
    fun createOrNull(
        textSizePx: Float,
        letterSpacingPx: Float,
        fontPath: String,
        weight: Int,
    ): TextMeasurer? {
        val factory = this.factory ?: return null
        val delegate = factory(textSizePx, letterSpacingPx, fontPath, weight)
        val table = tableFor(textSizePx, letterSpacingPx, fontPath, weight, delegate)
        return CachedTextMeasurer(delegate, table)
    }

    /** 找 / 建这组参数的共享字宽表：全流程唯一的同步点，度量热路径一把锁都没有。 */
    private fun tableFor(
        textSizePx: Float,
        letterSpacingPx: Float,
        fontPath: String,
        weight: Int,
        delegate: TextMeasurer,
    ): AdvanceTable = synchronized(tablesLock) {
        tables.firstOrNull {
            it.keySizePx == textSizePx && it.keySpacingPx == letterSpacingPx &&
                    it.keyFontPath == fontPath && it.keyWeight == weight
        } ?: AdvanceTable(textSizePx, letterSpacingPx, fontPath, weight).also {
            // 标定判定不可缓存的表也留在册子里（内部 slots=null），免得每章重探一次
            it.calibrate(delegate)
            if (tables.size >= TABLE_CACHE_SIZE) tables.removeAt(0)
            tables.add(it)
        }
    }
}

/**
 * 度量器工厂：[fontPath] 为 `ReadBookConfig.textFont`（自定义字体文件绝对路径，空 = 默认字体），
 * [weight] 为 100..900 字重（见 [ReaderFontWeights]，标题与正文字重不同）。
 *
 * 实现必须与绘制侧 `loadReaderFontFamily` 读同一个文件、失败时回落同一个默认字体，
 * 且**字重的取舍也要与绘制侧一致**：自定义字体只注册了一个字形，绘制侧的 `FontWeight`
 * 对它不起作用，故度量侧此时必须忽略 [weight]；默认字体则按 [weight] 取面。
 * 否则「A 字重量度 / B 字形绘制」会让正文行尾溢出、字距错乱。
 */
typealias TextMeasurerFactory =
        (textSizePx: Float, letterSpacingPx: Float, fontPath: String, weight: Int) -> TextMeasurer

private const val TABLE_CACHE_SIZE = 4

/** [AdvanceTable.fill] 结果：全命中 / 有未测码位 / 必须直调平台。 */
private const val FILLED = 0
private const val NEED_SEED = 1
private const val NEED_DIRECT = 2

/** 字宽表哨兵：NaN = 未测，负 = 该码位 advance 依赖邻居，永不缓存。 */
private const val POISON = -1f

/**
 * 可缓存码位区段（含端点）：逐字独立成形、无连字 / 组合记号 / 双向控制的区段，按正文频率排序。
 * 拉丁与代理对刻意不在表内——安卓 Minikin 默认开 kern/liga，advance 依赖邻居。
 */
private val CACHEABLE_RANGES = intArrayOf(
    0x4E00, 0x9FFF, // CJK 统一汉字
    0x3000, 0x3029, // CJK 符号标点（0x302A..0x302F 组合声调符除外）
    0x3030, 0x3098, // 假名（0x3099..0x309A 组合浊音符除外）
    0x309B, 0x33FF, // 假名扩展 / 注音 / 兼容方块
    0x3400, 0x4DBF, // CJK 扩展 A
    0xF900, 0xFAFF, // CJK 兼容汉字
    0xFF01, 0xFF9D, // 全角形式 / 半角假名（0xFF9E..0xFF9F 半角浊音符除外）
    0xFFA0, 0xFFEF, // 半角谚文 / 全角符号
    0x2010, 0x2027, // 连接号 / 引号 / 省略号（0x2028 起分隔符与双向控制符除外）
    0x2030, 0x205E, // 千分号 / 角引号 / 参考标记
)

/** 各区段在字宽表中的起始下标（前缀和，避免手算基址）。 */
private val CACHEABLE_BASES = IntArray(CACHEABLE_RANGES.size / 2).also { bases ->
    var base = 0
    for (i in bases.indices) {
        bases[i] = base
        base += CACHEABLE_RANGES[i * 2 + 1] - CACHEABLE_RANGES[i * 2] + 1
    }
}

private val CACHEABLE_SIZE = CACHEABLE_BASES.last() +
        CACHEABLE_RANGES[CACHEABLE_RANGES.lastIndex] -
        CACHEABLE_RANGES[CACHEABLE_RANGES.lastIndex - 1] + 1

/** 码位 → 字宽表下标，白名单外返回 -1（汉字在首段，一次比较即出）。 */
private fun slotOf(code: Int): Int {
    var i = 0
    while (i < CACHEABLE_BASES.size) {
        val lo = CACHEABLE_RANGES[i * 2]
        if (code >= lo && code <= CACHEABLE_RANGES[i * 2 + 1]) {
            return CACHEABLE_BASES[i] + (code - lo)
        }
        i++
    }
    return -1
}

/** 标定探针：前 4 个汉字定首末补偿量，其余覆盖 CJK 标点 / 全角 / 通用标点 / 假名。 */
private const val PROBE_HAN = "的一我中"
private const val PROBE_MORE = "，。“”…—あ"
private const val FILLER = '的'

/**
 * 一组（字号 + 字距 + 字体）共享的码位 → advance 表：整串码位全在 [CACHEABLE_RANGES] 白名单内
 * 才查表，否则整串直调平台。
 *
 * 表里只存「非首非末、前后邻居也在白名单内、前后实测都为正宽」处实测出来的纯内部宽；首末补偿量
 * [edgeFirst] / [edgeLast] 由 [calibrate] 实测（安卓 API35 的 `getTextWidthsCompat` 给首末两个
 * 非零项各补半格字距，其余实现为 0），命中路径按同一规则补回，于是与直调逐元素相等。
 *
 * 全程无锁：同一码位在同一组参数下测出来永远同值，重复写幂等且单个 float 写不会撕裂；
 * 唯一同步点是建表（[TextMeasurerProviders.createOrNull] 里走一次）。
 */
private class AdvanceTable(
    val keySizePx: Float,
    val keySpacingPx: Float,
    val keyFontPath: String,
    val keyWeight: Int,
) {

    /** NaN 未测 / 正数为实测内部宽 / 负数 [POISON]；null = 未标定，或标定判定这组参数不可缓存。 */
    @Volatile
    private var slots: FloatArray? = null

    // 仅 calibrate() 内写，随 slots 的 volatile 发布一并可见
    private var edgeFirst = 0f
    private var edgeLast = 0f

    /** 逐码位查表填 [widths]，全命中才补首末补偿并返回 [FILLED]。 */
    fun fill(text: String, widths: FloatArray): Int {
        val table = slots ?: return NEED_DIRECT
        var missing = false
        var i = 0
        while (i < text.length) {
            val slot = slotOf(text[i].code)
            if (slot < 0) return NEED_DIRECT
            val w = table[slot]
            when {
                w > 0f -> widths[i] = w
                w < 0f -> return NEED_DIRECT
                else -> missing = true
            }
            i++
        }
        if (missing) return NEED_SEED
        // 单码位串首末同一位，与 getTextWidthsCompat 两个循环都命中 index 0 的行为一致
        widths[0] += edgeFirst
        widths[text.length - 1] += edgeLast
        return FILLED
    }

    /**
     * 把 [text] 里未测的码位去重拼成一条合成串（两端垫 [FILLER]，让每个待测码位都落在内部位），
     * 没有待测码位返回 null。度量与写表由调用方用自己那份平台度量器完成。
     */
    fun missingProbe(text: String): String? {
        val table = slots ?: return null
        val sb = StringBuilder().append(FILLER)
        for (c in text) {
            val slot = slotOf(c.code)
            if (slot >= 0 && table[slot].isNaN() && !sb.contains(c)) sb.append(c)
        }
        if (sb.length == 1) return null // 另一个 worker 已补齐
        return sb.append(FILLER).toString()
    }

    /**
     * 只从「非首非末、前后邻居也在白名单内、且前后实测都是正宽」的位置学宽：前后为正宽才能保证
     * 本位没吃到首末补偿，邻居在白名单内才能保证成形上下文可控。
     * 同一码位两次实测不等（或实测非正宽）= advance 依赖邻居，标记 [POISON]。
     */
    fun learn(text: String, widths: FloatArray) {
        val table = slots ?: return
        val n = text.length
        if (n < 3 || widths.size < n) return
        var prev = slotOf(text[0].code)
        var cur = slotOf(text[1].code)
        var i = 1
        while (i < n - 1) {
            val next = slotOf(text[i + 1].code)
            if (cur >= 0 && prev >= 0 && next >= 0) {
                val w = widths[i]
                val old = table[cur]
                when {
                    // 非正宽（零宽字 / 被并进前一簇）：永不缓存，也免得每段都白探一次
                    !(w > 0f) -> table[cur] = POISON
                    !(widths[i - 1] > 0f && widths[i + 1] > 0f) -> {}
                    old.isNaN() -> table[cur] = w
                    old > 0f && old != w -> table[cur] = POISON
                }
            }
            prev = cur
            cur = next
            i++
        }
    }

    /**
     * 建表时标定一次：实测首末补偿量，并两两验证探针 advance 与邻居无关。
     * 补偿量与字符有关（探针之间对不上）就整表不用（[probe] 给 null）；某个探针对不上则只把它标记为永不缓存。
     */
    fun calibrate(delegate: TextMeasurer) {
        slots = probe(delegate)
    }

    private fun probe(delegate: TextMeasurer): FloatArray? {
        val table = FloatArray(CACHEABLE_SIZE) { Float.NaN }
        val one = FloatArray(1)
        val two = FloatArray(2)
        val three = FloatArray(3)
        var first = Float.NaN
        var last = Float.NaN
        for (c in PROBE_HAN) {
            delegate.measureGlyphWidths("$c$c$c", three)
            val inner = three[1]
            if (!(inner > 0f)) return null
            val df = three[0] - inner
            val dl = three[2] - inner
            if (first.isNaN()) {
                first = df
                last = dl
            } else if (df != first || dl != last) {
                return null
            }
            delegate.measureGlyphWidths("$c$c", two)
            if (two[0] != inner + df || two[1] != inner + dl) return null
            delegate.measureGlyphWidths("$c", one)
            if (one[0] != inner + df + dl) return null
            table[slotOf(c.code)] = inner
        }
        for (c in PROBE_MORE) {
            delegate.measureGlyphWidths("$FILLER$c$FILLER", three)
            table[slotOf(c.code)] = if (three[1] > 0f) three[1] else POISON
        }
        val probes = PROBE_HAN + PROBE_MORE
        for (a in probes) {
            for (b in probes) {
                val wa = table[slotOf(a.code)]
                val wb = table[slotOf(b.code)]
                if (wa < 0f || wb < 0f) continue
                // 成对实测（含叠字，"——" / "……" 这种连写就靠它兜住）：只标记对不上的那一位
                delegate.measureGlyphWidths("$a$b", two)
                if (two[0] != wa + first) table[slotOf(a.code)] = POISON
                if (two[1] != wb + last) table[slotOf(b.code)] = POISON
            }
        }
        edgeFirst = first
        edgeLast = last
        return table
    }
}

/**
 * codepoint → advance 缓存装饰器：整串码位都在表里就直接填数组，不再进平台度量
 * （安卓 JNI→Minikin / skiko getWidths）；有未测码位先用一条合成串把它们一次补齐。
 *
 * 一个实例配一份私有的 [delegate]（并行排版一个 worker 一个实例，平台度量器不被并发调用），
 * [table] 则按参数共享且无锁——度量路径没有任何锁。
 */
private class CachedTextMeasurer(
    private val delegate: TextMeasurer,
    private val table: AdvanceTable,
) : TextMeasurer {

    // 度量器一生绑定一份不可变字形参数，取一次即可（安卓 fontMetrics 每次访问都是 JNI + 新对象）
    override val textSizePx = delegate.textSizePx
    override val letterSpacingPx = delegate.letterSpacingPx
    override val descent = delegate.descent
    override val ascent = delegate.ascent
    override val leading = delegate.leading

    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        // 数组装不下整串时交回原实现（越界与否由它自己决定），命中路径只写 [0, text.length)
        if (text.isNotEmpty() && widths.size >= text.length) {
            when (table.fill(text, widths)) {
                FILLED -> return
                NEED_SEED -> {
                    seed(text)
                    if (table.fill(text, widths) == FILLED) return
                }
            }
        }
        delegate.measureGlyphWidths(text, widths)
        // 这一次度量本来就要花，顺手从真实上下文学表是白捡的
        table.learn(text, widths)
    }

    /** 不缓存：各实现的整串口径与「逐字求和」并不相等（全零宽串就是反例）。 */
    override fun measureWidth(text: String): Float = delegate.measureWidth(text)

    /** 断点由平台 ICU 给出，装饰器只透传（漏转发会让四端全部退化成按簇累加断行）。 */
    override fun lineBreakOpportunities(text: String): IntArray? =
        delegate.lineBreakOpportunities(text)

    /** 用一条合成串把 [text] 里未测的码位一次补齐；没有待测码位 = 别的 worker 已经补过了。 */
    private fun seed(text: String) {
        val probe = table.missingProbe(text) ?: return
        val out = FloatArray(probe.length)
        delegate.measureGlyphWidths(probe, out)
        table.learn(probe, out)
    }
}
