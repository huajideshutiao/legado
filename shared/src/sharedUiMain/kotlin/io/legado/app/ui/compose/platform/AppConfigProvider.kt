package io.legado.app.ui.compose.platform

import io.legado.app.help.config.currentEInkMode
import io.legado.app.help.config.currentNightTheme

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

/**
 * 两值都取业务层 [io.legado.app.help.config.AppConfigAccessor]，桌面 / iOS / 鸿蒙共用
 * （三端曾各写一份, 且各自只判 `themeMode == "2"/"3"` 字面量, 漏掉「跟随系统」档）。
 *
 * Android 端不用本类: 它的 AppConfigProvider 直接包装 app 模块的 AppConfig 单例。
 */
open class SharedAppConfigProvider : AppConfigProvider {
    override val isEInkMode: Boolean get() = currentEInkMode()
    override val isNightTheme: Boolean get() = currentNightTheme()
}
