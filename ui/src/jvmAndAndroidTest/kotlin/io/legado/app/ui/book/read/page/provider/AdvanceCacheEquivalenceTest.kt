package io.legado.app.ui.book.read.page.provider

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 平台度量器 fake：复刻 `getTextWidthsCompat` 的「首末各补半格字距」口径，并统计平台调用次数。
 *
 * - [ligatureChars] 里的字符与前一个相同字符连写时收窄 ⇒ advance 依赖邻居，
 *   正确的字宽表必须把它标成永不缓存（`POISON`），否则 `——` / `……` 会宽一格。
 * - [edgeQuirkChars] 让首末补偿量与字符有关 ⇒ 标定必须整表禁用、全部退回直调。
 */
internal class EdgeCompensatedMeasurer(
    override val textSizePx: Float = 40f,
    override val letterSpacingPx: Float = 4f,
    override val descent: Float = 8f,
    override val ascent: Float = -32f,
    override val leading: Float = 0f,
    private val ligatureChars: Set<Char> = setOf('—', '…'),
    private val edgeQuirkChars: Set<Char> = emptySet(),
) : TextMeasurer {

    private val calls = AtomicInteger()

    /** 平台度量调用次数（真机上就是 JNI→Minikin / skiko getWidths 的次数）。 */
    val glyphCalls: Int get() = calls.get()

    fun resetCalls() = calls.set(0)

    override fun measureGlyphWidths(text: String, widths: FloatArray) {
        calls.incrementAndGet()
        for (i in text.indices) widths[i] = advanceOf(text, i)
        if (text.isEmpty()) return
        // 与 getTextWidthsCompat 同构：首末各补一次，单字串两次都落在下标 0
        widths[0] += edgeOf(text[0])
        widths[text.length - 1] += edgeOf(text[text.length - 1])
    }

    override fun measureWidth(text: String): Float {
        var sum = 0f
        for (i in text.indices) sum += advanceOf(text, i)
        if (text.isNotEmpty()) sum += edgeOf(text[0]) + edgeOf(text[text.length - 1])
        return sum
    }

    private fun edgeOf(c: Char): Float =
        if (c in edgeQuirkChars) letterSpacingPx * 1.5f else letterSpacingPx / 2f

    private fun advanceOf(text: String, i: Int): Float {
        val c = text[i]
        return when {
            c.code == 0x200B || c.code == 0x200C || c.code == 0x200D || c.code == 0xFEFF -> 0f
            c.isLowSurrogate() -> 0f
            c in COMBINING -> 0f
            c.isHighSurrogate() -> textSizePx
            c.code < 128 -> textSizePx / 2f
            c in ligatureChars && i > 0 && text[i - 1] == c -> textSizePx * 0.75f
            else -> textSizePx
        }
    }

    private companion object {
        val COMBINING = setOf(Char(0x0301), Char(0x0308), Char(0x3099), Char(0x309A))
    }
}

/**
 * codepoint→advance 缓存（`AdvanceTable` + `CachedTextMeasurer`）等价性金样。
 *
 * 唯一红线：命中缓存路径写出的 `FloatArray` 必须与直调平台**逐元素精确相等**（不许容差）——
 * 宽度漂一个像素断行位置就变，整章页数跟着变。
 *
 * `AdvanceTable` / `CachedTextMeasurer` / `edgeFirst` / `POISON` 全是文件私有，测试只能走
 * [TextMeasurerProviders.register] + [TextMeasurerProviders.createOrNull] 这个唯一公开缝：
 * 命中率用「装饰器调 delegate 的次数」间接数，POISON 用「预热两遍后仍要平台度量」间接判。
 *
 * 注意 [TextMeasurerProviders] 没有反注册 API，本类注册的工厂会留到同 JVM 的后续测试。
 */
class AdvanceCacheEquivalenceTest {

    private val sizePx = 40f
    private val spacingPx = 4f
    private val fontPath = "advance-cache-test"

    private val delegates = CopyOnWriteArrayList<EdgeCompensatedMeasurer>()

    /** 各种字形形状：白名单内 / 白名单外 / 连写 / 零宽 / 组合记号 / 代理对 / 单字 / 空串。 */
    private val samples = listOf(
        "纯汉字段落没有任何标点也没有西文",
        "含全角标点，例如。以及“引号”都要一致",
        "破折号——与省略号……连写",
        "混入 ASCII 数字 2026 与字母 abc 的段落",
        "带表情😀与代理对🙂的段落",
        "组合记号 e" + Char(0x0301) + " 与 a" + Char(0x0308) + " 混排",
        "假名浊音符 か" + Char(0x3099) + " 不在白名单",
        "零宽空格" + Char(0x200B) + "分隔的两段",
        "全角空格　与半角 空格",
        "单",
        "",
    )

    @Before
    fun install() {
        delegates.clear()
        TextMeasurerProviders.register { size, spacing, _, _ ->
            EdgeCompensatedMeasurer(size, spacing).also { delegates += it }
        }
    }

    private fun newBare(edgeQuirk: Set<Char> = emptySet()) =
        EdgeCompensatedMeasurer(sizePx, spacingPx, edgeQuirkChars = edgeQuirk)

    /** 拿一个装饰器 + 它私有的 delegate（createOrNull 每次恰好造一个 delegate）。 */
    private fun newCached(): Pair<TextMeasurer, EdgeCompensatedMeasurer> {
        val before = delegates.size
        val cached = TextMeasurerProviders.createOrNull(sizePx, spacingPx, fontPath, 400)
            ?: error("工厂已注册，createOrNull 不该返回 null")
        return cached to delegates[before]
    }

    /** 逐元素精确比对（用 List 相等，Float.equals 是位级比较，比 == 更严）。 */
    private fun assertSameWidths(label: String, text: String, cached: TextMeasurer, bare: TextMeasurer) {
        val a = FloatArray(text.length)
        val b = FloatArray(text.length)
        cached.measureGlyphWidths(text, a)
        bare.measureGlyphWidths(text, b)
        assertEquals("$label 紧凑数组: [$text]", b.toList(), a.toList())

        // 调用方复用数组池：数组比文本长，尾部哨兵不许被写
        val pooledA = FloatArray(text.length + 8) { -7f }
        val pooledB = FloatArray(text.length + 8) { -7f }
        cached.measureGlyphWidths(text, pooledA)
        bare.measureGlyphWidths(text, pooledB)
        assertEquals("$label 池化数组: [$text]", pooledB.toList(), pooledA.toList())
    }

    private fun newCachedOnly(): TextMeasurer =
        TextMeasurerProviders.createOrNull(sizePx, spacingPx, fontPath, 400)
            ?: error("工厂已注册，createOrNull 不该返回 null")

    /** 齐夫式偏斜的纯汉字语料：头部字符高频复现，尾部生僻字偶现，贴近真实章节。 */
    private fun corpus(seed: Int, paragraphs: Int, chars: Int, poolSize: Int = 300): List<String> {
        val rnd = Random(seed)
        return List(paragraphs) {
            val sb = StringBuilder(chars)
            repeat(chars) {
                val u = rnd.nextDouble()
                val idx = (poolSize * u * u * u * u).toInt().coerceAtMost(poolSize - 1)
                sb.append(Char(0x4E00 + idx))
            }
            sb.toString()
        }
    }

    private fun measureAll(m: TextMeasurer, texts: List<String>) {
        var buf = FloatArray(0)
        for (t in texts) {
            if (buf.size < t.length) buf = FloatArray(t.length + 32)
            m.measureGlyphWidths(t, buf)
        }
    }

    /** 预热后仍要平台度量的码位 = POISON 或不在白名单；连跑两遍才能把「只是没测过」摘出去。 */
    private fun poisonProbe(
        cached: TextMeasurer,
        delegate: EdgeCompensatedMeasurer,
        chars: Collection<Char>,
    ): List<Char> {
        val buf = FloatArray(3)
        val out = ArrayList<Char>()
        for (c in chars) {
            val before = delegate.glyphCalls
            cached.measureGlyphWidths("的" + c + "的", buf)
            if (delegate.glyphCalls != before) out += c
        }
        return out
    }

    /**
     * 1. 逐元素等价对拍：同一批文本，装饰器与裸工厂产物写出的数组必须位级相等。
     * 两轮跑遍所有形状——第一轮表冷走「合成串补齐后再填」，第二轮表热走纯命中路径。
     */
    @Test
    fun `缓存路径与直调平台逐元素精确相等`() {
        val (cached, _) = newCached()
        val bare = newBare()

        repeat(2) { round ->
            for (text in samples) assertSameWidths("第${round + 1}轮", text, cached, bare)
        }
        // 换一个装饰器实例（共享同一张表）再验：表跨实例共享不许带来偏差
        val (another, _) = newCached()
        for (text in samples) assertSameWidths("换实例", text, another, bare)
    }

    /** 1'. 大批随机语料等价对拍：覆盖表从空填到满的整个过程。 */
    @Test
    fun `随机语料逐元素精确相等`() {
        val (cached, _) = newCached()
        val bare = newBare()
        for (text in corpus(seed = 7, paragraphs = 60, chars = 80)) {
            assertSameWidths("语料", text, cached, bare)
        }
    }

    /**
     * 2. 命中率与冷启动：统计「平台度量调用次数 / 段落数」。
     * 硬断言只有两条——每段最多一次合成串度量、热表重测同一批文本零平台调用；
     * 章间比值打印出来，第 2 章若仍接近 1.0 就顺手把不可缓存码位数打出来。
     */
    @Test
    fun `命中率与冷启动统计`() {
        val ch1 = corpus(seed = 11, paragraphs = 80, chars = 60)
        val ch2 = corpus(seed = 12, paragraphs = 80, chars = 60)
        val (cached, delegate) = newCached()

        delegate.resetCalls() // 甩掉建表标定那几次
        measureAll(cached, ch1.take(10))
        val cold10 = delegate.glyphCalls
        delegate.resetCalls()
        measureAll(cached, ch1.drop(10))
        val c1 = cold10 + delegate.glyphCalls

        delegate.resetCalls()
        measureAll(cached, ch2)
        val c2 = delegate.glyphCalls

        delegate.resetCalls()
        measureAll(cached, ch1)
        val repeatCalls = delegate.glyphCalls

        println("[advance-hit] 冷启动前 10 段 $cold10/10 = ${per1000(cold10, 10)}‰ 次每段")
        println("[advance-hit] 第 1 章 $c1/${ch1.size} = ${per1000(c1, ch1.size)}‰ 次每段")
        println("[advance-hit] 第 2 章 $c2/${ch2.size} = ${per1000(c2, ch2.size)}‰ 次每段")
        println("[advance-hit] 热表重测第 1 章 = $repeatCalls 次")

        assertEquals("热表重测同一批文本必须零平台度量", 0, repeatCalls)
        assertTrue("每段最多一次合成串度量，实测 $c1/${ch1.size}", c1 <= ch1.size)
        assertTrue("第 2 章必须严格少于第 1 章（$c1 → $c2）", c2 < c1)
        if (c2 * 2 >= ch2.size) {
            val chars = (ch1 + ch2).flatMapTo(LinkedHashSet()) { it.toList() }
            poisonProbe(cached, delegate, chars) // 第一遍只负责把未测码位补齐
            val poisoned = poisonProbe(cached, delegate, chars)
            println("[advance-hit] 不可缓存码位 ${poisoned.size} 个: ${poisoned.take(40).joinToString("")}")
        }
        assertTrue(
            "第 2 章仍接近 1.0：大量码位被标成 POISON 或整表禁用（实测 $c2/${ch2.size}）",
            c2 * 2 < ch2.size,
        )
    }

    /**
     * 3. 标定结果落一次日志：edgeFirst / edgeLast 实测值、被标成永不缓存的字符、表是否整体禁用。
     * 这三项直接回答「这套东西到底生效了没」。
     *
     * edgeFirst / edgeLast 是 `AdvanceTable` 私有字段，这里按 `probe()` 同一条公式
     * （`"ccc"` 的首末项减内部项）在裸实现上复算，与表内标定值同源。
     */
    @Test
    fun `标定结果落一次日志`() {
        val (cached, delegate) = newCached()
        val bare = newBare()

        val three = FloatArray(3)
        bare.measureGlyphWidths("的的的", three)
        val edgeFirst = three[0] - three[1]
        val edgeLast = three[2] - three[1]
        println("[advance-cal] edgeFirst=$edgeFirst edgeLast=$edgeLast innerAdvance=${three[1]}")
        assertEquals(spacingPx / 2f, edgeFirst, 0f)
        assertEquals(spacingPx / 2f, edgeLast, 0f)

        // 表是否整体生效：预热后纯汉字串必须 0 次平台度量
        val warm = "的一我中"
        val buf = FloatArray(warm.length)
        cached.measureGlyphWidths(warm, buf)
        delegate.resetCalls()
        cached.measureGlyphWidths(warm, buf)
        val tableEnabled = delegate.glyphCalls == 0
        println("[advance-cal] tableEnabled=$tableEnabled")
        assertTrue("标定成功时字宽表必须生效", tableEnabled)

        // 被标成永不缓存的字符：连写会改 advance 的 —— 与 ……
        val candidates = ("的一我中，。“”…—あ" + "阅读排版金样").toCollection(LinkedHashSet())
        poisonProbe(cached, delegate, candidates)
        val poisoned = poisonProbe(cached, delegate, candidates)
        println("[advance-cal] poisoned=${poisoned.joinToString("")} count=${poisoned.size}")
        assertEquals("只有 advance 依赖邻居的连写字符该永不缓存", listOf('—', '…'), poisoned.sorted())
    }

    /**
     * 4. 并发一致性：多线程各自 createOrNull 后在同一张冷表上并发度量，
     * 结果必须与直调平台逐元素一致（验那张表的无锁幂等写确实良性）。
     */
    @Test
    fun `并发度量与直调平台逐元素一致`() {
        val texts = samples + corpus(seed = 31, paragraphs = 40, chars = 50)
        val bare = newBare()
        val expected = texts.map { t ->
            FloatArray(t.length).also { bare.measureGlyphWidths(t, it) }.toList()
        }

        install() // 重新注册 ⇒ 清空字宽表，让线程们在冷表上抢写
        val threadCount = 4
        val results = ConcurrentHashMap<Int, List<List<Float>>>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { id ->
            Thread({
                try {
                    val cached = newCachedOnly()
                    gate.await()
                    repeat(8) {
                        results[id] = texts.map { t ->
                            FloatArray(t.length).also { cached.measureGlyphWidths(t, it) }.toList()
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                } finally {
                    done.countDown()
                }
            }, "advance-$id").start()
        }
        gate.countDown()

        assertTrue("并发度量 60s 未跑完", done.await(60, TimeUnit.SECONDS))
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
        for (id in 0 until threadCount) {
            assertEquals("线程 $id 的结果与直调不一致", expected, results[id])
        }
    }

    /**
     * 首末补偿量与字符有关（'一' 的补偿量与 '的' 不同）时标定必须整表禁用：
     * 之后每次度量都老老实实直调平台，绝不出现「命中」，结果自然与直调相等。
     */
    @Test
    fun `首末补偿量与字符有关时整表禁用并退回直调`() {
        delegates.clear()
        TextMeasurerProviders.register { size, spacing, _, _ ->
            EdgeCompensatedMeasurer(size, spacing, edgeQuirkChars = setOf('一')).also { delegates += it }
        }
        val (cached, delegate) = newCached()
        val bare = newBare(edgeQuirk = setOf('一'))

        for (text in samples) assertSameWidths("整表禁用", text, cached, bare)

        delegate.resetCalls()
        val buf = FloatArray(4)
        cached.measureGlyphWidths("的一我中", buf)
        assertEquals("表禁用后每次度量都必须直调平台，不许有命中", 1, delegate.glyphCalls)
    }

    private fun per1000(calls: Int, paragraphs: Int) = calls * 1000 / paragraphs
}
