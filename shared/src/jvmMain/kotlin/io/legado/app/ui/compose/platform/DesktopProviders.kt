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
import io.legado.app.help.config.resolveImagePath
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 桌面 JVM 端 Provider 实现。主题色与 iOS/鸿蒙一致: 从 [PreferenceProviders] 持久层动态读取
 * (无 Compose 内存态), AppTheme 经 [EventBusProvider.recreateEvent] 重组后重读新色。
 * 读写经 [PreferenceProviders] 持久化 (对照 Android ThemeStore.saveTheme 语义)，
 * 初始值从持久层恢复，无记录时回退 arcoblue 浅色主题 (accentColor = #165DFF)。
 *
 * 切换日夜走 [io.legado.app.help.config.FileThemeConfigProvider.applyDayNight] 写
 * ThemeStore 色 + themeMode 并 emit RECREATE; 本类无需再缓存 Compose 状态。
 */
class DesktopThemeStoreProvider : ThemeStoreProvider {
    override val accentColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_ACCENT_COLOR) ?: Color(0xFF165DFF)
    override val backgroundColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR)
            ?: if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    override val bottomBackground: Color
        get() = readColor(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND)
            ?: if (isDark) Color(0xFF1F1F1F) else Color(0xFFF7F8FA)
    override val statusBarColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR) ?: backgroundColor
    override val navigationBarColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR) ?: bottomBackground

    /** 是否深色主题 (从 themeMode 计算), 影响 background/bottomBackground 等派生色 */
    val isDark: Boolean
        get() = prefsOrNull()?.getString(PreferKey.themeMode, "0") == "2"

    /** 对照 ThemeConfig.curBgImagePath：按日/夜模式读持久层背景图相对引用并解析为绝对路径，空白视为无壁纸 */
    override val bgImagePath: String?
        get() = resolveImagePath(
            prefsOrNull()
                ?.let { p ->
                    val key = if (isDark) PreferKey.bgImageN else PreferKey.bgImage
                    if (p.contains(key)) p.getString(key) else null
                }
                ?.takeUnless { it.isBlank() }
        )

    /** 按日/夜读背景图模糊键 (对照原版 bgImageBlurring) */
    override val bgImageBlur: Int
        get() = prefsOrNull()?.getInt(
            if (isDark) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
            0,
        ) ?: 0

    /** 切换深/浅色主题；写默认深/浅色 + themeMode 并触发全局重组 */
    fun toggleDark() = updateDark(!isDark)

    /** 显式设置深/浅色主题 (与 isDark 计算属性解耦, 直接落盘派生色) */
    fun updateDark(dark: Boolean) {
        if (isDark == dark) return
        val p = prefsOrNull() ?: return
        p.putInt(
            ThemeStorePrefKeys.KEY_PRIMARY_COLOR,
            if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        )
        p.putInt(
            ThemeStorePrefKeys.KEY_BACKGROUND_COLOR,
            if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        )
        p.putInt(
            ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND,
            if (dark) 0xFF1F1F1F.toInt() else 0xFFF7F8FA.toInt()
        )
        p.putInt(
            ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR,
            if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        )
        p.putInt(
            ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR,
            if (dark) 0xFF1F1F1F.toInt() else 0xFFF7F8FA.toInt()
        )
        p.putString(PreferKey.themeMode, if (dark) "2" else "1")
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }

    /**
     * 应用自定义主题色 (供桌面端 ThemeCustomizeDialog / ThemeListDialog 调用)。
     *
     * 写完整三色到持久层后 emit RECREATE 触发 AppTheme 重组重读。
     */
    override fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean) {
        val p = prefsOrNull() ?: return
        p.putInt(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, bg.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, accent.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, bg.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, bbg.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, bg.toArgb())
        p.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, bbg.toArgb())
        p.putString(PreferKey.themeMode, if (isNight) "2" else "1")
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }

    private fun readColor(key: String): Color? =
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
