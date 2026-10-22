package io.legado.app.model

import io.legado.app.model.audio.AudioSpectrum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 可视信号的派发契约 (显示侧据此判断音频管道是否还在供数):
 * 每帧都是新实例、频段数组与调用方解耦、没有新频段值时沿用上一帧。
 *
 * 稳态段里相邻两帧的数值可能逐位相同, 只靠状态流去重会让显示侧收不到通知,
 * 把"还在播"误判成断供。
 */
class AudioVisualFrameTest {

    private fun bands(vararg values: Float): FloatArray =
        FloatArray(AudioSpectrum.BAND_COUNT) { values.getOrElse(it) { 0f } }

    @Test
    fun `数值相同的相邻帧也是新实例`() {
        val bands = bands(0.5f)
        AudioPlayShared.publishVisual(0.5f, bands)
        val first = AudioPlayShared.audioVisual.value
        AudioPlayShared.publishVisual(0.5f, bands)
        val second = AudioPlayShared.audioVisual.value
        assertNotSame("数值逐位相同的两帧也必须换实例", first, second)
        assertEquals(first.energy, second.energy, 0f)
    }

    @Test
    fun `频段数组与调用方解耦`() {
        val reused = bands(0.25f)
        AudioPlayShared.publishVisual(0.3f, reused)
        val frame = AudioPlayShared.audioVisual.value
        reused.fill(0.9f)
        assertEquals("派发后改调用方数组不得影响已派发的帧", 0.25f, frame.bands[0], 0f)
    }

    @Test
    fun `没有新频段值时沿用上一帧`() {
        AudioPlayShared.publishVisual(0.2f, bands(0.4f))
        val previous = AudioPlayShared.audioVisual.value.bands
        AudioPlayShared.publishVisual(0.6f, null)
        val frame = AudioPlayShared.audioVisual.value
        assertEquals(0.6f, frame.energy, 0f)
        assertSame("该帧没有新频段值, 应沿用上一帧那份", previous, frame.bands)
    }

    @Test
    fun `无频谱的端把整体电平填满各段`() {
        AudioPlayShared.publishLevelOnly(0.35f)
        val frame = AudioPlayShared.audioVisual.value
        assertEquals(0.35f, frame.energy, 0f)
        assertEquals(AudioSpectrum.BAND_COUNT, frame.bands.size)
        frame.bands.forEach { assertEquals(0.35f, it, 0f) }
    }
}
