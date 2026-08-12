package io.legado.app.utils

/**
 * 当前是否为 Android 平台。
 *
 * 用于区分"Room 失效推送可靠性"差异 (2026-08 实证):
 * - Android (AndroidSQLiteDriver + 系统 SQLite): @Query UPDATE/@Update 失效推送正常
 *   (Android 12 / Android 16 双设备最小实验 5/5 全触发)
 * - 桌面 (BundledSQLiteDriver + SQLite 3.50.1): UPDATE 类写操作不触发失效重发,
 *   书架等 UI 需事件兜底强化
 * - iOS/鸿蒙: 未验证, 按不可靠处理 (兜底强化无害)
 */
internal expect val isAndroidPlatform: Boolean
