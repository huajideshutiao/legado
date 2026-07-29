package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Composable

/**
 * 跨平台系统返回键拦截 (对照 app 端 `androidx.activity.compose.BackHandler`)。
 *
 * - Android: 委托 `androidx.activity.compose.BackHandler`, 拦截系统返回键/返回手势
 * - 桌面 JVM / iOS / 鸿蒙: 无系统返回键概念 (桌面 ESC/Backspace 由
 *   [io.legado.app.ui.root.LegadoApp] 的 `handleBackKey` Modifier 统一处理),
 *   actual 为 no-op
 *
 * shared/sharedUiMain 未引入 activity-compose 依赖, 故走 expect/actual 抽离。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
