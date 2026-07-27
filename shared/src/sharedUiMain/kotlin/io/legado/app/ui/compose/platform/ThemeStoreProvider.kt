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
}
