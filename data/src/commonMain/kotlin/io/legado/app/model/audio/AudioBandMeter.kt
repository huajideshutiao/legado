package io.legado.app.model.audio

import kotlin.math.pow

/**
 * 多频段驱动: 每段独立做"快包络 ÷ 慢平均", 与 [AudioEnergyMeter] 的律动口径同源。
 *
 * 与 [AudioEnergyMeter] 的区别: 那边把低频合成一条总曲线给歌词辉光, 这边保留各段
 * 独立起伏 —— 低频鼓点、中频人声、高频镲片各自驱动画面的不同位置, 不会整圈一起亮。
 *
 * 没有频谱的端 (桌面 / 鸿蒙) 不调用本类, 由调用方用整体电平统一填充 (见各端接线处),
 * 那里不做伪造的伪频谱。
 */
class AudioBandMeter {

    companion object {
        /** MilkDrop 快包络: 上升 0.2 / 下降 0.5 (30fps 基准) */
        private const val ATTACK_KEEP = 0.2
        private const val RELEASE_KEEP = 0.5

        /** MilkDrop 慢平均: 0.992 (30fps 基准) */
        private const val LONG_AVG_KEEP = 0.992

        /** 快包络/慢平均 的典型区间约 0.5~2.0, 线性映射到 0~1 (同 AudioEnergyMeter) */
        private const val PULSE_MIN_RATIO = 0.5
        private const val PULSE_RANGE = 1.5
    }

    private val count = AudioSpectrum.BAND_COUNT
    private val fast = DoubleArray(count)
    private val long = DoubleArray(count)
    private val out = FloatArray(count)

    fun reset() {
        fast.fill(0.0)
        long.fill(0.0)
        out.fill(0f)
    }

    /**
     * @param dtMs 本帧覆盖的墙钟时长
     * @param bands 各段幅度均值 ([AudioSpectrum.Frame.bands])
     * @return 各段 0~1 驱动值 (同一个实例, 调用方需自行复制)
     */
    fun push(dtMs: Double, bands: DoubleArray): FloatArray {
        if (dtMs <= 0.0 || bands.size != count) return out
        val frameScale = dtMs * 30.0 / 1000.0
        val attackKeep = ATTACK_KEEP.pow(frameScale)
        val releaseKeep = RELEASE_KEEP.pow(frameScale)
        val longKeep = LONG_AVG_KEEP.pow(frameScale)
        for (i in 0 until count) {
            val value = bands[i].coerceAtLeast(0.0)
            val fastKeep = if (value > fast[i]) attackKeep else releaseKeep
            fast[i] += (value - fast[i]) * (1.0 - fastKeep)
            long[i] = if (long[i] <= 0.0) value else long[i] * longKeep + value * (1.0 - longKeep)
            val ratio = if (long[i] > 1e-6) fast[i] / long[i] else 1.0
            out[i] = ((ratio - PULSE_MIN_RATIO) / PULSE_RANGE).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }
}
