@file:Suppress("unused")

package io.legado.app.lib.theme

import android.content.Context
import androidx.fragment.app.Fragment
import io.legado.app.R

/**
 * Arco Design 尺寸体系扩展属性。
 *
 * receiver Context 用于调用资源，
 * 值依赖具体 Context 的资源。
 *
 * 用法：
 * - `context.space.lg`、`view.context.radius.default`、`fragment.stroke.thin`
 * - Float 形参（如 GradientDrawable.cornerRadius）用 `xxx.radius.defaultF`
 *
 * @see MaterialValueHelper
 */

// ===== Context 扩展 =====

val Context.space: ArcoSpacing get() = ArcoSpacing(this)
val Context.radius: ArcoRadius get() = ArcoRadius(this)
val Context.viewHeight: ArcoViewHeight get() = ArcoViewHeight(this)
val Context.stroke: ArcoStroke get() = ArcoStroke(this)

// ===== Fragment 扩展（委托 requireContext()） =====

val Fragment.space: ArcoSpacing get() = requireContext().space
val Fragment.radius: ArcoRadius get() = requireContext().radius
val Fragment.viewHeight: ArcoViewHeight get() = requireContext().viewHeight
val Fragment.stroke: ArcoStroke get() = requireContext().stroke

// ===== 包装类（dimen 值随 Context 资源获取） =====

/** Arco Design 间距体系：xs=4 / default=8 / md=12 / lg=16 / max=20 dp */
class ArcoSpacing(context: Context) {
    val xs: Int = context.resources.getDimensionPixelSize(R.dimen.arco_spacing_xs)
    val default: Int = context.resources.getDimensionPixelSize(R.dimen.arco_spacing_default)
    val md: Int = context.resources.getDimensionPixelSize(R.dimen.arco_spacing_md)
    val lg: Int = context.resources.getDimensionPixelSize(R.dimen.arco_spacing_lg)
    val max: Int = context.resources.getDimensionPixelSize(R.dimen.arco_spacing_max)
}

/** Arco Design 圆角体系：sm=4 / default=8 / lg=16 dp；Float 别名复用 Int 缓存 */
class ArcoRadius(context: Context) {
    val sm: Int = context.resources.getDimensionPixelSize(R.dimen.arco_radius_sm)
    val default: Int = context.resources.getDimensionPixelSize(R.dimen.arco_radius_default)
    val lg: Int = context.resources.getDimensionPixelSize(R.dimen.arco_radius_lg)

    /** Float 版，用于 GradientDrawable.cornerRadius 等 Float 形参 */
    val smF: Float get() = sm.toFloat()
    val defaultF: Float get() = default.toFloat()
    val lgF: Float get() = lg.toFloat()
}

/** Arco Design 视图高度体系：mini=24 / small=28 / default=32 / large=40 / xl=48 / max=56 dp */
class ArcoViewHeight(context: Context) {
    val mini: Int = context.resources.getDimensionPixelSize(R.dimen.arco_view_height_mini)
    val small: Int = context.resources.getDimensionPixelSize(R.dimen.arco_view_height_small)
    val default: Int = context.resources.getDimensionPixelSize(R.dimen.arco_view_height_default)
    val large: Int = context.resources.getDimensionPixelSize(R.dimen.arco_view_height_large)
    val xl: Int = context.resources.getDimensionPixelSize(R.dimen.arco_view_height_xl)
    val max: Int = context.resources.getDimensionPixelSize(R.dimen.arco_view_height_max)
}

/** Arco Design 描边体系：thin=1 / medium=2 dp */
class ArcoStroke(context: Context) {
    val thin: Int = context.resources.getDimensionPixelSize(R.dimen.arco_stroke_width_thin)
    val medium: Int = context.resources.getDimensionPixelSize(R.dimen.arco_stroke_width_medium)
}
