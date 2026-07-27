package io.legado.app.ui.platform

/**
 * 跨平台字符串资源访问器。
 *
 * commonMain 用 expect 声明, 各平台 actual 提供:
 * - Android: 走 `context.getString(resId)`, 宿主启动时通过
 *   `registerSharedAppContext(appCtx)` 注入 ApplicationContext
 * - 桌面 JVM / iOS / 鸿蒙: 无 R.string 资源体系, 返回空串占位
 *
 * 供 commonMain 侧需要按 `Int resId` 取字符串的旧 widget/data 类(如 EditEntity)
 * 调用, 替代原 `appCtx.getString(resId)` (splitties 仅 Android 可用)。
 *
 * 注: 新 Compose 代码应优先用 [io.legado.app.ui.compose.platform.rememberString]
 * (key-based, 跨平台一致); 本函数仅用于无法避免 Int resId 的下沉旧代码。
 */
expect fun stringRes(resId: Int): String
