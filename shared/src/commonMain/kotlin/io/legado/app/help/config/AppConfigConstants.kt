package io.legado.app.help.config

/**
 * AppConfig 键值常量 (从 app 端 AppConfig object 下沉)。
 *
 * 跨平台共享的 const val 定义, 各端引用 `AppConfigConstants.XXX` 替代原
 * `AppConfig.XXX`, 行为与 app 端原版逐字一致。范围值 (IntRange) 见
 * [AppConfigRanges] (同包)。
 *
 * 注意: 委托属性 (boolPref/intPref/stringPref 等) 与 SharedPreferences 监听
 * 仍留 app 端 AppConfig, shared 跨平台访问经 [AppConfigAccessor] /
 * [AppConfigProviders]。
 */
@Suppress("ConstPropertyName")
object AppConfigConstants {
    const val BOTTOM_BAR_HEIGHT_MIN = 36
    const val BOTTOM_BAR_HEIGHT_MAX = 80
    const val BOTTOM_BAR_HEIGHT_DEFAULT = 50
    const val BOTTOM_BAR_ICON_MIN = 18
    const val BOTTOM_BAR_ICON_MAX = 36
    const val BOTTOM_BAR_ICON_DEFAULT = 24
    const val BOTTOM_BAR_LABEL_DEFAULT = 0
    const val defaultSpeechRate = 5
}
