package io.legado.app.model.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 短窗频谱: 低频段能量。
 *
 * 窗长 1024、hop 512，周期 Hann 窗。只输出低频段（20–320Hz）的幅度和，
 * 与 MilkDrop/Butterchurn 的 bass 段一致 —— 视觉律动取这条连续能量曲线，
 * 不做离散的起音检测（离散事件会漏拍、卡顿）。
 */
class AudioSpectrum(private val sampleRate: Int) {

    private companion object {
        const val FFT_SIZE = 1024
        const val HALF = FFT_SIZE / 2
        const val FFT_LOG2 = 10
        const val RING_SIZE = FFT_SIZE

        fun reverseBits(value: Int): Int {
            var v = value
            var result = 0
            repeat(FFT_LOG2) {
                result = (result shl 1) or (v and 1)
                v = v shr 1
            }
            return result
        }
    }

    private val ring = DoubleArray(RING_SIZE)
    private var writeIndex = 0
    private var filled = 0

    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) {
        0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE)
    }
    private val reversed = IntArray(FFT_SIZE) { reverseBits(it) }
    private val binHz: Double
    private val bassStart: Int
    private val bassEnd: Int

    init {
        binHz = sampleRate.toDouble() / FFT_SIZE
        bassStart = (20.0 / binHz).toInt().coerceIn(1, HALF - 1)
        bassEnd = (320.0 / binHz).toInt().coerceIn(bassStart + 1, HALF)
    }

    fun reset() {
        ring.fill(0.0)
        writeIndex = 0
        filled = 0
    }

    fun pushSample(mono: Double) {
        ring[writeIndex] = mono
        writeIndex = (writeIndex + 1) % RING_SIZE
        if (filled < RING_SIZE) filled++
    }

    /** 低频段 (20–320Hz) 的幅度和; 窗口未满返回 null */
    fun analyzeLowBand(): Double? {
        if (filled < FFT_SIZE) return null
        var index = (writeIndex - FFT_SIZE + RING_SIZE) % RING_SIZE
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[index] * window[i]
            im[i] = 0.0
            index = (index + 1) % RING_SIZE
        }
        fft()

        var energy = 0.0
        for (k in bassStart until bassEnd) {
            energy += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        return energy
    }

    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val j = reversed[i]
            if (j > i) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle)
            val wIm = sin(angle)
            val halfLen = length / 2
            var start = 0
            while (start < FFT_SIZE) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until halfLen) {
                    val a = start + k
                    val b = a + halfLen
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length *= 2
        }
    }
}
