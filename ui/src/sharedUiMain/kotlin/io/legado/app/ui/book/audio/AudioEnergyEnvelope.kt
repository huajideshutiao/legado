package io.legado.app.ui.book.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlin.math.exp

/**
 * 音频能量的显示包络: 歌词辉光与页面边缘光效共用同一套平滑。
 *
 * 自归一化 (快包络 / 慢均值) 已在播放侧完成, 这里只做一层显示平滑, 把逐缓冲的驱动值化成
 * 连续呼吸; 再做断供衰减 —— 暂停/缓冲/跳转时播放侧不再派发新值, 靠它自行回到 0。
 *
 * 三个字段都是快照状态: 绘制期读取只失效重绘, 不触发重组。
 * [nowMs] 必须与写入 [energyAtMs] 的时钟同源 (歌词用播放位置, 边缘光效用单调时钟)。
 */
internal class AudioEnergyEnvelope(
    private val attackMs: Float = ATTACK_MS,
    private val releaseMs: Float = RELEASE_MS,
) {

    companion object {
        /** 显示平滑时间常数 (ms): 上升跟得上瞬态 (10~20ms 级), 下降不拖尾 */
        const val ATTACK_MS = 25f
        const val RELEASE_MS = 180f

        /** 管道静默超过此间隔才开始衰减 (ms): 正常派发间隔远小于它, 不会把阶梯锯成锯齿 */
        const val HOLD_MS = 120

        /** 断供 (暂停/缓冲/跳转) 后的衰减时间常数 (ms) */
        const val DECAY_TAU_MS = 200f

        /** 低于这个能量视为静音, 帧循环降为低频轮询 (省电) */
        const val SILENT_THRESHOLD = 0.01f

        /** 静音段的时钟推进间隔 (ms) */
        const val IDLE_POLL_MS = 100L
    }

    /** 播放侧派发的原始能量 (0~1) */
    var energy by mutableFloatStateOf(0f)

    /** [energy] 的采样时刻 (单调毫秒), 供绘制期判断管道是否断供 */
    var energyAtMs by mutableLongStateOf(0L)

    /** 最终展示的能量 (0~1): 绘制期只读这一个值 */
    var smoothEnergy by mutableFloatStateOf(0f)

    fun reset() {
        energy = 0f
        energyAtMs = 0L
        smoothEnergy = 0f
    }

    /** 静音/暂停: 显示值归零, 避免恢复播放瞬间从陈旧的亮度跳下来 */
    fun resetDisplay() {
        smoothEnergy = 0f
    }

    /**
     * 绘制期取用当前驱动值: 正常播放时播放侧每 10~40ms 派发一次, 直接用最新值;
     * 只有静默超过 [HOLD_MS] 才开始指数衰减到 0, 不会把逐次派发的阶梯锯成锯齿。
     */
    fun decayedEnergy(nowMs: Long): Float {
        val base = energy
        if (base <= 0f) return 0f
        val idleMs = nowMs - energyAtMs - HOLD_MS
        return if (idleMs <= 0) base else base * exp(-idleMs / DECAY_TAU_MS)
    }

    /** 帧循环逐帧推进显示平滑 (非对称一阶) */
    fun advance(dtMs: Float, target: Float) {
        val tau = if (target > smoothEnergy) attackMs else releaseMs
        smoothEnergy += (target - smoothEnergy) * (1f - exp(-dtMs / tau))
    }
}
