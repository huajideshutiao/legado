package io.legado.app.help.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.audio.AudioVisualAnalyzer
import java.nio.ByteBuffer

/**
 * 真实物理 PCM 听觉驱动：作为 [TeeAudioProcessor] 的旁路消费者接在音频管道上。
 *
 * 旁路（tee）只观察、不改写：输入格式与输出格式完全一致，下游的静音跳过与变速
 * 处理器拿到的仍是原始的 16 位 PCM。
 *
 * 这里只负责按管道给出的编码把多声道 PCM 混成单声道；按 hop 分析与包络累计统一由
 * [AudioVisualAnalyzer] 负责（四端口径同一份实现）。
 */
@OptIn(UnstableApi::class)
class EnergyAudioBufferSink : TeeAudioProcessor.AudioBufferSink {

    private var analyzer: AudioVisualAnalyzer? = null
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /** 每帧字节数; 不支持的编码给 0 */
    private val bytesPerSample: Int
        get() = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        val sampleRate = sampleRateHz.coerceAtLeast(8000)
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        analyzer = AudioVisualAnalyzer(sampleRate)
        AudioPlayShared.publishLevelOnly(0f)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val analyzer = analyzer ?: return
        val bytesPerFrame = bytesPerSample * channels
        if (bytesPerFrame <= 0) return
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return

        analyzer.playSpeed = AudioPlayShared.playSpeed
        analyzer.beginBuffer()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> feedShorts(buffer, frames, analyzer)
            C.ENCODING_PCM_32BIT -> feedInts(buffer, frames, analyzer)
            C.ENCODING_PCM_FLOAT -> feedFloats(buffer, frames, analyzer)
            C.ENCODING_PCM_24BIT -> feed24Bit(buffer, frames, analyzer)
        }
        // 一缓冲只派发一次: 块内每个 hop 的起伏已按真实时长累进两条包络
        if (analyzer.hasFrame) AudioPlayShared.publishVisual(analyzer.energy, analyzer.bands)
    }

    private fun feedShorts(buffer: ByteBuffer, frames: Int, analyzer: AudioVisualAnalyzer) {
        val view = buffer.asShortBuffer()
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                mono += view.get(frame * channels + channel) / 32768.0
            }
            analyzer.pushSample(mono / channels)
        }
    }

    private fun feedInts(buffer: ByteBuffer, frames: Int, analyzer: AudioVisualAnalyzer) {
        val view = buffer.asIntBuffer()
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                mono += view.get(frame * channels + channel) / 2147483648.0
            }
            analyzer.pushSample(mono / channels)
        }
    }

    private fun feedFloats(buffer: ByteBuffer, frames: Int, analyzer: AudioVisualAnalyzer) {
        val view = buffer.asFloatBuffer()
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                mono += view.get(frame * channels + channel)
            }
            analyzer.pushSample(mono / channels)
        }
    }

    /** 24 位小端有符号: 3 字节一组, 最高位为符号位 */
    private fun feed24Bit(buffer: ByteBuffer, frames: Int, analyzer: AudioVisualAnalyzer) {
        for (frame in 0 until frames) {
            var mono = 0.0
            for (channel in 0 until channels) {
                // 绝对下标以缓冲起点为基准 (asShortBuffer 那一类视图是按当前位置算的, 两者口径要对齐)
                val base = buffer.position() + (frame * channels + channel) * 3
                val b0 = buffer.get(base).toInt() and 0xFF
                val b1 = buffer.get(base + 1).toInt() and 0xFF
                val b2 = buffer.get(base + 2).toInt() and 0xFF
                var value = b0 or (b1 shl 8) or (b2 shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                mono += value / 8388608.0
            }
            analyzer.pushSample(mono / channels)
        }
    }
}
