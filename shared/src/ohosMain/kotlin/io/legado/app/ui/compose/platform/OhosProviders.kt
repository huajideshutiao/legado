package io.legado.app.ui.compose.platform

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.lib.theme.ThemeStorePrefKeys
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 鸿蒙 (OHOS) 端 Provider 真实实现。
 *
 * 主题色 / AppConfig / Preferences 基于 [PreferenceProviders] (底层 [OhosPreferenceProvider]
 * 文件持久化 legado_config.json), 事件总线沿用本地 SharedFlow (与桌面/iOS 端一致)。
 *
 * 颜色以 Android ColorInt (ARGB packed Int) 持久化, 读取后通过 Color(Int) 转换为
 * Compose Color (与 iOS [IosThemeStoreProvider] 一致)。
 *
 * # 与 iOS/Desktop 端的差异
 *
 * - 持久化: iOS 用 NSUserDefaults / 桌面用内存 Map / 鸿蒙用文件 JSON (OhosPreferenceProvider)
 * - 事件总线: 三端一致 (本地 MutableSharedFlow, 跨平台等价 FlowBus.with(EventBus.RECREATE))
 * - Compose 注入: iOS/Desktop 经 CompositionLocal 注入 4 个 Provider; 鸿蒙端当前由
 *   [io.legado.app.MainOhos] 顶层注入 (后续 ThemeListDialog/ThemeCustomizeDialog 下沉后启用)
 */

/**
 * 鸿蒙主题数据: 从 [PreferenceProviders] 读取持久化的主题色 (键名对齐 shared [ThemeStorePrefKeys]),
 * 默认 arcoblue 浅色 (与 Android ThemeStore.loadValues / iOS IosThemeStoreProvider 兜底一致)。
 */
class OhosThemeStoreProvider : ThemeStoreProvider {
    private val prefs get() = PreferenceProviders.get()

    override val accentColor: Color
        get() = Color(prefs.getInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, DEFAULT_ACCENT))
    override val backgroundColor: Color
        get() = Color(prefs.getInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, DEFAULT_BG))
    override val bottomBackground: Color
        get() = Color(prefs.getInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, DEFAULT_BOTTOM_BG))
    override val statusBarColor: Color
        get() = Color(prefs.getInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, prefs.getInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, DEFAULT_BG)))
    override val navigationBarColor: Color
        get() = Color(prefs.getInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, prefs.getInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, DEFAULT_BOTTOM_BG)))
    override val bgImagePath: String?
        get() = prefs.getString(currentBgImageKey)

    /** 写入主题色到 [PreferenceProviders] (对齐 getter 读取的键, isNight 同步更新 themeMode) */
    override fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean) {
        prefs.putInt(ThemeStorePrefKeys.KEY_ACCENT_COLOR, accent.toArgb())
        prefs.putInt(ThemeStorePrefKeys.KEY_BACKGROUND_COLOR, bg.toArgb())
        prefs.putInt(ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, bbg.toArgb())
        // statusBar / navigationBar 派生色跟随 bg / bbg (与 getter 派生逻辑一致)
        prefs.putInt(ThemeStorePrefKeys.KEY_STATUS_BAR_COLOR, bg.toArgb())
        prefs.putInt(ThemeStorePrefKeys.KEY_NAVIGATION_BAR_COLOR, bbg.toArgb())
        // themeMode: "0" = 日间, "2" = 夜间 (对齐 iOS currentBgImageKey 判断)
        prefs.putString(PreferKey.themeMode, if (isNight) "2" else "0")
    }

    /** 当前应使用的背景图键: 夜间模式读 bgImageN, 否则 bgImage (对齐 ThemeConfig.curBgImagePath)。 */
    private val currentBgImageKey: String
        get() = if (prefs.getString(PreferKey.themeMode) == "2") PreferKey.bgImageN else PreferKey.bgImage

    private companion object {
        private val DEFAULT_ACCENT = 0xFF165DFF.toInt()
        private val DEFAULT_BG = 0xFFFFFFFF.toInt()
        private val DEFAULT_BOTTOM_BG = 0xFFF7F8FA.toInt()
    }
}

/**
 * 鸿蒙 AppConfig: 从 [PreferenceProviders] 读 themeMode, isEInkMode = (themeMode == "3")。
 *
 * 对齐 iOS [IosAppConfigProvider] / Android AppConfig.isEInkMode。
 */
class OhosAppConfigProvider : AppConfigProvider {
    override val isEInkMode: Boolean
        get() = PreferenceProviders.get().getString(PreferKey.themeMode) == "3"
}

/**
 * 鸿蒙事件总线: 本地 MutableSharedFlow (与 [DesktopEventBusProvider] / [IosEventBusProvider] 一致)。
 *
 * recreateEvent 用于主题切换后触发 AppTheme 重组 (collect 后重读 ThemeStore)。
 */
class OhosEventBusProvider : EventBusProvider {
    private val flow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val recreateEvent: Flow<Unit> = flow

    /** 主题切换后由调用方 emit 触发 AppTheme 重组 (对齐 Desktop/iOS emitRecreate) */
    override fun emitRecreate() {
        flow.tryEmit(Unit)
    }
}

/**
 * 鸿蒙 [PreferenceStoreProvider]: 委托 [PreferenceProviders] (底层 [OhosPreferenceProvider]
 * 文件持久化 legado_config.json), 让 Compose UI 与 commonMain 业务逻辑共享同一存储。
 *
 * 类型映射: Boolean/Int/String 直接委托 PreferenceProvider 同名方法。
 */
class OhosPreferenceStoreProvider : PreferenceStoreProvider {
    private val prefs get() = PreferenceProviders.get()

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
        prefs.getString(key, defValue ?: "")

    override fun putString(key: String, value: String?) {
        prefs.putString(key, value)
    }
}
