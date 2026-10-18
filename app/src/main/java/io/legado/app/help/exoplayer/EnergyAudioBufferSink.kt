package io.legado.app.help.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.audio.AudioBandMeter
import io.legado.app.model.audio.AudioEnergyMeter
import io.legado.app.model.audio.AudioLoudnessMeter
import io.legado.app.model.audio.AudioSpectrum
import java.nio.ByteBuffer

/**
 * 真实物理 PCM 听觉驱动：作为 [TeeAudioProcessor] 的旁路消费者接在音频管道上。
 *
 * 旁路（tee）只观察、不改写：输入格式与输出格式完全一致，下游的静音跳过与变速
 * 处理器拿到的仍是原始的 16 位 PCM。
 *
 * 每 hop（512 帧）产出一帧驱动：K 加权 400ms 瞬时响度（LKFS）做底色，
 * 1024 点谱通量做律动；解码按管道给出的编码分支处理，多声道取平均成单声道。
 */
@OptIn(UnstableApi::class)
class EnergyAudioBufferSink : TeeAudioProcessor.AudioBufferSink {

    private companion object {
        /** 分析步长: 512 帧 ≈ 11.6ms @44.1k */
        const val HOP_FRAMES = 512
    }

    private var loudness: AudioLoudnessMeter? = null
    private var spectrum: AudioSpectrum? = null
    private var energyMeter = AudioEnergyMeter()
    private var bandMeter = AudioBandMeter()
    private var pendingFrames = 0
    private var sampleRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz.coerceAtLeast(8000)
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        loudness = AudioLoudnessMeter(sampleRate)
        spectrum = AudioSpectrum(sampleRate)
        energyMeter.reset()
        bandMeter.reset()
        pendingFrames = 0
        AudioPlayShared.audioEnergy.value = 0f
        AudioPlayShared.audioBands.value = FloatArray(AudioSpectrum.BAND_COUNT)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val loudness = loudness ?: return
        val spectrum = spectrum ?: return
        val frames = when (encoding) {
            C.ENCODING_PCM_16BIT -> feedShorts(buffer, loudness, spectrum)
            C.ENCODING_PCM_32BIT -> feedInts(buffer, loudness, spectrum)
            C.ENCODING_PCM_FLOAT -> feedFloats(buffer, loudness, spectrum)
            C.ENCODING_PCM_24BIT -> feed24Bit(buffer, loudness, spectrum)
            else -> return
        }
        if (frames <= 0) return

        pendingFrames += frames
        val hopMs = HOP_FRAMES.toDouble() / sampleRate * 1000.0
        val speed = AudioPlayShared.playSpeed.coerceAtLeast(0.1f)
        while (pendingFrames >= HOP_FRAMES) {
            pendingFrames -= HOP_FRAMES
            val lkfs = loudness.momentaryLkfs()
            val frame = spectrum.analyze()
            val lowBand = frame?.lowBand ?: 0.0
            val dtMs = hopMs / speed
            AudioPlayShared.audioEnergy.value = energyMeter.pushFrame(dtMs, lkfs, lowBand)
            // 各段独立起伏: 边缘光效按频段分开驱动, 不与上面那条合成曲线同涨同落
            // (必须复制: push 复用同一个数组实例, StateFlow 按引用相等会判为没变而不发出)
            frame?.let {
                AudioPlayShared.audioBands.value = bandMeter.push(dtMs, it.bands).copyOf()
            }
        }
    }

    private fun feedShorts(
        buffer: ByteBuffer,
        loudness: AudioLoudnessMeter,
        spectrum: AudioSpectrum,
    ): Int {
        val view = buffer.asShortBuffer()
        val frames = view.remaining() / channels
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                mono += view.get(frame * channels + channel) / 32768.0
            }
            val value = mono / channels
            loudness.pushSample(value)
            spectrum.pushSample(value)
        }
        return frames
    }

    private fun feedInts(
        buffer: ByteBuffer,
        loudness: AudioLoudnessMeter,
        spectrum: AudioSpectrum,
    ): Int {
        val view = buffer.asIntBuffer()
        val frames = view.remaining() / channels
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                mono += view.get(frame * channels + channel) / 2147483648.0
            }
            val value = mono / channels
            loudness.pushSample(value)
            spectrum.pushSample(value)
        }
        return frames
    }

    private fun feedFloats(
        buffer: ByteBuffer,
        loudness: AudioLoudnessMeter,
        spectrum: AudioSpectrum,
    ): Int {
        val view = buffer.asFloatBuffer()
        val frames = view.remaining() / channels
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                mono += view.get(frame * channels + channel)
            }
            val value = mono / channels
            loudness.pushSample(value)
            spectrum.pushSample(value)
        }
        return frames
    }

    /** 24 位小端有符号: 3 字节一组, 最高位为符号位 */
    private fun feed24Bit(
        buffer: ByteBuffer,
        loudness: AudioLoudnessMeter,
        spectrum: AudioSpectrum,
    ): Int {
        val frames = buffer.remaining() / (3 * channels)
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                val base = (frame * channels + channel) * 3
                val b0 = buffer.get(base).toInt() and 0xFF
                val b1 = buffer.get(base + 1).toInt() and 0xFF
                val b2 = buffer.get(base + 2).toInt() and 0xFF
                var value = b0 or (b1 shl 8) or (b2 shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                mono += value / 8388608.0
            }
            val value = mono / channels
            loudness.pushSample(value)
            spectrum.pushSample(value)
        }
        return frames
    }
}
