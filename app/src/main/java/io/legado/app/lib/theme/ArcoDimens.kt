@file:Suppress("unused")

package io.legado.app.lib.theme

import android.content.Context
import androidx.fragment.app.Fragment
import io.legado.app.R
import splitties.init.appCtx

/**
 * Arco Design 尺寸体系扩展属性。
 *
 * 通过 [appCtx] 全局缓存 dp→px（同设备 density 恒定，进程内不变，无需失效）。
 * [lazy] 默认 SYNCHRONIZED 模式，线程安全，等价于 ThemeStore 的 @Volatile + synchronized 双检锁。
 *
 * receiver Context 仅用于调用风格统一（与 [MaterialValueHelper] 的 accentColor 等保持一致），
 * 值不依赖具体 Context，统一取自 ApplicationContext 的资源。
 *
 * 用法：
 * - `context.space.lg`、`view.context.radius.default`、`fragment.stroke.thin`
 * - Float 形参（如 GradientDrawable.cornerRadius）用 `xxx.radius.defaultF`
 *
 * @see MaterialValueHelper
 */

// ===== Context 扩展（委托单例，receiver 仅用于调用风格统一） =====

val Context.space: ArcoSpacing get() = ArcoSpacing
val Context.radius: ArcoRadius get() = ArcoRadius
val Context.viewHeight: ArcoViewHeight get() = ArcoViewHeight
val Context.stroke: ArcoStroke get() = ArcoStroke

// ===== Fragment 扩展（委托 requireContext()） =====

val Fragment.space: ArcoSpacing get() = requireContext().space
val Fragment.radius: ArcoRadius get() = requireContext().radius
val Fragment.viewHeight: ArcoViewHeight get() = requireContext().viewHeight
val Fragment.stroke: ArcoStroke get() = requireContext().stroke

// ===== 单例 + lazy 缓存（dimen 值进程内不变，首次访问求值后永不失效） =====

/** Arco Design 间距体系：xs=4 / default=8 / md=12 / lg=16 / max=20 dp */
object ArcoSpacing {
    val xs: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_spacing_xs) }
    val default: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_spacing_default) }
    val md: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_spacing_md) }
    val lg: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_spacing_lg) }
    val max: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_spacing_max) }
}

/** Arco Design 圆角体系：sm=4 / default=8 / lg=16 dp；Float 别名复用 Int 缓存 */
object ArcoRadius {
    val sm: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_radius_sm) }
    val default: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_radius_default) }
    val lg: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_radius_lg) }

    /** Float 版（复用 Int 缓存），用于 GradientDrawable.cornerRadius 等 Float 形参 */
    val smF: Float get() = sm.toFloat()
    val defaultF: Float get() = default.toFloat()
    val lgF: Float get() = lg.toFloat()
}

/** Arco Design 视图高度体系：mini=24 / small=28 / default=32 / large=40 / xl=48 / max=56 dp */
object ArcoViewHeight {
    val mini: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_view_height_mini) }
    val small: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_view_height_small) }
    val default: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_view_height_default) }
    val large: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_view_height_large) }
    val xl: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_view_height_xl) }
    val max: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_view_height_max) }
}

/** Arco Design 描边体系：thin=1 / medium=2 dp */
object ArcoStroke {
    val thin: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_stroke_width_thin) }
    val medium: Int by lazy { appCtx.resources.getDimensionPixelSize(R.dimen.arco_stroke_width_medium) }
}
