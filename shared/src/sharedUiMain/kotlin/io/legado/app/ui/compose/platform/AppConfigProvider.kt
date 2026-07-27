package io.legado.app.ui.compose.platform

/**
 * 跨平台 AppConfig provider。
 * Android actual 包装 app.help.config.AppConfig; 桌面/iOS/鸿蒙 actual 提供本地实现。
 *
 * 仅暴露 AppTheme/A 类 Composable 在 commonMain 用到的字段（当前为 isEInkMode），
 * 其余字段按需扩展，避免一次性抽空 AppConfig。
 */
interface AppConfigProvider {
    /** 对应 AppConfig.isEInkMode (themeMode == "3") */
    val isEInkMode: Boolean

    /** 对应 AppConfig.isNightTheme，不根据背景色亮度推断。 */
    val isNightTheme: Boolean
}
