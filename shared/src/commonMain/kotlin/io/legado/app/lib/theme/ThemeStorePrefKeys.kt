package io.legado.app.lib.theme

/*
 * 主题 SharedPreferences 键常量下沉 (shared/commonMain)。
 *
 * 原 app 端 lib/theme/ThemeStorePrefKeys.kt 纯常量 object, 无 Android 依赖,
 * 下沉至此供 app/desktop/iOS/鸿蒙 各端复用。
 *
 * iOS 端 IosProviders.kt 原本地复制 KEY_ACCENT_COLOR/KEY_BACKGROUND_COLOR 等
 * (注释 "app 模块不可被 shared 反向依赖, 故本地常量"), 现改为直接引用本 object,
 * 消除复制。
 *
 * 跨模块同包名同 object 名跨模块引用, app 端 ThemeStore.kt 调用零改动
 * (同包名 `io.legado.app.lib.theme`, 无需 import)。
 *
 * @author Aidan Follestad (afollestad), Karim Abou Zeid (kabouzeid)
 */
object ThemeStorePrefKeys {

    const val CONFIG_PREFS_KEY_DEFAULT = "app_themes"

    const val KEY_PRIMARY_COLOR = "primary_color"
    const val KEY_ACCENT_COLOR = "accent_color"
    const val KEY_STATUS_BAR_COLOR = "status_bar_color"
    const val KEY_NAVIGATION_BAR_COLOR = "navigation_bar_color"

    const val KEY_TEXT_COLOR_PRIMARY = "text_color_primary"
    const val KEY_TEXT_COLOR_SECONDARY = "text_color_secondary"

    const val KEY_BACKGROUND_COLOR = "backgroundColor"
    const val KEY_BOTTOM_BACKGROUND = "bottomBackground"
}