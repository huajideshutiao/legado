package io.legado.app.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.legado.app.help.config.AppConfigAccessor
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.platform.AppConfigProvider
import io.legado.app.ui.compose.platform.EventBusProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.platform.ThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Preview 专用环境。
 *
 * shared/sharedUiMain 中的 Composable 普遍依赖通过 CompositionLocal 注入的 provider
 * (ThemeStore/AppConfig/EventBus/PreferenceStore), 未注入时 AppTheme 会在 `current` 处
 * 抛 `error("... not provided")`。本文件提供 Preview 用的 stub 实例与 [LegadoThemePreview]
 * 包装 Composable, 让各 @Preview 函数可在 IDE 中无脑渲染, 无需重复造 stub。
 *
 * 用法:
 * ```
 * @Preview
 * @Composable
 * fun FooPreview() {
 *     LegadoThemePreview { Foo(...) }
 * }
 * ```
 *
 * 注: 仅用于 Preview, 不参与正式运行; stub 仅返回常量, 不读写任何持久化。
 */

/** 浅色主题 stub: 白底深字 / accent 蓝(#165DFF, Arco arcoblue-6)。 */
private class LightThemeStore : ThemeStoreProvider {
    override val accentColor: Color = Color(0xFF165DFF)
    override val backgroundColor: Color = Color(0xFFFFFFFF)
    override val bottomBackground: Color = Color(0xFFF7F8FA)
    override val statusBarColor: Color = Color(0xFFFFFFFF)
    override val navigationBarColor: Color = Color(0xFFFFFFFF)
    override val bgImagePath: String? = null
}

/** 深色主题 stub: 深底浅字 / accent 蓝。 */
private class DarkThemeStore : ThemeStoreProvider {
    override val accentColor: Color = Color(0xFF165DFF)
    override val backgroundColor: Color = Color(0xFF17191C)
    override val bottomBackground: Color = Color(0xFF2A2D32)
    override val statusBarColor: Color = Color(0xFF17191C)
    override val navigationBarColor: Color = Color(0xFF17191C)
    override val bgImagePath: String? = null
}

/** 非 E-Ink 模式 stub。 */
private class StubAppConfig : AppConfigProvider {
    override val isEInkMode: Boolean = false
}

/** 空事件流 stub (无主题刷新事件)。 */
private class StubEventBus : EventBusProvider {
    override val recreateEvent: Flow<Unit> = emptyFlow()
}

/** 内存型 stub PreferenceStore (Preview 期间维护一份 mutableMap, 不落盘)。 */
private class StubPreferenceStore : PreferenceStoreProvider {
    private val bools = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val strings = mutableMapOf<String, String?>()

    override fun getBoolean(key: String, defValue: Boolean): Boolean = bools[key] ?: defValue
    override fun putBoolean(key: String, value: Boolean) { bools[key] = value }

    override fun getInt(key: String, defValue: Int): Int = ints[key] ?: defValue
    override fun putInt(key: String, value: Int) { ints[key] = value }

    override fun getString(key: String, defValue: String?): String? = strings[key] ?: defValue
    override fun putString(key: String, value: String?) { strings[key] = value }
}

/**
 * Stub [AppConfigAccessor] (Preview 用): 所有字段返回常见默认值,
 * 仅保证 `AppConfigProviders.get()` 不抛异常。
 *
 * 字段默认值对照 app 端 AppConfig 的真实默认值 (见 AppConfigAccessor 注释),
 * 不影响 Preview 渲染。`searchScope`/`searchGroup` 是 var, 用 mutableStateOf 兜底。
 */
private class StubAppConfigAccessor : AppConfigAccessor {
    override val threadCount: Int = 8
    override val tocCountWords: Boolean = false
    override var chineseConverterType: Int = 0
    override val replaceEnableDefault: Boolean = true
    override val enableReadRecord: Boolean = true

    override val bookshelfSort: Int = 0
    override val bookshelfLayout: Int = 0
    override val bookshelfCoverHeight: Int = 120
    override val bookshelfGridWidth: Int = 120
    override val showUnread: Boolean = true
    override val bookshelfListShowKind: Boolean = true
    override val bookshelfListShowIntro: Boolean = true
    override val bookshelfListIntroLines: Int = 2
    override val bookshelfShowGroupCount: Boolean = true
    override val bookshelfFixedWidthMode: Boolean = false
    override val showLastUpdateTime: Boolean = true
    override val saveTabPosition: Int = 0
    override val bookExportFileName: String = ""
    override val episodeExportFileName: String = ""
    override val bookGroupStyle: Int = 0
    override val autoRefreshBook: Boolean = false
    override val preDownloadNum: Int = 10

    override var searchScope: String = ""
    override var searchGroup: String = ""
    override val searchLayout: Int = 1
    override val precisionSearch: Boolean = false
    override fun setPrecisionSearch(value: Boolean) {}

    override val exportCharset: String = "UTF-8"

    override val webDavUrl: String = ""
    override val webDavAccount: String = ""
    override val webDavPassword: String = ""
    override val syncBookProgress: Boolean = true
    override val webDavDir: String = "legado"
    override val webDavDeviceName: String = ""

    override val ttsEngine: String = ""
    override val ttsSpeechRate: Int = 5
    override val ttsTimer: Int = 0

    override val themeMode: String = "0"
    override val isNightTheme: Boolean = false
    override val isEInkMode: Boolean = false
    override val useDefaultCover: Boolean = false

    override val bottomBarHeight: Int = 50
    override val bottomBarIconSize: Int = 24
    override val bottomBarLabelMode: Int = 0
    override val showHome: Boolean = true
    override val showDiscovery: Boolean = true
    override val bottomNavItemOrder: String = ""
    override val defaultHomePage: String = "bookshelf"

    override val importKeepName: Boolean = false
    override val importKeepGroup: Boolean = false
    override val importKeepEnable: Boolean = false
    override val localBookImportSort: Int = 0

    override val remoteServerId: Long = 0L
    override val batchChangeSourceDelay: Int = 0
}

/** Preview 期一次性注册 stub AppConfigAccessor (幂等)。 */
internal fun registerStubAppConfig() {
    AppConfigProviders.register(StubAppConfigAccessor())
}

/**
 * Preview 包装: provides 全部 CompositionLocal stub + 调用 [AppTheme],
 * 内部用 fillMaxSize + Box 居中放内容, 让 Preview 默认有合理背景与尺寸。
 * 同时通过 SideEffect 注册 stub [AppConfigAccessor] 到 [AppConfigProviders],
 * 让依赖 `AppConfigProviders.get()` 的 Composable (书架条目等) 也能 Preview。
 *
 * @param dark true 用深色主题, false 用浅色主题 (默认浅色)
 * @param content 待 Preview 的 Composable
 */
@Composable
fun LegadoThemePreview(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val themeStore = if (dark) DarkThemeStore() else LightThemeStore()
    androidx.compose.runtime.SideEffect { registerStubAppConfig() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides StubAppConfig(),
        LocalEventBusProvider provides StubEventBus(),
        LocalPreferenceStoreProvider provides StubPreferenceStore(),
    ) {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
