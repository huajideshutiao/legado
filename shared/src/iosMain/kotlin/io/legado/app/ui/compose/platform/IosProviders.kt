package io.legado.app.ui.compose.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSUserDefaults

/**
 * iOS 端 Provider 真实实现。
 *
 * 主题色 / AppConfig / Preferences 基于 NSUserDefaults 持久化 (等价 Android
 * SharedPreferences / ThemeStore), 事件总线委托 commonMain [FlowBus]`[EventBus.RECREATE]`
 * (与桌面端一致)。
 *
 * 颜色以 Android ColorInt (ARGB packed Int) 持久化, 读取后通过 Color(Int) 转换为
 * Compose Color (与 AndroidThemeStoreProvider 一致)。
 */

/** 判断 key 是否存在于 NSUserDefaults (与 IosPreferenceProvider.objectHasKey 同语义)。 */
private fun NSUserDefaults.objectHasKey(key: String): Boolean = objectForKey(key) != null

/**
 * iOS 主题数据: 从 NSUserDefaults 读取持久化的主题色 (键名对齐 shared ThemeStorePrefKeys),
 * 默认 arcoblue 浅色 (与 Android ThemeStore.loadValues 兜底一致)。
 *
 * bgImagePath: 对齐 ThemeConfig.curBgImagePath, 按 themeMode 选择日/夜背景图键。
 */
class IosThemeStoreProvider(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ThemeStoreProvider {
    override val accentColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_ACCENT_COLOR, DEFAULT_ACCENT)
    override val backgroundColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, DEFAULT_BG)
    override val bottomBackground: Color
        get() = readColor(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, DEFAULT_BOTTOM_BG)
    override val statusBarColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, backgroundColor)
    override val navigationBarColor: Color
        get() = readColor(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, bottomBackground)
    override val bgImagePath: String?
        get() = defaults.stringForKey(currentBgImageKey)

    /** 写入主题色到 NSUserDefaults (对齐 getter 读取的键, isNight 同步更新 themeMode) */
    override fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean) {
        defaults.setObject(accent.toArgb(), forKey = ThemeStorePrefKeys.KEY_ACCENT_COLOR)
        defaults.setObject(bg.toArgb(), forKey = ThemeStorePrefKeys.KEY_BACKGROUND_COLOR)
        defaults.setObject(bbg.toArgb(), forKey = ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND)
        // statusBar / navigationBar 派生色跟随 bg / bbg (与 getter 派生逻辑一致)
        defaults.setObject(bg.toArgb(), forKey = ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR)
        defaults.setObject(bbg.toArgb(), forKey = ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR)
        // themeMode: "0" = 日间, "2" = 夜间 (对齐 currentBgImageKey 判断)
        defaults.setObject(if (isNight) "2" else "0", forKey = PreferKey.themeMode)
        defaults.synchronize()
    }

    private fun readColor(key: String, default: Color): Color {
        if (!defaults.objectHasKey(key)) return default
        // integerForKey 返回 NSInteger (Long), 截断为 Int (Android ColorInt, ARGB packed)
        return Color(defaults.integerForKey(key).toInt())
    }

    /** 当前应使用的背景图键: 夜间模式读 bgImageN, 否则 bgImage (对齐 ThemeConfig.curBgImagePath)。 */
    private val currentBgImageKey: String
        get() = if (defaults.stringForKey(PreferKey.themeMode) == "2") PreferKey.bgImageN else PreferKey.bgImage

    private companion object {
        // 键名常量已下沉 shared/commonMain (ThemeStorePrefKeys), 直接引用消除本地复制
        private val DEFAULT_ACCENT = Color(0xFF165DFF)
        private val DEFAULT_BG = Color(0xFFFFFFFF)
        private val DEFAULT_BOTTOM_BG = Color(0xFFF7F8FA)
    }
}

/**
 * iOS AppConfig: 从 NSUserDefaults 读 themeMode, isEInkMode = (themeMode == "3")。
 *
 * 对齐 Android AppConfig.isEInkMode; NativeAppConfigAccessor 也在业务层提供 isEInkMode,
 * 本 Provider 供 Compose AppTheme 在 commonMain 消费 (LocalAppConfigProvider)。
 */
class IosAppConfigProvider(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : AppConfigProvider {
    override val isEInkMode: Boolean
        get() = defaults.stringForKey(PreferKey.themeMode) == "3"
    override val isNightTheme: Boolean
        get() = defaults.stringForKey(PreferKey.themeMode) == "2"
}

/**
 * iOS 事件总线: 委托 commonMain [FlowBus]`[EventBus.RECREATE]` (与 DesktopEventBusProvider
 * 同模式)。多处 new 的实例共享同一全局 bus, [FileThemeConfigProvider] 等非 UI 层
 * 发出的 recreate 也能到达 AppTheme。
 */
class IosEventBusProvider : EventBusProvider {
    /** 对照 postEvent(EventBus.RECREATE, "") */
    override val recreateEvent: Flow<Unit> = FlowBus.with(EventBus.RECREATE).map { }

    /** 主题切换后由调用方 emit 触发 AppTheme 重组 (对齐 DesktopEventBusProvider.emitRecreate) */
    override fun emitRecreate() {
        FlowBus.with(EventBus.RECREATE).tryEmit("")
    }
}

/**
 * iOS Preferences: 基于 NSUserDefaults 持久化 (等价 Android SharedPreferences)。
 *
 * 类型映射 (对齐 IosPreferenceProvider):
 * - Boolean: setBool/boolForKey
 * - Int: setObject(NSNumber)/integerForKey (NSInteger 64 位截断为 Int)
 * - String: setObject(NSString)/stringForKey
 *
 * putString(null) 等价于 SharedPreferences.Editor.remove (removeObjectForKey),
 * 与 Android SP 行为一致; 写后 synchronize 确保立即落盘 (对齐 Android commit())。
 */
class IosPreferenceStoreProvider(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : PreferenceStoreProvider {

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        // boolForKey 对不存在的 key 返回 false, 需用 objectForKey 判断后回退 default
        if (!defaults.objectHasKey(key)) return defValue
        return defaults.boolForKey(key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
        defaults.synchronize()
    }

    override fun getInt(key: String, defValue: Int): Int {
        // integerForKey 对不存在的 key 返回 0, 需用 objectForKey 判断后回退 default
        if (!defaults.objectHasKey(key)) return defValue
        return defaults.integerForKey(key).toInt()
    }

    override fun putInt(key: String, value: Int) {
        // KN 中 NSUserDefaults 无 setIntegerForKey, 用 setObject (NSInteger 自动装箱为 NSNumber)
        defaults.setObject(value, forKey = key)
        defaults.synchronize()
    }

    override fun getString(key: String, defValue: String?): String? {
        // stringForKey 对不存在的 key 返回 null, 直接回退 default
        return defaults.stringForKey(key) ?: defValue
    }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            // null 等价于移除 (与 SharedPreferences.Editor.remove 行为一致)
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
        defaults.synchronize()
    }
}
