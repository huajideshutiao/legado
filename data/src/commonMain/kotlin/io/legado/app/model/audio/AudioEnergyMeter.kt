package io.legado.app.model.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * 音频能量驱动：感知响度做底色，低频能量包络做律动。
 *
 * - **底色**（50%）：K 加权 150ms 瞬时响度（LKFS）映射到以最近 30 秒响度为中心、
 *   上下 8dB 的区间，再取 0.5 次幂（Stevens 亮度指数范围）。绝对量，越响越亮。
 * - **律动**（50%）：低频段（20–320Hz）能量的快包络 ÷ 慢平均（MilkDrop/Butterchurn 的写法：
 *   快包络上升保留 0.2、下降 0.5，慢平均 0.992，均按 `keep^(dt*30/1000)` 换算帧率）。
 *   这是一条连续曲线，随鼓点起伏；不用离散的起音检测（会漏拍、会卡）。
 *
 * 没有频谱的端（桌面/鸿蒙）调用 [pushLevel]：用整体电平的线性值近似低频能量。
 */
class AudioEnergyMeter {

    companion object {
        /** 底色区间: 以最近 30 秒的长期响度为中心, 上下各 8dB */
        private const val BASE_HALF_RANGE_DB = 8.0
        private const val LONG_TERM_KEEP = 0.9995
        private const val BASE_POWER = 0.5

        /** MilkDrop 快包络: 上升 0.2 / 下降 0.5 (30fps 基准) */
        private const val ATTACK_KEEP = 0.2
        private const val RELEASE_KEEP = 0.5

        /** MilkDrop 慢平均: 0.992 (30fps 基准) */
        private const val LONG_AVG_KEEP = 0.992

        /**
         * 律动映射: 快包络/慢平均 的典型区间约 0.5~2.0, 线性映射到 0~1。
         * 区间取自 MilkDrop 的 att 定义 (avg/longAvg) 与静音钳制 (longAvg<0.001 时取 1)。
         */
        private const val PULSE_MIN_RATIO = 0.5
        private const val PULSE_RANGE = 1.5

        private const val BASE_WEIGHT = 0.5
        private const val PULSE_WEIGHT = 0.5

        /** 线性幅度转分贝; 静音钳到下限, 避免负无穷 */
        fun linearToDb(linear: Double): Double {
            if (linear <= 0.0) return -40.0
            return (20.0 * log10(linear)).coerceAtLeast(-40.0)
        }
    }

    private var elapsedMs = 0.0
    private var base = 0.0
    private var longTermLkfs = -20.0
    private var fast = 0.0
    private var longAvg = 0.0
    private var lastLevelDb = -40.0
    private var lastDrive = 0f

    fun reset() {
        elapsedMs = 0.0
        base = 0.0
        longTermLkfs = -20.0
        fast = 0.0
        longAvg = 0.0
        lastLevelDb = -40.0
        lastDrive = 0f
    }

    /**
     * 完整入口。
     *
     * @param dtMs 本帧覆盖的墙钟时长
     * @param lkfs K 加权瞬时响度
     * @param lowBand 低频段能量 (任意线性标度, 只需同源稳定)
     */
    fun pushFrame(dtMs: Double, lkfs: Double, lowBand: Double): Float {
        if (dtMs <= 0.0) return lastDrive
        elapsedMs += dtMs
        val frameScale = dtMs * 30.0 / 1000.0

        longTermLkfs += (lkfs - longTermLkfs) * (1.0 - LONG_TERM_KEEP.pow(frameScale))
        val floor = longTermLkfs - BASE_HALF_RANGE_DB
        val ceil = longTermLkfs + BASE_HALF_RANGE_DB
        val target = ((lkfs - floor) / (ceil - floor))
            .coerceIn(0.0, 1.0)
            .pow(BASE_POWER)
        val baseKeep = if (target > base) ATTACK_KEEP else RELEASE_KEEP
        base += (target - base) * (1.0 - baseKeep.pow(frameScale))

        val fastKeep = if (lowBand > fast) ATTACK_KEEP else RELEASE_KEEP
        fast += (lowBand - fast) * (1.0 - fastKeep.pow(frameScale))
        val longKeep = LONG_AVG_KEEP.pow(frameScale)
        longAvg = if (longAvg <= 0.0) lowBand else longAvg * longKeep + lowBand * (1.0 - longKeep)
        val ratio = if (longAvg > 1e-6) fast / longAvg else 1.0
        val pulse = ((ratio - PULSE_MIN_RATIO) / PULSE_RANGE).coerceIn(0.0, 1.0)

        lastDrive = (base * BASE_WEIGHT + pulse * PULSE_WEIGHT)
            .coerceIn(0.0, 1.0)
            .toFloat()
        return lastDrive
    }

    /** 无频谱端: 用整体电平的线性值近似低频能量 */
    fun pushLevel(dtMs: Double, levelDb: Double): Float {
        lastLevelDb = levelDb
        val linear = 10.0.pow(levelDb / 20.0)
        return pushFrame(dtMs, levelDb, linear)
    }
}
