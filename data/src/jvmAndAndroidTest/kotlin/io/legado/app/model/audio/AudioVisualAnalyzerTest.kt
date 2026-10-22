package io.legado.app.model.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 按 hop 分析的可视分析器契约 (四端共用这一份):
 * 喂满一个 hop 才产出一帧、跨缓冲接着上次的进度、每个 hop 看到自己那段窗口。
 *
 * 断言都是"一类行为"的分布量, 不锁具体数值。
 */
class AudioVisualAnalyzerTest {

    private val sampleRate = 44100
    private val hop = AudioVisualAnalyzer.HOP_FRAMES

    private fun AudioVisualAnalyzer.feedTone(
        hz: Double,
        frames: Int,
        amplitude: Double = 0.5,
        startIndex: Int = 0,
    ) {
        repeat(frames) { offset ->
            val index = startIndex + offset
            pushSample(amplitude * sin(2.0 * PI * hz * index / sampleRate))
        }
    }

    private fun AudioVisualAnalyzer.feedSilence(frames: Int) {
        repeat(frames) { pushSample(0.0) }
    }

    @Test
    fun `喂满一个 hop 才产出一帧`() {
        val analyzer = AudioVisualAnalyzer(sampleRate)
        analyzer.beginBuffer()
        analyzer.feedTone(hz = 100.0, frames = hop - 1)
        assertFalse("还差一帧, 不该产出", analyzer.hasFrame)
        analyzer.feedTone(hz = 100.0, frames = 1, startIndex = hop - 1)
        assertTrue("满一个 hop 必须产出", analyzer.hasFrame)
    }

    @Test
    fun `跨缓冲接着上次的 hop 进度`() {
        val analyzer = AudioVisualAnalyzer(sampleRate)
        analyzer.beginBuffer()
        analyzer.feedTone(hz = 100.0, frames = 300)
        assertFalse(analyzer.hasFrame)
        // 换下一个缓冲: 只有 211 帧, 合计 511 帧还不够一个 hop
        analyzer.beginBuffer()
        analyzer.feedTone(hz = 100.0, frames = hop - 300 - 1, startIndex = 300)
        assertFalse("300+211 帧还不够一个 hop", analyzer.hasFrame)
        analyzer.beginBuffer()
        analyzer.feedTone(hz = 100.0, frames = 1, startIndex = hop - 1)
        assertTrue("补满这一帧就该产出", analyzer.hasFrame)
    }

    @Test
    fun `每个 hop 看到自己那段窗口`() {
        val analyzer = AudioVisualAnalyzer(sampleRate)
        // 两个 hop 的静音: 把 1024 点分析窗口填满, 并给包络播种
        analyzer.beginBuffer()
        analyzer.feedSilence(2 * hop)
        // 两个 hop 的鼓点: 第一个 hop 窗口里还一半是静音, 第二个 hop 才是满窗
        analyzer.beginBuffer()
        analyzer.feedTone(hz = 100.0, frames = 2 * hop, amplitude = 0.8)
        val kickBands = analyzer.bands?.copyOf() ?: error("窗口已满, 必须有频段读数")
        val kickEnergy = analyzer.energy
        // 下一个 hop 全是静音: 必须重新分析出新读数, 而不是停在上一个 hop 的数值上
        analyzer.beginBuffer()
        analyzer.feedSilence(hop)
        val quietBands = analyzer.bands?.copyOf() ?: error("缺频段读数")

        assertTrue("鼓点 hop 的频段读数必须起来: ${kickBands.toList()}", kickBands.any { it > 0f })
        // 读数按各段自身的相对起伏定标 (与绝对电平无关), 所以不能断言"静音 hop 每段都更小";
        // 能钉住的是每个 hop 都重算了一次
        assertTrue(
            "相邻 hop 的窗口不同, 读数必须变: kick=${kickBands.toList()} quiet=${quietBands.toList()}",
            kickBands.indices.any { kickBands[it] != quietBands[it] },
        )
        // 持续静音: 快包络追上慢均值后各段归零
        analyzer.beginBuffer()
        analyzer.feedSilence(20 * hop)
        val silentBands = analyzer.bands?.copyOf() ?: error("缺频段读数")
        assertTrue("长静音后各段读数必须归零: ${silentBands.toList()}", silentBands.all { it < 0.01f })
        // 150ms 响度窗有拖尾: 静音一个 hop 后能量还在高位, 要等窗口走完才落下去
        assertTrue("长静音后能量必须落下去", analyzer.energy < kickEnergy)
    }
}
