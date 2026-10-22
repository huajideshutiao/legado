package io.legado.app.model.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 频谱到显示信号的链路契约 (读数驱动页面边缘光效的各段起伏):
 * 频段值必须来自真实 PCM、各段互不串扰、每个 hop 看到的是自己那段窗口。
 *
 * 断言都是"一类行为"的分布量 (哪一段占优、读数是否随窗口推进变化), 不锁具体数值。
 */
class AudioSpectrumTest {

    private val sampleRate = 44100

    /** 往频谱里喂 [frames] 帧 [hz] 的正弦 (相位从 [startIndex] 续上), 幅度 [amplitude] */
    private fun feedTone(
        spectrum: AudioSpectrum,
        rate: Int = sampleRate,
        hz: Double,
        frames: Int,
        amplitude: Double = 0.5,
        startIndex: Int = 0,
    ) {
        repeat(frames) { offset ->
            val index = startIndex + offset
            spectrum.pushSample(amplitude * sin(2.0 * PI * hz * index / rate))
        }
    }

    private fun feedSilence(spectrum: AudioSpectrum, frames: Int) {
        repeat(frames) { spectrum.pushSample(0.0) }
    }

    /** 读数最大的频段序号 */
    private fun dominantBand(bands: DoubleArray): Int = bands.indices.maxBy { bands[it] }

    private fun bandsOf(spectrum: AudioSpectrum): DoubleArray =
        spectrum.analyze()?.bands?.copyOf() ?: error("窗口已满, 必须有读数")

    @Test
    fun `频段值来自真实输入`() {
        val spectrum = AudioSpectrum(sampleRate)
        feedTone(spectrum, hz = 100.0, frames = 2048)
        val bands = bandsOf(spectrum)
        assertEquals(AudioSpectrum.BAND_COUNT, bands.size)
        assertTrue("各段幅度不得为负: ${bands.toList()}", bands.all { it >= 0.0 })
        assertTrue("低频输入必须点亮低频段: ${bands.toList()}", bands.sum() > 0.0)
        assertTrue("100Hz 应落在最低几段: ${bands.toList()}", dominantBand(bands) <= 2)
    }

    @Test
    fun `各采样率下低频都落在最低几段`() {
        // 频段边界随采样率变 (binHz 与 Nyquist 夹取), 换采样率不能把低频挪到别处
        for (rate in listOf(8000, 22050, 44100, 48000)) {
            val spectrum = AudioSpectrum(rate)
            feedTone(spectrum, rate = rate, hz = 100.0, frames = 2048)
            val bands = bandsOf(spectrum)
            assertTrue("$rate: 各段幅度不得为负", bands.all { it >= 0.0 })
            assertTrue("$rate: 低频输入必须点亮低频段: ${bands.toList()}", bands.sum() > 0.0)
            assertTrue(
                "$rate: 100Hz 应落在最低四分之一段: ${bands.toList()}",
                dominantBand(bands) <= AudioSpectrum.BAND_COUNT / 4,
            )
        }
    }

    @Test
    fun `各段互不串扰`() {
        // 同一幅度的高低两个音: 占优段必须分开, 且各自对侧的段远小于自己
        val bass = AudioSpectrum(sampleRate).also { feedTone(it, hz = 100.0, frames = 2048) }
            .let { bandsOf(it) }
        val treble = AudioSpectrum(sampleRate).also { feedTone(it, hz = 5000.0, frames = 2048) }
            .let { bandsOf(it) }

        val bassPeak = dominantBand(bass)
        val treblePeak = dominantBand(treble)
        assertTrue("低频应占低段: $bassPeak", bassPeak < treblePeak)
        assertTrue("高频应占高段: $treblePeak", treblePeak >= AudioSpectrum.BAND_COUNT / 2)
        assertTrue(
            "低频音的末段应远小于它自己的峰值: ${bass.toList()}",
            bass.last() < bass[bassPeak] / 10,
        )
        assertTrue(
            "高频音的首段应远小于它自己的峰值: ${treble.toList()}",
            treble.first() < treble[treblePeak] / 10,
        )
    }

    @Test
    fun `每个 hop 看到自己那段窗口`() {
        val spectrum = AudioSpectrum(sampleRate)
        feedSilence(spectrum, 1024)
        val silent = bandsOf(spectrum)
        assertTrue("静音窗口应全为 0: ${silent.toList()}", silent.all { it == 0.0 })

        // 再喂一个 hop (相位接着上一段): 窗口里一半是静音一半是正弦, 读数必须起来
        feedTone(spectrum, hz = 100.0, frames = 512)
        val mixed = bandsOf(spectrum)
        assertTrue("新窗口里有声音, 读数必须起来: ${mixed.toList()}", mixed.sum() > 0.0)

        // 再喂一个 hop: 窗口整体前移, 读数必须与上一 hop 不同
        // (同一窗口重复分析只会得到同一个值, 块内变化就是这么丢的)
        feedTone(spectrum, hz = 100.0, frames = 512, startIndex = 512)
        val shifted = bandsOf(spectrum)
        assertNotEquals("相邻 hop 的窗口不同, 读数应随之变化", mixed[0], shifted[0], 1e-9)
    }

    @Test
    fun `窗口未满时没有读数`() {
        val spectrum = AudioSpectrum(sampleRate)
        feedTone(spectrum, hz = 100.0, frames = 1023)
        assertNull(spectrum.analyze())
    }

    @Test
    fun `reset 后要重新填满窗口才有读数`() {
        val spectrum = AudioSpectrum(sampleRate)
        feedTone(spectrum, hz = 100.0, frames = 2048)
        bandsOf(spectrum)
        spectrum.reset()
        assertNull("复位清空环形窗口, 读数必须等重新填满", spectrum.analyze())
        feedSilence(spectrum, 1023)
        assertNull(spectrum.analyze())
        feedSilence(spectrum, 1)
        assertEquals(0.0, spectrum.analyze()!!.lowBand, 0.0)
    }
}
