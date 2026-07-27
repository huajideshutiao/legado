package io.legado.app.ui.compose.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow

/**
 * 桌面 JVM 端 Provider 实现。无 SharedPreferences / ThemeStore 等底座，用内存
 * mutableStateOf 持有主题色，初始为 arcoblue 浅色主题，桌面 UI 可通过
 * [toggleDark] / [setDark] 切换深浅。
 *
 * 颜色值与 ThemeStore 兜底保持一致（accentColor = #165DFF = arcoblue-6）。
 * 桌面端暂不支持背景图，bgImagePath 恒为 null。
 */

/** 桌面端主题状态：用 var 实现 ThemeStoreProvider 的 val 接口，触发重组 */
class DesktopThemeStoreProvider : ThemeStoreProvider {
    /** 是否深色主题（影响 background/bottomBackground/statusBar 等派生色） */
    var isDark: Boolean by mutableStateOf(false)

    override var accentColor: Color by mutableStateOf(Color(0xFF165DFF))
        private set
    override var backgroundColor: Color by mutableStateOf(Color(0xFFFFFFFF))
        private set
    override var bottomBackground: Color by mutableStateOf(Color(0xFFF7F8FA))
        private set
    override var statusBarColor: Color by mutableStateOf(Color(0xFFFFFFFF))
        private set
    override var navigationBarColor: Color by mutableStateOf(Color(0xFFF7F8FA))
        private set
    override val bgImagePath: String? get() = null

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
    }

    /**
     * 应用自定义主题色 (供桌面端 ThemeCustomizeDialog / ThemeListDialog 调用)。
     *
     * - 与 [updateDark] 不同, 本方法允许外部传入完整三色 (accent/bg/bbg),
     *   用于主题定制/主题列表的应用主题操作;
     * - 派生色 (statusBar/navigationBar) 跟随 background/bottomBackground;
     * - isNight 仅更新 isDark 标记, 不再走 [updateDark] 的默认深浅色覆盖
     *   (因为外部已显式给出 bg/bbg, 不能被 updateDark 默认值覆盖)。
     */
    override fun applyColors(accent: Color, bg: Color, bbg: Color, isNight: Boolean) {
        isDark = isNight
        accentColor = accent
        backgroundColor = bg
        bottomBackground = bbg
        statusBarColor = bg
        navigationBarColor = bbg
    }
}

/** 桌面端 AppConfig stub：E-Ink 模式桌面端恒为 false */
class DesktopAppConfigProvider : AppConfigProvider {
    override var isEInkMode: Boolean by mutableStateOf(false)
        private set

    /** 桌面端可手动切换 E-Ink 模式用于调试组件去动画/黑白化 */
    fun updateEInk(value: Boolean) {
        isEInkMode = value
    }
}

/** 桌面端事件总线：本地 MutableSharedFlow，recreateEvent 用于切换主题后触发 AppTheme 重组 */
class DesktopEventBusProvider : EventBusProvider {
    private val recreateFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 桌面端无 FlowBus，主题切换后由调用方 emit 此流触发 AppTheme 重组 */
    override fun emitRecreate() {
        recreateFlow.tryEmit(Unit)
    }

    override val recreateEvent: Flow<Unit> = recreateFlow
}

/**
 * 桌面端 Preferences provider：内存 Map 实现，无 SharedPreferences 底座。
 *
 * 与 [DesktopThemeStoreProvider] 一致用 var/mutableStateOf 持有状态，
 * Compose 读 prefs 时 (PreferenceRow 内部 remember state) 重组由各 Composable 自行管理，
 * Provider 仅负责持久化语义 (实际桌面端进程结束即丢失)。
 *
 * Boolean/Int/String 分别存独立 Map，避免类型混淆。
 */
class DesktopPreferenceStoreProvider : PreferenceStoreProvider {
    private val boolMap = mutableMapOf<String, Boolean>()
    private val intMap = mutableMapOf<String, Int>()
    private val stringMap = mutableMapOf<String, String?>()

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        boolMap[key] ?: defValue

    override fun putBoolean(key: String, value: Boolean) {
        boolMap[key] = value
    }

    override fun getInt(key: String, defValue: Int): Int =
        intMap[key] ?: defValue

    override fun putInt(key: String, value: Int) {
        intMap[key] = value
    }

    override fun getString(key: String, defValue: String?): String? =
        stringMap[key] ?: defValue

    override fun putString(key: String, value: String?) {
        stringMap[key] = value
    }
}
