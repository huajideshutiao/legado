package io.legado.app.help.config

/**
 * 鸿蒙 actual: Kotlin/Native 侧没有 configuration API, 无法同步探测。
 *
 * 系统深色由 ArkTS 宿主 (EntryAbility.onCreate 取初值 / onConfigurationUpdate 变更) 经
 * platformEvent 通道推送到 [NativeSystemTheme.update], 见 PlatformEventBridge.emitColorMode。
 */
internal actual fun probeSystemNightMode(): Boolean? = null
