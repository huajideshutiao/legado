package io.legado.app.model.audio

import kotlin.math.pow

/**
 * 律动包络 (MilkDrop / Butterchurn 的写法): 快包络上升保留 0.2、下降保留 0.5, 慢平均 0.992,
 * 三者都按 `keep^(dt*30/1000)` 换算帧率; 输出"快包络 ÷ 慢平均"的比值线性映射到 0~1。
 *
 * 这是一条连续曲线, 随鼓点起伏; 不用离散的起音检测 (会漏拍、会卡)。
 * 歌词辉光的单值能量与边缘光效的各频段驱动共用这一份实现, 两者口径同源。
 */
class AudioPulseEnvelope(private val count: Int) {

    internal companion object {
        /** 快包络: 上升 / 下降 (30fps 基准) */
        const val ATTACK_KEEP = 0.2
        const val RELEASE_KEEP = 0.5

        /** 慢平均 (30fps 基准) */
        const val LONG_AVG_KEEP = 0.992

        /**
         * 映射区间: 快包络/慢平均 的典型区间约 0.5~2.0, 线性映射到 0~1。
         * 区间取自 MilkDrop 的 att 定义 (avg/longAvg) 与静音钳制 (longAvg<0.001 时取 1)。
         */
        const val PULSE_MIN_RATIO = 0.5
        const val PULSE_RANGE = 1.5
    }

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
     * @param values 本帧各段原始幅度 (长度必须等于构造时的段数)
     * @return 各段 0~1 驱动值 (同一个实例, 只在下次 push 前有效)
     */
    fun push(dtMs: Double, values: DoubleArray): FloatArray {
        val frameScale = dtMs * 30.0 / 1000.0
        val attackKeep = ATTACK_KEEP.pow(frameScale)
        val releaseKeep = RELEASE_KEEP.pow(frameScale)
        val longKeep = LONG_AVG_KEEP.pow(frameScale)
        for (i in 0 until count) {
            val value = values[i].coerceAtLeast(0.0)
            val fastKeep = if (value > fast[i]) attackKeep else releaseKeep
            fast[i] += (value - fast[i]) * (1.0 - fastKeep)
            long[i] = if (long[i] <= 0.0) value else long[i] * longKeep + value * (1.0 - longKeep)
            val ratio = if (long[i] > 1e-6) fast[i] / long[i] else 1.0
            out[i] = ((ratio - PULSE_MIN_RATIO) / PULSE_RANGE).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }
}
