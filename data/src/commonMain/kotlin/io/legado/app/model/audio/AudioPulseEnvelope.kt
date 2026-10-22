package io.legado.app.model.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * 律动驱动: 每段"快包络 ÷ 慢平均"(MilkDrop / Butterchurn 的写法) 转成分贝偏离量,
 * 按该段自身的动态大小整体放大一档, 再过一条软上界曲线。
 *
 * - 快包络上升保留 0.2、下降保留 0.5, 慢平均保留 0.992, 都按 `keep^(dt*30/1000)` 换算帧率
 *   (快包络约 20ms 起 / 48ms 落, 慢平均时间常数约 4.2 秒)。输出是一条连续曲线, 随鼓点起伏;
 *   不用离散的起音检测 (会漏拍、会卡)。
 * - 用分贝表达偏离: 音乐的瞬态比近几秒均值高出 10~20dB 是常态, 线性比值域里 2 倍以上会全落到
 *   同一个数值上 (读数钉死), 分贝域里 2 倍 (6dB)、4 倍 (12dB)、8 倍 (18dB) 各占不同位置。
 * - 与绝对电平无关: 用的是比值, 换歌、换母带、调系统音量都不需要重新定标。
 * - 平淡素材 (如朗读) 的偏离量本身只有 1~2dB, 用该段 |偏离| 的慢跟踪量整体放大, 上限 [GAIN_MAX]
 *   倍 —— 放大封顶是为了不让底噪被推到满幅。
 * - 输出 = 1 - exp(-K·u), 恒小于 1 且**严格单调**: 偏离越大读数越大, 任何增量都还有增量,
 *   不存在"钉在上界"的平台段, 也不会出现"更强的瞬态读数反而更小"。
 *
 * 歌词辉光的单值能量与边缘光效的各频段驱动共用这一份实现, 两者口径同源。
 */
class AudioPulseEnvelope(private val count: Int) {

    internal companion object {
        /** 快包络: 上升 / 下降 (30fps 基准) */
        const val ATTACK_KEEP = 0.2
        const val RELEASE_KEEP = 0.5

        /** 慢平均 (30fps 基准) */
        const val LONG_AVG_KEEP = 0.992

        /** 偏离量的钳位区间 (dB): 下限避开 -∞, 上限避开数字静默/爆音把跨度拉穿 */
        private const val MIN_DB = -36.0
        private const val MAX_DB = 48.0

        /** 读数为 0 的偏离位置 (dB): 低于它就全暗 */
        private const val OFFSET_DB = 4.0

        /** 一个单位读数对应的偏离跨度 (dB): 越大越"耐Dynamic", 越小越容易被顶到高位 */
        private const val SPAN_DB = 22.0

        /** 软上界系数: 越大越快接近 1; 输出恒小于 1 */
        private const val SOFT_KNEE = 1.6

        /** |偏离| 的慢跟踪量 (30fps 基准, 时间常数约 8 秒) 与它的参考值 (dB) */
        private const val MAG_KEEP = 0.99583
        private const val MAG_REF_DB = 6.0

        /** 整体放大倍数区间 (平淡素材放大, 但不超过 [GAIN_MAX] 倍) */
        private const val GAIN_MIN = 1.0
        private const val GAIN_MAX = 1.8
    }

    private val fast = DoubleArray(count)
    private val long = DoubleArray(count)

    /** 各段 |偏离| 的慢跟踪量 (dB): 决定该段整体放大多少 */
    private val mag = DoubleArray(count)

    private val out = FloatArray(count)

    fun reset() {
        fast.fill(0.0)
        long.fill(0.0)
        mag.fill(0.0)
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
        val magKeep = MAG_KEEP.pow(frameScale)
        for (i in 0 until count) {
            val value = values[i].coerceAtLeast(0.0)
            if (long[i] <= 0.0) {
                // 该段首帧: 快包络与均值从同一个值起步, 比值从 1 开始
                // (否则均值从 0 跳到首值, 凭空造出一个 -36dB 的假起点, 之后几秒都抬不亮)
                long[i] = value
                fast[i] = value
                mag[i] = 0.0
                out[i] = 0f
                continue
            }
            val fastKeep = if (value > fast[i]) attackKeep else releaseKeep
            fast[i] += (value - fast[i]) * (1.0 - fastKeep)
            long[i] = long[i] * longKeep + value * (1.0 - longKeep)
            if (long[i] <= 1e-6) {
                out[i] = 0f
                mag[i] = 0.0
                continue
            }
            // 相对该段自身近几秒均值的偏离 (dB)
            val dev = if (fast[i] <= 1e-6) MIN_DB
            else (20.0 * log10(fast[i] / long[i])).coerceIn(MIN_DB, MAX_DB)
            mag[i] = mag[i] * magKeep + abs(dev) * (1.0 - magKeep)
            val gain = (MAG_REF_DB / (mag[i] + 1e-6)).coerceIn(GAIN_MIN, GAIN_MAX)
            val norm = ((dev * gain + OFFSET_DB) / SPAN_DB).coerceAtLeast(0.0)
            out[i] = (1.0 - exp(-SOFT_KNEE * norm)).toFloat()
        }
        return out
    }
}
