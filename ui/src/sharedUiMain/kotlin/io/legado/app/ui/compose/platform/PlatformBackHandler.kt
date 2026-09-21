package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 跨平台系统返回键拦截 (对照 app 端 `androidx.activity.compose.BackHandler`)。
 *
 * - Android: 委托 `androidx.activity.compose.BackHandler`, 拦截系统返回键/返回手势
 * - 鸿蒙: 注册到 ArkUI `OnBackPressedDispatcher` (见 Ohos actual)
 * - 桌面 JVM / iOS: 无系统返回键, actual 为 no-op; 这两端的页面级返回拦截要经
 *   [AppBackHandler] 注册进统一返回链 (桌面 ESC → `performBack`), 本函数对其无效
 *
 * 页面内的子状态返回一律用 [AppBackHandler], 只用本函数会在无系统返回键的端变成"进得去退不出"。
 *
 * shared/sharedUiMain 未引入 activity-compose 依赖, 故走 expect/actual 抽离。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
