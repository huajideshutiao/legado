package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ===== state =====

/**
 * 主题设置页 UI 状态 (动态 summary)。
 *
 * 只留 sourceEditMaxLineSummary: 它直读 AppConfig 的普通 getter, 需 picker 写完后 dispatch
 * 推新值才能重组。字体缩放不在此列 —— 它的生效值就是 `LocalDensity.fontScale`, 组合期直接
 * 可读 (见 ThemeConfigRoute), 走状态是组合→状态→组合的无价值绕路。
 */
data class ThemeConfigUiState(
    val sourceEditMaxLineSummary: String = "",
)

/**
 * 主题设置页交互事件。
 *
 * 字体缩放 (fontScale) 的写入口只有本路由, 改完 pref 就 emitRecreate, 无需 prefs 监听回推;
 * 换图标 (launcherIcon) 副作用经 ThemeConfigRoute 的 onIconChange 直接委托
 * PlatformCapabilities.changeLauncherIcon, 同样不走 prefs 监听。
 */
sealed interface ThemeConfigUiEvent {
    /** 源编辑框最大行数 summary 变化。 */
    data class UpdateSourceEditMaxLineSummary(val value: String) : ThemeConfigUiEvent

    // 平台专属动作 (弹窗/NumberPicker), 由宿主注入 lambda 执行
    object BookshelfLayout : ThemeConfigUiEvent
    object SearchLayout : ThemeConfigUiEvent
    object BottomNavConfig : ThemeConfigUiEvent
    object ThemeList : ThemeConfigUiEvent
    object CustomizeDayTheme : ThemeConfigUiEvent
    object CustomizeNightTheme : ThemeConfigUiEvent
    object FontScale : ThemeConfigUiEvent
    object SourceEditMaxLine : ThemeConfigUiEvent
}

// ===== ScreenModel =====

/**
 * 主题设置页 shared ScreenModel: 托管 [ThemeConfigUiState]。
 *
 * 只托管源编辑行数 summary (直读 AppConfig, 靠 dispatch 推新值); 字体缩放 summary 由
 * ThemeConfigRoute 直接从 `LocalDensity.fontScale` 算。平台专属动作 (布局/搜索/底栏/主题弹窗/
 * NumberPicker) 经构造函数 lambda 注入, 由宿主实现。
 */
class ThemeConfigScreenModel(
    private val onBookshelfLayout: () -> Unit,
    private val onSearchLayout: () -> Unit,
    private val onBottomNavConfig: () -> Unit,
    private val onThemeList: () -> Unit,
    private val onCustomizeDayTheme: () -> Unit,
    private val onCustomizeNightTheme: () -> Unit,
    private val onFontScale: () -> Unit,
    private val onSourceEditMaxLine: () -> Unit,
) : ScreenModel {

    private val _state = MutableStateFlow(ThemeConfigUiState())
    val state: StateFlow<ThemeConfigUiState> = _state.asStateFlow()

    fun dispatch(event: ThemeConfigUiEvent) {
        when (event) {
            is ThemeConfigUiEvent.UpdateSourceEditMaxLineSummary ->
                _state.value = _state.value.copy(sourceEditMaxLineSummary = event.value)

            ThemeConfigUiEvent.BookshelfLayout -> onBookshelfLayout()
            ThemeConfigUiEvent.SearchLayout -> onSearchLayout()
            ThemeConfigUiEvent.BottomNavConfig -> onBottomNavConfig()
            ThemeConfigUiEvent.ThemeList -> onThemeList()
            ThemeConfigUiEvent.CustomizeDayTheme -> onCustomizeDayTheme()
            ThemeConfigUiEvent.CustomizeNightTheme -> onCustomizeNightTheme()
            ThemeConfigUiEvent.FontScale -> onFontScale()
            ThemeConfigUiEvent.SourceEditMaxLine -> onSourceEditMaxLine()
        }
    }
}
