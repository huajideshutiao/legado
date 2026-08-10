package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 底部"强制系统手势区"高度（px，全窗坐标）。
 *
 * 仅 Android 有该概念（全面屏手势导航的 home 指示条那条带子，API 30+ 且手势导航时非 0，
 * 对应 `WindowInsets.mandatorySystemGestures` 的 bottom inset，指示条隐藏时也按占位算，
 * 即 `getInsetsIgnoringVisibility` 语义）；桌面 / iOS / 鸿蒙无此概念，恒 0。
 *
 * 阅读页触摸分发器用它拦截落在该带子内的手势（对照原版 ReadView.onTouchEvent 开头的
 * mandatorySystemGestures 判据），避免翻页动画与系统返回/回主页手势打架。
 */
@Composable
expect fun rememberMandatoryGestureBottomPx(): Int
