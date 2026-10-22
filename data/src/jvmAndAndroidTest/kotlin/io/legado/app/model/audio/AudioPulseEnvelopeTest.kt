package io.legado.app.model.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/**
 * 律动驱动的对外契约 (读数用来驱动歌词辉光与页面边缘光效):
 * 静默为 0、稳态不出起伏、瞬态要有明显行程且不到上限、与绝对电平无关、各段互不串扰。
 * 断言都是"一类行为"的分布量 (峰值/均值/变化幅度), 不锁某个具体数值。
 */
class AudioPulseEnvelopeTest {

    /** 一次 push 覆盖的墙钟时长 (44.1k / hop 512 帧) */
    private val hopMs = 512.0 / 44100.0 * 1000.0

    private fun run(signal: List<Double>, count: Int = 1): List<FloatArray> {
        val envelope = AudioPulseEnvelope(count)
        return signal.map { value ->
            envelope.push(hopMs, DoubleArray(count) { value }).copyOf()
        }
    }

    /** 稳态底 + 周期性瞬态 (每 30 帧来一次, 持续 3 帧), 模拟鼓点 */
    private fun percussive(multiplier: Double, frames: Int = 900): List<Double> =
        List(frames) { index -> if (index % 30 < 3) multiplier else 1.0 }

    /** 取后半段 (甩掉起步的自适应过程) */
    private fun tail(values: List<FloatArray>, column: Int = 0): List<Float> =
        values.drop(values.size / 2).map { it[column] }

    @Test
    fun `静默读数为零`() {
        val values = tail(run(List(200) { 0.0 }))
        assertTrue("静默应全为 0: ${values.distinct()}", values.all { it == 0f })
    }

    @Test
    fun `稳态输入不产生起伏`() {
        val values = tail(run(List(1200) { 1.0 }))
        val spread = values.max() - values.min()
        assertTrue("稳态起伏应趋近 0: $spread", spread < 0.01f)
    }

    @Test
    fun `瞬态给出明显行程且够不到上限`() {
        for (multiplier in listOf(2.0, 4.0, 8.0, 32.0)) {
            val values = tail(run(percussive(multiplier)))
            val peak = values.max()
            val mean = values.average().toFloat()
            assertTrue("峰值应明显高于均值: m=$multiplier peak=$peak mean=$mean", peak - mean > 0.3f)
            assertTrue("峰值不该贴上限: m=$multiplier peak=$peak", peak < 0.95f)
            assertTrue("读数不得为负: $values", values.min() >= 0f)
        }
    }

    @Test
    fun `动态小的素材也有可见行程`() {
        // 起伏只有 ±20% (母带压得很紧、或朗读人声): 仍要能看到明暗变化
        val signal = List(1200) { index ->
            1.0 * (1.0 + 0.2 * sin(2.0 * PI * index / 30.0))
        }
        val values = tail(run(signal))
        val spread = values.max() - values.min()
        assertTrue("行程过小: max=${values.max()} min=${values.min()}", spread > 0.2f)
    }

    @Test
    fun `读数与绝对电平无关`() {
        // 同一段相对起伏整体放大 100 倍 (只是更响), 读数必须逐帧一致
        val quiet = tail(run(percussive(8.0)))
        val loud = tail(run(percussive(8.0).map { it * 100.0 }))
        for (index in quiet.indices) {
            assertEquals("第 $index 帧", quiet[index], loud[index], 0.001f)
        }
    }

    @Test
    fun `各段互不串扰`() {
        val envelope = AudioPulseEnvelope(2)
        var hotPeak = 0f
        var silentMax = 0f
        repeat(900) { index ->
            val hot = if (index % 30 < 3) 8.0 else 1.0
            val out = envelope.push(hopMs, doubleArrayOf(0.0, hot))
            silentMax = maxOf(silentMax, out[0])
            hotPeak = maxOf(hotPeak, out[1])
        }
        assertEquals("全程静默的那段必须一直是 0", 0f, silentMax, 0.0001f)
        assertTrue("有信号的那段应有读数: $hotPeak", hotPeak > 0.3f)
    }

    @Test
    fun `从静默恢复后重新起振`() {
        val values = run(List(200) { 0.0 } + percussive(8.0))
        assertEquals("静默段应为 0", 0f, values[190][0], 0.0001f)
        val after = values.subList(200, values.size).map { it[0] }
        assertTrue("恢复后应重新起振: ${after.max()}", after.max() > 0.3f)
    }

    @Test
    fun `reset 后回到初始状态`() {
        val envelope = AudioPulseEnvelope(1)
        percussive(8.0).forEach { envelope.push(hopMs, doubleArrayOf(it)) }
        envelope.reset()
        assertEquals(0f, envelope.push(hopMs, doubleArrayOf(0.0))[0], 0.0001f)
        // 复位后首帧只播种均值, 不该凭空造出一个假起点
        assertEquals(0f, envelope.push(hopMs, doubleArrayOf(5.0))[0], 0.0001f)
    }
}
