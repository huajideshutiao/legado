package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * [PlatformBackHandler] 的 iOS actual: no-op。
 *
 * 本端**没有任何系统返回通道** —— 既无物理/手势返回键, 也没有导航容器承载侧滑返回:
 * 宿主是 SwiftUI `WindowGroup { ContentView() }` 直接包一层
 * `UIViewControllerRepresentable` 呈现 Compose 根 VC (全仓无 `NavigationStack` /
 * `UINavigationController`), 页面栈完全由 shared [io.legado.app.ui.root.AppNavigator]
 * 自管。故本函数无处可挂, 返回一律不经过它。
 *
 * 后果 (调用方必须自行兜住, 见下): 页面内的子状态**没有**"返回键可退"这条退路。
 * - 需要返回语义的页面级拦截: 走 [AppBackHandler] / [BackLayerHandler] 注册进统一返回链
 *   (唯一触发源是外接键盘, 见 [io.legado.app.ui.compose.platform.handleBackKey]);
 * - 全屏 / 覆盖层等把顶栏一起隐掉的界面: 必须常驻一个**可见退出口**。既有先例是
 *   [io.legado.app.ui.root.PlatformCapabilities.supportsSystemBack] = false 驱动共享层
 *   常驻退出入口 (见视频播放页的全屏退出钮);
 * - 仅靠本函数拦返回的页面, 在 iOS 上就是"进得去退不出"。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: iOS 无系统返回通道 (无返回键, 也无导航容器承载侧滑返回)
}
