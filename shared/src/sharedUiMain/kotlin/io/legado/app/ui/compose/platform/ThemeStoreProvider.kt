package io.legado.app.ui.compose.platform

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.FileThemeConfigProvider
import io.legado.app.help.config.PreferenceProvider
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.currentNightTheme
import io.legado.app.help.config.resolveImagePath
import io.legado.app.lib.theme.ThemeStorePrefKeys

/**
 * 跨平台主题数据 provider。
 * Android actual 包装 app.lib.theme.ThemeStore + app.help.config.ThemeConfig.curBgImagePath;
 * 桌面/iOS/鸿蒙 actual 提供本地实现。
 *
 * 设计：颜色统一以 Compose [Color] 暴露（而非 Android ColorInt），把 Int→Color 转换
 * 收敛到平台 actual，commonMain 侧的 AppTheme 与 A 类 Composable 直接消费 Color。
 *
 * 只读：写入主题色统一走 [io.legado.app.help.config.ThemeConfigProvider]
 * （Android 端 ThemeConfig / 三端 FileThemeConfigProvider），本接口不再提供 applyColors ——
 * 曾四端各一份实现，与 FileThemeConfigProvider.saveThemeStore 写同一批 pref。
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
}

/**
 * 走 [PreferenceProviders] 的 [ThemeStoreProvider] 实现，桌面 / iOS / 鸿蒙共用。
 *
 * 三端曾各抄一份：键名、派生规则、日夜分支逐字相同，只有底层读写 API 不同 —— 而 iOS 的
 * NSUserDefaults 与鸿蒙的 JSON 文件本就已由各自的 [PreferenceProvider] 封装，没有差异可留。
 *
 * 兜底：持久层无记录时按当前日/夜取 [FileThemeConfigProvider] 的内置默认（源自原版
 * values / values-night），与 applyTheme 真正写入的值同一套 —— 三端原先各编一套硬编码色，
 * 同一个 app 会有两三种"默认夜间色"。
 */
open class SharedThemeStoreProvider : ThemeStoreProvider {

    override val accentColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_ACCENT_COLOR)
            ?: builtin(FileThemeConfigProvider.NIGHT_ACCENT, FileThemeConfigProvider.DAY_ACCENT)
    override val backgroundColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR)
            ?: builtin(FileThemeConfigProvider.NIGHT_BG, FileThemeConfigProvider.DAY_BG)
    override val bottomBackground: Color
        get() = readColor(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND)
            ?: builtin(FileThemeConfigProvider.NIGHT_BBG, FileThemeConfigProvider.DAY_BBG)
    override val statusBarColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR) ?: backgroundColor
    override val navigationBarColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR) ?: bottomBackground

    /** 对照 ThemeConfig.curBgImagePath: 按日/夜读相对引用并解析为绝对路径, 空白视为无壁纸 */
    override val bgImagePath: String?
        get() = resolveImagePath(
            prefsOrNull()?.getStringOrNull(if (isNight) PreferKey.bgImageN else PreferKey.bgImage)
                ?.takeUnless { it.isBlank() }
        )

    /** 按日/夜读背景图模糊键 (对照原版 bgImageBlurring) */
    override val bgImageBlur: Int
        get() = prefsOrNull()?.getInt(
            if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
            0,
        ) ?: 0

    /** 日/夜判定与业务层同源 (含「跟随系统」档), 影响兜底色与壁纸/模糊取键 */
    protected val isNight: Boolean
        get() = currentNightTheme()

    private fun builtin(night: Int, day: Int) = Color(if (isNight) night else day)

    private fun readColor(key: String): Color? =
        prefsOrNull()?.let { if (it.contains(key)) Color(it.getInt(key)) else null }

    /** PreferenceProviders 未注册时返回 null (测试/@Preview), 主流程宿主已先注册 */
    private fun prefsOrNull(): PreferenceProvider? =
        runCatching { PreferenceProviders.get() }.getOrNull()
}
