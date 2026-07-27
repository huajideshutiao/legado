package io.legado.app.ui.compose.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProvider
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 桌面 JVM 端 Provider 实现。主题色用 mutableStateOf 持有触发重组，
 * 读写经 [PreferenceProviders] 持久化 (对照 Android ThemeStore.saveTheme 语义)，
 * 初始值从持久层恢复，无记录时回退 arcoblue 浅色主题 (accentColor = #165DFF)。
 */

/** 桌面端主题状态：用 var 实现 ThemeStoreProvider 的 val 接口，触发重组 */
class DesktopThemeStoreProvider : ThemeStoreProvider {
    /** 是否深色主题（影响 background/bottomBackground/statusBar 等派生色），初始从 themeMode 恢复 */
    var isDark: Boolean by mutableStateOf(readInitDark())

    override var accentColor: Color by mutableStateOf(
        readInitColor(ThemeStorePrefKeys.KEY_ACCENT_COLOR) ?: Color(0xFF165DFF)
    )
        private set
    override var backgroundColor: Color by mutableStateOf(
        readInitColor(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR)
            ?: if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    )
        private set
    override var bottomBackground: Color by mutableStateOf(
        readInitColor(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND)
            ?: if (isDark) Color(0xFF1F1F1F) else Color(0xFFF7F8FA)
    )
        private set
    override var statusBarColor: Color by mutableStateOf(
        readInitColor(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR) ?: backgroundColor
    )
        private set
    override var navigationBarColor: Color by mutableStateOf(
        readInitColor(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR) ?: bottomBackground
    )
        private set

    /** 对照 ThemeConfig.curBgImagePath：按日/夜模式读持久层背景图路径，空白视为无壁纸 */
    override val bgImagePath: String?
        get() = prefsOrNull()
            ?.let { p ->
                val key = if (isDark) PreferKey.bgImageN else PreferKey.bgImage
                if (p.contains(key)) p.getString(key) else null
            }
            ?.takeUnless { it.isBlank() }

    /** 切换深/浅色主题；派生色同步刷新 */
    fun toggleDark() = updateDark(!isDark)

    /** 显式设置深/浅色主题 (不与 var isDark 自动 setter 冲突, 故改名 updateDark) */
    fun updateDark(dark: Boolean) {
        if (isDark == dark) return
        isDark = dark
        if (dark) {
            backgroundColor = Color(0xFF121212)
            bottomBackground = Color(0xFF1F1F1F)
            statusBarColor = Color(0xFF121212)
            navigationBarColor = Color(0xFF1F1F1F)
        } else {
            backgroundColor = Color(0xFFFFFFFF)
            bottomBackground = Color(0xFFF7F8FA)
            statusBarColor = Color(0xFFFFFFFF)
            navigationBarColor = Color(0xFFF7F8FA)
        }
        persist()
    }

    /**
     * 应用自定义主题色 (供桌面端 ThemeCustomizeDialog / ThemeListDialog 调用)。
     *
     * - 与 [updateDark] 不同, 本方法允许外部传入完整三色 (accent/bg/bbg),
     *   用于主题定制/主题列表的应用主题操作;
     * - 派生色 (statusBar/navigationBar) 跟随 background/bottomBackground;
     * - 更新内存状态后经 [persist] 落盘 (对照 Android ThemeConfig.applyConfig → ThemeStore.saveTheme)。
     */
    override fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean) {
        isDark = isNight
        accentColor = accent
        backgroundColor = bg
        bottomBackground = bbg
        statusBarColor = bg
        navigationBarColor = bbg
        persist()
    }

    /** 当前主题色写入持久层 (键对齐 ThemeStore.saveTheme, themeMode 对齐 AppConfig.isNightTheme setter) */
    private fun persist() {
        val p = prefsOrNull() ?: return
        p.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, backgroundColor.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, accentColor.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, backgroundColor.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, bottomBackground.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, statusBarColor.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, navigationBarColor.toArgb())
        p.putString(PreferKey.themeMode, if (isDark) "2" else "1")
    }

    private fun readInitDark(): Boolean =
        prefsOrNull()?.getString(PreferKey.themeMode, "0") == "2"

    private fun readInitColor(key: String): Color? =
        prefsOrNull()?.let { p -> if (p.contains(key)) Color(p.getInt(key)) else null }

    /** PreferenceProviders 未注册时返回 null (测试/预览场景), 主流程 Main.kt 已先注册 */
    private fun prefsOrNull(): PreferenceProvider? =
        runCatching { PreferenceProviders.get() }.getOrNull()
}

/** 桌面端 AppConfig stub：E-Ink 模式桌面端恒为 false */
class DesktopAppConfigProvider : AppConfigProvider {
    override var isEInkMode: Boolean by mutableStateOf(false)
        private set
    override val isNightTheme: Boolean
        get() = runCatching {
            PreferenceProviders.get().getString(PreferKey.themeMode, "0") == "2"
        }.getOrDefault(false)

    /** 桌面端可手动切换 E-Ink 模式用于调试组件去动画/黑白化 */
    fun updateEInk(value: Boolean) {
        isEInkMode = value
    }
}

/**
 * 桌面端事件总线：委托 commonMain [FlowBus]`[EventBus.RECREATE]` (与 AndroidEventBusProvider
 * 同模式)。多处 new 的实例共享同一全局 bus, FileThemeConfigProvider 等非 UI 层
 * 发出的 recreate 也能到达 AppTheme。
 */
class DesktopEventBusProvider : EventBusProvider {
    /** 对照 postEvent(EventBus.RECREATE, "") */
    override fun emitRecreate() {
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }

    override val recreateEvent: Flow<Unit> = FlowBus.with(EventBus.RECREATE).map { }
}

/**
 * 桌面端 Preferences provider：委托 [PreferenceProviders] 注册的持久后端
 * (DesktopPreferenceProvider, java.util.prefs)。自身无状态，各处 new 的实例
 * 共享同一后端，UI 写入与 BackupShared 等业务读取同源。
 */
class DesktopPreferenceStoreProvider : PreferenceStoreProvider {
    private val prefs: PreferenceProvider get() = PreferenceProviders.get()

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        prefs.getBoolean(key, defValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }

    override fun getInt(key: String, defValue: Int): Int =
        prefs.getInt(key, defValue)

    override fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
    }

    override fun getString(key: String, defValue: String?): String? =
        if (prefs.contains(key)) prefs.getString(key) else defValue

    override fun putString(key: String, value: String?) {
        prefs.putString(key, value)
    }
}
