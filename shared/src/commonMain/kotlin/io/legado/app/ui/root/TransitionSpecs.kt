package io.legado.app.ui.root

/**
 * 转场插值器描述 (commonMain 无 compose 依赖, 用可描述形态承载平台插值器;
 * sharedUiMain 动画层经 [toComposeEasing] 集中映射为 compose Easing)。
 *
 * 预设覆盖系统插值器; 任意系统曲线可用 [CubicBezier] 控制点表达 (如
 * iOS kCAMediaTimingFunctionEaseInEaseOut、Windows Fluent standard), 接入新系统
 * 插值器只需新增预设或控制点, 动画框架无需改动。
 */
sealed interface TransitionEasing {
    /** 匀速 (系统 linear) */
    data object Linear : TransitionEasing

    /** Android 系统 @android:interpolator/fast_out_slow_in (系统转场/animator 默认插值器) */
    data object FastOutSlowIn : TransitionEasing

    /** Android 系统 decelerate_quad (系统 dialog_enter.xml 插值器) */
    data object DecelerateQuad : TransitionEasing

    /** Android 系统 accelerate_quad (系统 dialog_exit.xml 插值器) */
    data object AccelerateQuad : TransitionEasing

    /**
     * 任意三次贝塞尔曲线:
     * - iOS 系统转场曲线 kCAMediaTimingFunctionEaseInEaseOut = (0.42, 0, 0.58, 1)
     * - Windows Fluent motion 标准曲线 = (0.1, 0.9, 0.2, 1)
     */
    data class CubicBezier(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    ) : TransitionEasing
}

/**
 * 路由转场动画平台 spec (全局过渡动画平台化, 方案 A)。
 *
 * 动画仍由 shared LegadoApp 唯一注入点驱动, 本 spec 只承载
 * "平台动画形态参数", 由各端 [PlatformCapabilities] 按系统转场语义提供:
 * - Android: 系统 Activity 转场语义 (新页 slide_in_right 全宽滑入+fade_in, 旧页 fade_out
 *   不位移, 300ms + fast_out_slow_in), 时长运行时动态读系统动画时长缩放 (Settings.Global);
 * - iOS: UINavigationController push/pop 转场语义 (350ms, easeInEaseOut 曲线);
 * - desktop: 平台惯例 (Windows Fluent motion: 200ms + standard 曲线, 淡入+轻微位移);
 * - ohos 及其它未 override 端: 本文件默认值 (iOS 式 300ms)。
 *
 * 可扩展性: 新动画形态 (位移/淡入淡出/缩放组合、任意系统曲线) 只需扩展字段 + 平台
 * 提供者, shared 动画层按字段消费, 动画框架无需改动。
 */
data class RouteTransitionSpec(
    // ===== 前进 (push) =====
    /** 新页自右侧滑入宽度比例 (1f=全宽, Android 系统 slide_in_right 语义) */
    val newPageSlideFraction: Float,
    /** 旧页向左位移比例 (0f=不位移, Android 系统 fade_out 仅淡出) */
    val oldPageShiftFraction: Float,
    /** 新页淡入 (alpha 0→1, Android 系统 fade_in) */
    val newPageFadeIn: Boolean,
    /** 旧页淡出 (alpha 1→0, Android 系统 fade_out) */
    val oldPageFadeOut: Boolean,
    /** 新页缩放起点 (1f=无缩放; 缩放形态扩展预留) */
    val newPageScaleFrom: Float,
    val pushDurationMillis: Int,
    val pushEasing: TransitionEasing,
    // ===== 返回 (pop) =====
    /** 目标页自左侧滑回比例 (0f=不位移, Android 系统返回转场 fade 语义) */
    val targetPageSlideFraction: Float,
    /** 出栈页向右滑出比例 (1f=全宽, Android 系统 slide_out_right 语义) */
    val outgoingSlideFraction: Float,
    /** 目标页淡入 (Android 系统返回转场 fade_in) */
    val targetPageFadeIn: Boolean,
    /** 出栈页淡出 (Android 系统返回转场 fade_out) */
    val outgoingFadeOut: Boolean,
    /** 目标页缩放起点 (1f=无缩放) */
    val targetPageScaleFrom: Float,
    val popDurationMillis: Int,
    val popEasing: TransitionEasing,
)

/**
 * 对话框/底部弹层动画平台 spec。
 * 进入/退出时长与插值器按平台对话框转场语义提供 (Android 系统 dialog_enter.xml 200ms
 * decelerate_quad 中心缩放 0.96→1+淡入 / dialog_exit.xml 150ms accelerate_quad 淡出,
 * 时长受系统动画时长缩放影响); 几何形态由组件固定 (AppDialog=中心缩放, AppBottomSheetDialog=底部滑入)。
 */
data class DialogTransitionSpec(
    val enterDurationMillis: Int,
    val enterEasing: TransitionEasing,
    /** 进入缩放起点 (Android 系统 dialog_enter.xml scale 0.96) */
    val enterScaleFrom: Float,
    /** 进入是否淡入 (Android 系统 dialog_enter.xml fade_in) */
    val enterFadeIn: Boolean,
    val exitDurationMillis: Int,
    val exitEasing: TransitionEasing,
    /** 退出是否淡出 (Android 系统 dialog_exit.xml fade_out) */
    val exitFadeOut: Boolean,
)

/**
 * shared 默认路由转场 spec (iOS 式, 保持迁移前统一风格): 前进=新页全宽滑入+旧页左移 30%,
 * 返回=目标页自左 30% 滑回+出栈页全宽滑出, 300ms fast_out_slow_in, 无淡入淡出。
 * 未 override 的端 (ohos) 与回退路径使用。
 */
val DefaultRouteTransitionSpec = RouteTransitionSpec(
    pushDurationMillis = 300,
    pushEasing = TransitionEasing.FastOutSlowIn,
    newPageSlideFraction = 1f,
    oldPageShiftFraction = 0.3f,
    newPageFadeIn = false,
    oldPageFadeOut = false,
    newPageScaleFrom = 1f,
    popDurationMillis = 300,
    popEasing = TransitionEasing.FastOutSlowIn,
    targetPageSlideFraction = 0.3f,
    outgoingSlideFraction = 1f,
    targetPageFadeIn = false,
    outgoingFadeOut = false,
    targetPageScaleFrom = 1f,
)

/** shared 默认对话框动画 spec (沿用 Android 系统 dialog 动画资源语义, 各平台无强制规范) */
val DefaultDialogTransitionSpec = DialogTransitionSpec(
    enterDurationMillis = 200,
    enterEasing = TransitionEasing.DecelerateQuad,
    enterScaleFrom = 0.96f,
    enterFadeIn = true,
    exitDurationMillis = 150,
    exitEasing = TransitionEasing.AccelerateQuad,
    exitFadeOut = true,
)
