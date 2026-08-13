package io.legado.app.ui.book.read.page

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FloatDecayAnimationSpec
import androidx.compose.animation.core.generateDecayAnimationSpec
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign

/**
 * AOSP `Scroller.fling` 惯性滚动（SplineOverScroller 样条段）物理公式的纯 Kotlin 复刻。
 *
 * 权威来源（aosp-mirror/platform_frameworks_base，master）：
 * - core/java/android/widget/Scroller.java（fling/getSpline* 公式、FLING_MODE 位置曲线）
 * - core/java/android/widget/OverScroller.java（SplineOverScroller.update() SPLINE 态）
 * - core/java/android/view/ViewConfiguration.java（SCROLL_FRICTION、MINIMUM_FLING_VELOCITY）
 * - core/java/android/hardware/SensorManager.java（GRAVITY_EARTH）
 * - core/res/res/values/config.xml（config_viewMinFlingVelocity = 50dp）
 *
 * 目的：原版 legado 用 `Scroller.fling` 惯性滚动（衰减曲线 DECELERATION_RATE =
 * ln(0.78)/ln(0.9)），Compose `splineBasedDecay` 的曲线（AndroidFlingDecayRate=-4.2）与
 * 之不同；本类提供与 AOSP Scroller 逐行对应的纯物理计算，供滚动 delegate 的惯性
 * 增量驱动模式（对照原版 computeScroll → currY 逐帧增量）接入。
 *
 * 单位约定：
 * - velocity：px/s，带方向符号（原版 fling 传入 `mVelocity.yVelocity`，
 *   VelocityTracker.computeCurrentVelocity(1000) 的单位；Compose VelocityTracker
 *   calculateVelocity() 同为 px/s）
 * - density：DisplayMetrics.density（用于 ppi = density * 160 与 50dp/s 门控换算）
 * - 位移：px，相对 fling 起点的增量（不是绝对坐标），符号随速度方向
 *
 * 与原版 legado 调用方式的对应：
 * ```
 * // ScrollPageDelegate.onAnimStart (原版):
 * fling(0, touchY.toInt(), 0, mVelocity.yVelocity.toInt(),
 *       0, 0, -10 * viewHeight, 10 * viewHeight)
 * // 本类等价物:
 * SplineFling(initialVelocity = mVelocity.yVelocity, density = 屏幕密度)
 * ```
 * 原版每次手势 DOWN 都 abortAnim()，UP 的 fling 是唯一 fling 调用点，Scroller.fling
 * 的 flywheel 分支（mFlywheel && !mFinished）永不命中，故本类不复刻 flywheel。
 * 原版 minY/maxY = ±10 * viewHeight 只钳制终点；现有 onFling 的 travelLimit 钳制
 * （±10 屏）语义相同，钳制逻辑留在 delegate 侧（依赖 viewHeight），本类保持纯物理。
 *
 * 与 AOSP 的对应关系（方法级）：
 *
 * | SplineFling                          | AOSP                                                       |
 * | ---                                  | ---                                                        |
 * | companion 常量 (DECELERATION_RATE 等) | Scroller.java:101-111                                      |
 * | companion init (样条采样表二分求逆)     | Scroller.java:121-151 static 块                             |
 * | physicalCoeff (0.84 look-and-feel)    | Scroller.java:183/187/202-205 computeDeceleration(0.84f)   |
 * | splineDeceleration(velocity)          | Scroller.java:481-483 getSplineDeceleration                |
 * | splineFlingDuration(velocity)         | Scroller.java:485-488 getSplineFlingDuration               |
 * | splineFlingDistance(velocity)         | Scroller.java:491-494 getSplineFlingDistance               |
 * | totalDistance / durationMs            | OverScroller.java:747-778 fling()（mSplineDistance/mDuration）|
 * | positionAt(elapsedMs)                 | OverScroller.java:907-957 update() SPLINE 态               |
 * | velocityAt(elapsedMs)                 | 同上（mCurrVelocity = velocityCoef * dist / dur * 1000）   |
 * | isFinished(elapsedMs)                 | Scroller.java:307-358 computeScrollOffset 结束分支          |
 * | minimumFlingVelocity(density)         | ViewConfiguration.getScaledMinimumFlingVelocity() ← 50dp   |
 * | DEFAULT_FRICTION                      | ViewConfiguration.java:275 SCROLL_FRICTION = 0.015f        |
 *
 * 有意保留的差异（均为不影响曲线形状的表示层差异，详见各成员注释）：
 * 1. totalDistance/positionAt 用 Float：AOSP 内部 mSplineDistance 截断为 Int、
 *    每帧 Math.round 到 Int px；KMP 管线为 Float，保留精度（每帧与 AOSP 误差 ≤0.5px）
 * 2. 最小 fling 速度门控内置：AOSP 由调用方用 getScaledMinimumFlingVelocity() 判，
 *    本类在构造期判定并返回 isStarted=false / 总位移 0 / durationMs=0
 * 3. 不复刻 overscroll 回弹（BALLISTIC/CUBIC 态）与终点钳制后的 adjustDuration
 *    （SPLINE_TIME 表保留备用）：legado 的 fling 终点钳制语义由 delegate 的
 *    travelLimit（±10 屏）承担
 *
 * 纯物理计算类：不依赖 Compose 状态、不持协程，可在 commonMain/sharedUiMain 编译。
 */
class SplineFling(
    /** 松手瞬间的滚动速度（px/s，带方向符号；对应原版 fling 的 mVelocity.yVelocity） */
    val initialVelocity: Float,
    /** 屏幕密度（DisplayMetrics.density），用于 ppi 与 50dp/s 最小速度门控换算 */
    density: Float,
    /** 摩擦系数（无量纲），默认同 ViewConfiguration.getScrollFriction() */
    val friction: Float = DEFAULT_FRICTION,
) {

    // region AOSP 公式复刻（构造期一次算完，之后只读）

    /**
     * Scroller.java:187 `mPhysicalCoeff = computeDeceleration(0.84f)`（look and feel
     * tuning），computeDeceleration = GRAVITY_EARTH * 39.37f(inch/meter) * ppi * friction
     * （Scroller.java:202-205），其中 ppi = density * 160（Scroller.java:183）。
     * 全部 Float 逐级左结合运算，与 AOSP 一致。
     */
    private val physicalCoeff: Float =
        GRAVITY_EARTH * GRAVITY_EARTH_MULTIPLIER * (density * PPI_BASE) * LOOK_AND_FEEL_TUNING

    /**
     * 是否启动惯性：|velocity| >= 最小 fling 速度（50dp/s × density → px/s）。
     *
     * AOSP 该门控由调用方执行（ViewConfiguration.getScaledMinimumFlingVelocity()，
     * 50dp 见 config.xml:3002 config_viewMinFlingVelocity）；本类按接入要求内置：
     * 低于门控或速度为 0（含 NaN）→ 不启动，总位移 0、时长 0、isFinished 恒 true。
     */
    val isStarted: Boolean = abs(initialVelocity) >= minimumFlingVelocity(density)

    /**
     * 惯性总位移（px，带符号）。AOSP: fling() 中
     * `mSplineDistance = (int)(getSplineFlingDistance(velocity) * Math.signum(velocity))`
     * （OverScroller.java:769）。本类保留 Float 不截断为 Int（与 AOSP 相差 <1px，
     * 曲线形状完全一致，仅总量子化精度差异）。
     */
    val totalDistance: Float =
        if (isStarted) (splineFlingDistance(abs(initialVelocity)) * sign(initialVelocity)).toFloat()
        else 0f

    /** 惯性总时长（ms）。AOSP: getSplineFlingDuration（Scroller.java:485-488），SPLINE 态下即总时长。 */
    val durationMs: Int = if (isStarted) splineFlingDuration(abs(initialVelocity)) else 0

    // endregion

    // region 位置/速度曲线（update() SPLINE 态）

    /**
     * 当前时刻相对起点的位移（px，带符号）。
     *
     * 对应 OverScroller.java:907-957 `SplineOverScroller.update()` 的 SPLINE 态：
     * - currentTime == 0 → 提前返回，位置保持起点（位移 0）
     * - currentTime in (0, duration] → distance = distanceCoef * mSplineDistance
     * - currentTime > duration → 结束，位置 = 总位移
     *
     * @param elapsedMs 相对 fling 起点的毫秒数（AOSP 的 currentTime = now - mStartTime）
     */
    fun positionAt(elapsedMs: Long): Float {
        if (!isStarted || durationMs <= 0) return 0f
        if (elapsedMs <= 0L) return 0f
        if (elapsedMs >= durationMs) return totalDistance
        return splineCoefficients(elapsedMs).first * totalDistance
    }

    /**
     * 当前时刻的速度（px/s）。
     *
     * 对应 OverScroller.java:936 `mCurrVelocity = velocityCoef * mSplineDistance /
     * mSplineDuration * 1000.0f`。起速为 [initialVelocity]，结束（t>=1）为 0。
     * 供 delegate 命中边界时估算剩余速度（对照原版 notifyEdgeReached 取 mCurrVelocity）。
     */
    fun velocityAt(elapsedMs: Long): Float {
        if (!isStarted || durationMs <= 0) return 0f
        if (elapsedMs <= 0L) return initialVelocity
        if (elapsedMs >= durationMs) return 0f
        return splineCoefficients(elapsedMs).second * totalDistance / durationMs * 1000f
    }

    /**
     * 惯性是否已结束。对应 Scroller.java:307-358 computeScrollOffset：
     * timePassed >= mDuration → 位置置为终点、mFinished = true。
     */
    fun isFinished(elapsedMs: Long): Boolean = !isStarted || elapsedMs >= durationMs

    /**
     * 样条查表（update() SPLINE 态，OverScroller.java:921-935 / Scroller.java:320-336）：
     * ```
     * t = currentTime / duration
     * index = (int)(NB_SAMPLES * t)
     * distanceCoef = 1, velocityCoef = 0
     * if (index < NB_SAMPLES) {
     *     t_inf = index / NB_SAMPLES; t_sup = (index + 1) / NB_SAMPLES
     *     d_inf = SPLINE_POSITION[index]; d_sup = SPLINE_POSITION[index + 1]
     *     velocityCoef = (d_sup - d_inf) / (t_sup - t_inf)
     *     distanceCoef = d_inf + (t - t_inf) * velocityCoef
     * }
     * ```
     * 返回 (distanceCoef, velocityCoef)。全部 Float 运算（AOSP 同）。
     */
    private fun splineCoefficients(elapsedMs: Long): Pair<Float, Float> {
        val t = elapsedMs.toFloat() / durationMs
        val index = (NB_SAMPLES * t).toInt()
        var distanceCoef = 1f
        var velocityCoef = 0f
        if (index < NB_SAMPLES) {
            val tInf = index.toFloat() / NB_SAMPLES
            val tSup = (index + 1).toFloat() / NB_SAMPLES
            val dInf = SPLINE_POSITION[index]
            val dSup = SPLINE_POSITION[index + 1]
            velocityCoef = (dSup - dInf) / (tSup - tInf)
            distanceCoef = dInf + (t - tInf) * velocityCoef
        }
        return Pair(distanceCoef, velocityCoef)
    }

    // endregion

    // region AOSP getSpline* 三公式（Double 版，AOSP 源码即 double）

    /** Scroller.java:481-483 `getSplineDeceleration`（float 运算后转入 ln(double)，Kotlin 同序） */
    private fun splineDeceleration(velocity: Float): Double =
        ln((INFLEXION * velocity / (friction * physicalCoeff)).toDouble())

    /** Scroller.java:485-488 `getSplineFlingDuration`（返回 ms，int 截断同 AOSP） */
    private fun splineFlingDuration(velocity: Float): Int =
        (1000.0 * exp(splineDeceleration(velocity) / DECEL_MINUS_ONE)).toInt()

    /**
     * Scroller.java:491-494 `getSplineFlingDistance`（无符号；AOSP 由调用方乘
     * Math.signum(velocity)，见 [totalDistance]）
     */
    private fun splineFlingDistance(velocity: Float): Double {
        val l = splineDeceleration(velocity)
        return friction * physicalCoeff * exp(DECELERATION_RATE / DECEL_MINUS_ONE * l)
    }

    // endregion

    companion object {
        // region 常量与样条采样表（逐项对应 AOSP 源码，标注行号）

        /**
         * Scroller.java:101 `DECELERATION_RATE = (float)(ln(0.78) / ln(0.9))`。
         * 注意 AOSP 是 float 截断后再参与后续 double 运算（decelMinusOne），
         * 此处同样先转 Float 再转 Double，与 AOSP 逐位一致。
         */
        private val DECELERATION_RATE: Float = (ln(0.78) / ln(0.9)).toFloat()

        /** Scroller.java:485 `decelMinusOne = DECELERATION_RATE - 1.0`（float 截断值 - 1.0，double） */
        private val DECEL_MINUS_ONE: Double = DECELERATION_RATE - 1.0

        /** Scroller.java:103 INFLEXION = 0.35f（张力线交于 (INFLEXION, 1)） */
        private const val INFLEXION = 0.35f

        /** Scroller.java:104-105 样条张力常量 */
        private const val START_TENSION = 0.5f
        private const val END_TENSION = 1.0f

        /** Scroller.java:106-107 */
        private val P1: Float = START_TENSION * INFLEXION
        private val P2: Float = 1.0f - END_TENSION * (1.0f - INFLEXION)

        /** Scroller.java:109 样条采样数 */
        private const val NB_SAMPLES = 100

        /** SensorManager.java:279 GRAVITY_EARTH = 9.80665f（g，m/s^2） */
        private const val GRAVITY_EARTH = 9.80665f

        /** Scroller.java:204 39.37f = inch/meter */
        private const val GRAVITY_EARTH_MULTIPLIER = 39.37f

        /** Scroller.java:183 mPpi = density * 160.0f 的基准 PPI */
        private const val PPI_BASE = 160f

        /** Scroller.java:187 computeDeceleration(0.84f) 的 look and feel tuning 系数 */
        private const val LOOK_AND_FEEL_TUNING = 0.84f

        /** ViewConfiguration.java:275 SCROLL_FRICTION = 0.015f（getScrollFriction()） */
        const val DEFAULT_FRICTION = 0.015f

        /** ViewConfiguration.java:240 MINIMUM_FLING_VELOCITY = 50（dp/s；config.xml:3002 50dp） */
        private const val MINIMUM_FLING_VELOCITY_DP = 50f

        /**
         * Scroller.java:121-151 static 块：对每格 alpha 二分求逆生成
         * SPLINE_POSITION / SPLINE_TIME 采样表（1E-5 收敛、x_min/y_min 跨迭代携带，
         * 逐行对照）。SPLINE_TIME 供 AOSP adjustDuration（终点钳制后时长折算）使用，
         * 本类未复刻钳制折算，表保留备用。
         */
        private val SPLINE_POSITION = FloatArray(NB_SAMPLES + 1)
        private val SPLINE_TIME = FloatArray(NB_SAMPLES + 1)

        init {
            var xMin = 0.0f
            var yMin = 0.0f
            for (i in 0 until NB_SAMPLES) {
                val alpha = i.toFloat() / NB_SAMPLES

                var xMax = 1.0f
                var x = 0.0f
                var tx = 0.0f
                var coef = 0.0f
                while (true) {
                    x = xMin + (xMax - xMin) / 2.0f
                    coef = 3.0f * x * (1.0f - x)
                    tx = coef * ((1.0f - x) * P1 + x * P2) + x * x * x
                    if (abs(tx - alpha) < 1E-5) break
                    if (tx > alpha) xMax = x else xMin = x
                }
                SPLINE_POSITION[i] = coef * ((1.0f - x) * START_TENSION + x) + x * x * x

                var yMax = 1.0f
                var y = 0.0f
                var dy = 0.0f
                while (true) {
                    y = yMin + (yMax - yMin) / 2.0f
                    coef = 3.0f * y * (1.0f - y)
                    dy = coef * ((1.0f - y) * START_TENSION + y) + y * y * y
                    if (abs(dy - alpha) < 1E-5) break
                    if (dy > alpha) yMax = y else yMin = y
                }
                SPLINE_TIME[i] = coef * ((1.0f - y) * P1 + y * P2) + y * y * y
            }
            SPLINE_POSITION[NB_SAMPLES] = 1.0f
            SPLINE_TIME[NB_SAMPLES] = 1.0f
        }

        /**
         * ViewConfiguration.getScaledMinimumFlingVelocity()：50dp/s × density → px/s。
         * （config_viewMinFlingVelocity = 50dp 经 getDimensionPixelSize 缩放；
         * 废弃的 getMinimumFlingVelocity() 返回原始 50。）
         */
        fun minimumFlingVelocity(density: Float): Float = MINIMUM_FLING_VELOCITY_DP * density

        // endregion
    }
}

/**
 * 把 [SplineFling] 适配为 Compose [DecayAnimationSpec]（复用官方 animateDecay 动画
 * 框架的帧调度/取消/快照管理，对照原版 Scroller.computeScrollOffset 的轮询驱动）。
 *
 * 适配口径（对照 compose animation-core 官方接口，权威签名见本机
 * animation-core-1.11.4-sources 的 FloatDecayAnimationSpec.kt）：
 * - [FloatDecayAnimationSpec.getValueFromNanos] → [SplineFling.positionAt]
 *   （playTimeNanos 折算 ms，与 AOSP update() 的 currentTime 同为毫秒粒度；
 *   未启动（|v| < 50dp/s 门控）时恒返 initialValue，配合 duration=0 不产生任何位移）
 * - [FloatDecayAnimationSpec.getDurationNanos] → [SplineFling.durationMs]：
 *   未启动 = 0 → animateDecay 框架立即结束（对照 AOSP Scroller.fling 的
 *   mMinimumVelocity 判定 → mFinished 不滚动）
 * - [FloatDecayAnimationSpec.getTargetValue] → initialValue + totalDistance
 * - [FloatDecayAnimationSpec.getVelocityFromNanos] → [SplineFling.velocityAt]
 *   （框架的每帧 velocity/lastVelocity 消费）
 * - [FloatDecayAnimationSpec.absVelocityThreshold] → 50dp/s 门控值（框架只在
 *   seekToVelocity 等路径使用，本接入一次性动画不依赖）
 *
 * @param initialVelocity 松手瞬间滚动速度（px/s，带方向）
 * @param density 屏幕密度（ppi 与门控换算，组合期注入）
 */
internal fun splineFlingDecaySpec(
    initialVelocity: Float,
    density: Float,
): DecayAnimationSpec<Float> {
    val fling = SplineFling(initialVelocity, density)
    return (object : FloatDecayAnimationSpec {
        override val absVelocityThreshold: Float = SplineFling.minimumFlingVelocity(density)

        override fun getValueFromNanos(
            playTimeNanos: Long,
            initialValue: Float,
            initialVelocity: Float,
        ): Float {
            if (!fling.isStarted) return initialValue
            return initialValue + fling.positionAt(playTimeNanos / 1_000_000L)
        }

        override fun getDurationNanos(initialValue: Float, initialVelocity: Float): Long =
            fling.durationMs.toLong() * 1_000_000L

        override fun getVelocityFromNanos(
            playTimeNanos: Long,
            initialValue: Float,
            initialVelocity: Float,
        ): Float {
            if (!fling.isStarted) return 0f
            return fling.velocityAt(playTimeNanos / 1_000_000L)
        }

        override fun getTargetValue(initialValue: Float, initialVelocity: Float): Float =
            initialValue + fling.totalDistance
    }).generateDecayAnimationSpec()
}
