package io.legado.app.ui.book.read.page.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版基准 harness（非断言型，只打印数字）。
 *
 * 用固定字宽 fake 度量器把「一章排版」拆成 测量（直调 / advance 表冷 / advance 表热）/
 * 断行 / Phase 1 冷 / Phase 1 热 / Phase 2 共七段分别计时，
 * 供 advance 缓存、Phase 1 并行等优化前后对拍。
 *
 * 单独跑：`gradlew :shared:jvmTest --tests "*LayoutBenchmarkHarness*"`
 * （Android host 变体同名：`gradlew :shared:testDebugUnitTest --tests "*LayoutBenchmarkHarness*"`）
 * 数字走 stdout，项目没配 showStandardStreams，加 `-i` 或读 test-results 的 XML。
 *
 * 注意 1b/1c 会往 [TextMeasurerProviders] 注册工厂且没有反注册 API。
 */
class LayoutBenchmarkHarness {

    private val paragraphCount = 200
    private val charsPerParagraph = 200
    private val visibleWidth = 1000
    private val visibleHeight = 1600
    private val textHeight = 40f
    private val descent = 8f
    private val indent = "　　"
    private val benchSizePx = 40f
    private val benchSpacingPx = 4f

    private val measurer = FixedWidthMeasurer(textSizePx = 40f, descent = 8f, ascent = -32f)

    /** 含标点的字池，让 [LineBreaker] 的禁则回退分支真正被走到。 */
    private val pool = "甲乙丙丁戊，己庚辛壬癸。子丑寅卯辰巳午未申酉"

    private val paragraphs: List<String> = List(paragraphCount) { p ->
        val sb = StringBuilder(indent.length + charsPerParagraph)
        sb.append(indent)
        for (i in 0 until charsPerParagraph) {
            sb.append(pool[(p * 7 + i * 3) % pool.length])
        }
        sb.toString()
    }

    private fun layoutOne(text: String, cache: ParagraphLayoutCache?) =
        ParagraphLayoutEngine.layoutParagraph(
            text = text,
            measurer = measurer,
            visibleWidth = visibleWidth,
            paragraphIndent = indent,
            indentCharWidth = 40f,
            isTitle = false,
            isFirstLine = true,
            paragraphNum = 1,
            textHeight = textHeight,
            descent = descent,
            cache = cache,
            fontKey = "bench",
        )

    private val config = PaginationConfig(
        visibleWidth = visibleWidth,
        visibleHeight = visibleHeight,
        lineSpacingExtra = 1.2f,
        paragraphSpacing = 5,
        textHeight = textHeight,
        textFullJustify = true,
    )

    private suspend fun bench(label: String, block: suspend () -> Unit): Long {
        repeat(2) { block() }
        var best = Long.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            block()
            val cost = System.nanoTime() - start
            if (cost < best) best = cost
        }
        report(label, best)
        return best
    }

    private fun report(label: String, best: Long) {
        val us = best / 1000
        println("[layout-bench] $label 总 $us us  单段 ${best / paragraphCount} ns")
    }

    private fun measureAllGlyphs(m: TextMeasurer) {
        var buf = FloatArray(0)
        for (text in paragraphs) {
            if (buf.size < text.length) buf = FloatArray(text.length + 32)
            m.measureGlyphWidths(text, buf)
        }
    }

    /**
     * advance 表冷：每轮重新 register（清表）再只跑一遍，取最小值；首轮丢掉（吃 JIT / 类加载）。
     * 与「表热」对照就是 codepoint→advance 缓存的净收益。
     */
    private fun benchAdvanceCold(label: String): Long {
        var best = Long.MAX_VALUE
        repeat(6) { round ->
            TextMeasurerProviders.register { size, spacing, _, _ ->
                EdgeCompensatedMeasurer(size, spacing)
            }
            val m = TextMeasurerProviders.createOrNull(benchSizePx, benchSpacingPx, "bench", 400)
                ?: error("工厂已注册")
            val start = System.nanoTime()
            measureAllGlyphs(m)
            val cost = System.nanoTime() - start
            if (round > 0 && cost < best) best = cost
        }
        report(label, best)
        return best
    }

    private fun benchAdvanceHot(label: String): Long {
        val m = TextMeasurerProviders.createOrNull(benchSizePx, benchSpacingPx, "bench", 400)
            ?: error("工厂已注册")
        repeat(3) { measureAllGlyphs(m) } // 填表 + 预热
        var best = Long.MAX_VALUE
        repeat(5) {
            val start = System.nanoTime()
            measureAllGlyphs(m)
            val cost = System.nanoTime() - start
            if (cost < best) best = cost
        }
        report(label, best)
        return best
    }

    @Test
    fun `排版分段耗时`() = runBlocking {
        val metrics = paragraphs.map { layoutOne(it, null) }
        val lineCount = metrics.sumOf { it.lineCount }
        val pageCount = PaginationEngine.paginate(metrics, config).size
        println(
            "[layout-bench] 规模 $paragraphCount 段 × $charsPerParagraph 字 = " +
                "${paragraphCount * charsPerParagraph} 字, 可视区 ${visibleWidth}x$visibleHeight"
        )
        println("[layout-bench] 产出 $lineCount 行 / $pageCount 页")

        // 1 测量：逐字宽度 + 字素簇聚合（安卓上这段是 JNI Paint 调用，fake 下只剩纯算术）
        val splits = ArrayList<TextSplit>(paragraphCount)
        bench("1a 测量(直调+聚簇)     ") {
            splits.clear()
            for (text in paragraphs) {
                val widths = FloatArray(text.length)
                measurer.measureGlyphWidths(text, widths)
                splits.add(measureTextSplit(text, widths))
            }
        }

        // 1b/1c：advance 缓存冷热对照（只量 measureGlyphWidths，不含聚簇）
        val cold = benchAdvanceCold("1b 测量(advance 表冷)  ")
        val hot = benchAdvanceHot("1c 测量(advance 表热)  ")
        println("[layout-bench] advance 缓存收益 冷/热 = ${if (hot > 0) cold / hot else -1} 倍")

        // 2 断行：只跑 LineBreaker 状态机，输入用上一段已聚好的簇
        // FixedWidthMeasurer 不提供 ICU 断点，这里量的是中文禁则表退化路径
        bench("2 断行(LineBreaker)     ") {
            for ((idx, split) in splits.withIndex()) {
                ParagraphLayoutEngine.breakLines(
                    text = paragraphs[idx],
                    words = split.words,
                    widths = split.widths,
                    measurer = measurer,
                    isFirstLine = true,
                    indentLength = indent.length,
                    visibleWidth = visibleWidth,
                )
            }
        }

        // 3 Phase 1 冷：测量 + 断行 + LineMetrics 构造，无缓存
        bench("3 Phase1 冷(无缓存)    ") {
            for (text in paragraphs) layoutOne(text, null)
        }

        // 4 Phase 1 热：全部命中段落缓存，只剩一次哈希查找（调行距/边距时走的就是这条）
        val warmCache = ParagraphLayoutCache(maxSize = paragraphCount * 2)
        for (text in paragraphs) layoutOne(text, warmCache)
        bench("4 Phase1 热(命中缓存)  ") {
            for (text in paragraphs) layoutOne(text, warmCache)
        }

        // 5 Phase 2 分页：纯算术切页 + 逐字 Column 构造
        bench("5 Phase2 分页(paginate)") {
            PaginationEngine.paginate(metrics, config)
        }

        assertTrue("基准数据必须真的排出页来", pageCount > 0 && lineCount > 0)
    }
}
