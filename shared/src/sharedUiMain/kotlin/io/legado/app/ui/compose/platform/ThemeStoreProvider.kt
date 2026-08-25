package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 跨平台主题数据 provider。
 * Android actual 包装 app.lib.theme.ThemeStore + app.help.config.ThemeConfig.curBgImagePath;
 * 桌面/iOS/鸿蒙 actual 提供本地实现。
 *
 * 设计：颜色统一以 Compose [Color] 暴露（而非 Android ColorInt），把 Int→Color 转换
 * 收敛到平台 actual，commonMain 侧的 AppTheme 与 A 类 Composable 直接消费 Color。
 */
@Immutable
interface ThemeStoreProvider {
    val accentColor: Color
    val backgroundColor: Color
    val bottomBackground: Color
    val statusBarColor: Color
    val navigationBarColor: Color

    /** 对应 ThemeConfig.curBgImagePath, null 表示无壁纸 */
    val bgImagePath: String?

    /**
     * 当前背景图模糊半径 (px, 0=不模糊), 与 [bgImagePath] 同日夜。
     * 页面级壁纸层 ([io.legado.app.ui.root.WallpaperLayer]) 四端统一使用;
     * 原版 Android 的 stackBlur 改为 Compose Gaussian 模糊 (视觉近似)。
     */
    val bgImageBlur: Int
        get() = 0

    /**
     * 应用自定义主题色 (供 ThemeCustomizeDialog/ThemeListDialog 下沉后跨平台调用)。
     *
     * - Android actual: 包装 ThemeConfig.applyConfig + postEvent(RECREATE)
     * - 桌面/iOS/鸿蒙 actual: 写入本地持久化 (NSUserDefaults/java.util.prefs/PreferenceProvider)
     *
     * 注: 背景图功能不下沉 (与 desktop 一致), 本方法仅处理三色 + 日夜模式。
     */
    fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean)
}
