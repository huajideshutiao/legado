package io.legado.app.ui.main

import android.content.Context
import android.graphics.Matrix
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import io.legado.app.ui.root.PageTransform
import io.legado.app.ui.root.RouteTransitionSampler
import io.legado.app.ui.root.TransitionRole

/**
 * 复用系统窗口转场动画的采样器: 从运行设备主题 (windowAnimationStyle) 读取系统 Activity
 * 转场动画资源 (activityOpenEnter/Exit、activityCloseEnter/Exit) 并逐帧采样, 定制 ROM
 * (MIUI/ColorOS 等) 的系统动画资源与插值器自动生效, 零参数复刻。
 *
 * 读取路径全为公开 API: android.R.attr.windowAnimationStyle 及 4 个动画属性
 * (javap android.R.attr 验证), AnimationUtils.loadAnimation, Animation.getTransformation。
 * 系统动画的多段组合 (alpha/scale 各带独立曲线与 startOffset) 与 fillBefore/fillAfter
 * 语义由系统 Animation 实现原样处理 (AOSP Animation.getTransformation 验证):
 * - 百分比轴心/尺寸 (如 pivot 50%) 经 initialize(width, height, ...) 解析 (AOSP
 *   ScaleAnimation.initialize 验证, 动画未挂 View 不会自动触发, 须显式调用)
 * - 采样时间 = 线性进度 × 时长, startTime 固定为 0, 进度单调推进即可
 * - progress=0 → fillBefore 输出 from 值 (起始位); progress=1 时动画 expired
 *   (mMore=false) 不再输出变换 (AOSP getTransformation 验证), 采样会得到新建单位矩阵,
 *   故进度 clamp 到 0.9999, 末帧输出近似终态 (fillAfter 语义由系统实现处理)
 * - 动画时长 = computeDurationHint (含 startOffset) × 系统动画时长缩放 (Settings.Global,
 *   与窗口系统行为一致), scale=0 时动画层 duration=0 直接瞬切
 *
 * 几何精确性: 系统转场动画为 scale (pivot 50%) + translate, 采样矩阵已烘焙轴心补偿
 * (MTRANS = tx + pivot×(1-s)), 动画层以 transformOrigin=左上角 + scaleX/Y + translationX/Y
 * 渲染与系统矩阵逐点等价 (AOSP 矩阵推导验证)。
 */
class SystemRouteTransitionSampler private constructor(
    private val openEnter: Animation,
    private val openExit: Animation,
    private val closeEnter: Animation,
    private val closeExit: Animation,
    private val animationScale: () -> Float,
) : RouteTransitionSampler {

    override val pushDurationMillis: Int
        get() = scaledDuration(openEnter, openExit)

    override val popDurationMillis: Int
        get() = scaledDuration(closeEnter, closeExit)

    override fun sample(
        role: TransitionRole,
        progress: Float,
        width: Float,
        height: Float,
    ): PageTransform {
        val anim = when (role) {
            TransitionRole.NewPage -> openEnter
            TransitionRole.OldPage -> openExit
            TransitionRole.TargetPage -> closeEnter
            TransitionRole.OutgoingPage -> closeExit
        }
        val scale = animationScale().coerceAtLeast(0f)
        if (scale <= 0f) {
            // 系统动画关闭: 动画层 duration=0 瞬切, 此处仅防御性返回终态
            return PageTransform()
        }
        val duration = (anim.computeDurationHint() * scale).toLong().coerceAtLeast(1L)
        // 系统动画的百分比轴心/尺寸 (如 pivot 50%) 只在 initialize(width, height, ...) 中
        // 经 resolveSize 解析 (AOSP ScaleAnimation 验证), 动画未挂 View 不会自动触发,
        // 每帧显式 initialize (内部 reset, 状态由采样时间驱动) + startTime 固定 0:
        // 采样时间 = 线性进度 × 时长, 动画层保证单调递增 (段与段之间切换动画实例, 各自从 0 重新采样);
        // progress=1 时系统动画 expired (mMore=false) 不再输出变换, 采样结果会是新建的单位矩阵,
        // 旧页/出栈页 (openExit/closeExit) 末帧跳回起始态闪一帧, 故进度 clamp 到 0.9999 让末帧输出近似终态
        val time = ((progress.coerceIn(0f, 0.9999f)) * duration).toLong()
        anim.initialize(width.toInt(), height.toInt(), width.toInt(), height.toInt())
        anim.startTime = 0L
        val out = Transformation()
        anim.getTransformation(time, out)
        val values = FloatArray(9)
        out.matrix.getValues(values)
        // 矩阵已烘焙缩放轴心补偿, 动画层 transformOrigin 取左上角即逐点等价
        return PageTransform(
            alpha = out.alpha,
            scaleX = values[Matrix.MSCALE_X],
            scaleY = values[Matrix.MSCALE_Y],
            translationX = values[Matrix.MTRANS_X],
            translationY = values[Matrix.MTRANS_Y],
        )
    }

    private fun scaledDuration(vararg anims: Animation): Int {
        val scale = animationScale().coerceAtLeast(0f)
        if (scale <= 0f) return 0
        // computeDurationHint = max(startOffset + duration), 含子段 startOffset
        // (Animation.getDuration 只含 duration 不含 offset, 用 hint 才覆盖全部子段结束时刻)
        return (anims.maxOf { it.computeDurationHint() } * scale).toInt().coerceAtLeast(1)
    }

    companion object {
        /**
         * 从当前主题读取系统转场动画并创建采样器; 动画资源缺失 (极端定制系统未提供
         * 标准转场资源) 时返回 null, 动画层回退参数化 spec。
         */
        fun create(context: Context, animationScale: () -> Float): SystemRouteTransitionSampler? {
            return runCatching {
                val theme = context.theme
                // 1. 主题 → windowAnimationStyle (系统/ROM 主题链继承后的值)
                val styleTa = theme.obtainStyledAttributes(
                    intArrayOf(android.R.attr.windowAnimationStyle)
                )
                val windowAnimStyle = styleTa.getResourceId(0, 0)
                styleTa.recycle()
                if (windowAnimStyle == 0) return null
                // 2. style → 4 个转场动画资源
                val animAttrs = intArrayOf(
                    android.R.attr.activityOpenEnterAnimation,
                    android.R.attr.activityOpenExitAnimation,
                    android.R.attr.activityCloseEnterAnimation,
                    android.R.attr.activityCloseExitAnimation,
                )
                val animTa = theme.obtainStyledAttributes(windowAnimStyle, animAttrs)
                val resIds = IntArray(animAttrs.size) { animTa.getResourceId(it, 0) }
                animTa.recycle()
                if (resIds.any { it == 0 }) return null
                // 3. 加载系统动画实例 (含系统插值器/多段组合, ROM 定制自动生效)
                val anims = resIds.map { AnimationUtils.loadAnimation(context, it) }
                SystemRouteTransitionSampler(
                    anims[0], anims[1], anims[2], anims[3], animationScale
                )
            }.getOrNull()
        }
    }
}
