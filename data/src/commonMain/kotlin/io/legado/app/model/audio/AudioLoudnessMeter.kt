package io.legado.app.model.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * 感知响度（ITU-R BS.1770）：K 加权 + 150ms 视觉响度。
 *
 * 两段滤波参数取自 BS.1770 的 K 加权（与 libebur128/FFmpeg 的实现一致）：
 * - 高架: f0=1681.974450955533, G=3.999843853973347dB, Q=0.7071752369554196
 * - RLB 高通: f0=38.13547087602444, Q=0.5003270373238773
 * 系数按采样率用双线性变换现算（48k 下算出的值与 BS.1770 表格逐位一致）。
 *
 * 瞬时响度 = 150ms 窗内 K 加权均方和 E 的 10·log10(E) − 0.691 (LKFS)。
 * 这是绝对响度，越响越大，不需要"相对最近峰值"归一。
 */
class AudioLoudnessMeter(sampleRate: Int) {

    private class Biquad(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        private val c0 = b0
        private val c1 = b1
        private val c2 = b2
        private val d1 = a1
        private val d2 = a2
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = c0 * x + c1 * x1 + c2 * x2 - d1 * y1 - d2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }
    }

    private companion object {
        const val FLOOR_LKFS = -70.0

        /** 视觉响度窗: 150ms (400ms 是测量用窗, 对画面太慢) */
        const val WINDOW_SECONDS = 0.15

        fun highShelf(sampleRate: Int): DoubleArray {
            val f0 = 1681.974450955533
            val gain = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(gain / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return doubleArrayOf(
                (vh + vb * k / q + k * k) / a0,
                2.0 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0,
                2.0 * (k * k - 1.0) / a0,
                (1.0 - k / q + k * k) / a0,
            )
        }

        fun rlbHighPass(sampleRate: Int): DoubleArray {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1.0 + k / q + k * k
            return doubleArrayOf(
                1.0,
                -2.0,
                1.0,
                2.0 * (k * k - 1.0) / a0,
                (1.0 - k / q + k * k) / a0,
            )
        }
    }

    private val shelf = highShelf(sampleRate).let {
        Biquad(it[0], it[1], it[2], it[3], it[4])
    }
    private val highPass = rlbHighPass(sampleRate).let {
        Biquad(it[0], it[1], it[2], it[3], it[4])
    }

    /** 视觉响度窗的平方和 (滑动) */
    private val windowSize = (sampleRate * WINDOW_SECONDS).toInt().coerceAtLeast(1)
    private val squares = DoubleArray(windowSize)
    private var writeIndex = 0
    private var filled = 0
    private var sumSquares = 0.0

    fun reset() {
        shelf.reset()
        highPass.reset()
        squares.fill(0.0)
        writeIndex = 0
        filled = 0
        sumSquares = 0.0
    }

    /** 送入一个单声道样本 (-1.0~1.0) */
    fun pushSample(sample: Double) {
        val weighted = highPass.process(shelf.process(sample))
        val square = weighted * weighted
        sumSquares += square - squares[writeIndex]
        squares[writeIndex] = square
        writeIndex = (writeIndex + 1) % windowSize
        if (filled < windowSize) filled++
    }

    /** 150ms 瞬时响度 (LKFS); 窗口未满或静音时返回下限 */
    fun momentaryLkfs(): Double {
        if (filled < windowSize) return FLOOR_LKFS
        val energy = sumSquares / windowSize
        if (energy <= 1e-10) return FLOOR_LKFS
        return 10.0 * log10(energy) - 0.691
    }
}
