package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ===== state =====

/**
 * 主题设置页 UI 状态 (动态 summary)。
 *
 * fontScale 的 summary 依赖平台资源 (Context + AppConfig),
 * 由宿主解析后通过 [ThemeConfigScreenModel.dispatch] 推入。
 */
data class ThemeConfigUiState(
    val fontScaleSummary: String = "",
)

/**
 * 主题设置页交互事件。
 *
 * prefs 变更回调 (fontScale) 由宿主 OnSharedPreferenceChangeListener
 * 承接原副作用 (LauncherIconHelp/recreate), summary 文案更新通过 dispatch 推入本类。
 */
sealed interface ThemeConfigUiEvent {
    /** 字体缩放 summary 变化。 */
    data class UpdateFontScaleSummary(val value: String) : ThemeConfigUiEvent

    // 平台专属动作 (弹窗/NumberPicker), 由宿主注入 lambda 执行
    object BookshelfLayout : ThemeConfigUiEvent
    object SearchLayout : ThemeConfigUiEvent
    object BottomNavConfig : ThemeConfigUiEvent
    object ThemeList : ThemeConfigUiEvent
    object CustomizeDayTheme : ThemeConfigUiEvent
    object CustomizeNightTheme : ThemeConfigUiEvent
    object FontScale : ThemeConfigUiEvent
}

// ===== ScreenModel =====

/**
 * 主题设置页 shared ScreenModel: 托管 [ThemeConfigUiState]。
 *
 * 平台资源 (fontScaleSummary 字符串) 无法在 shared 层直接读取
 * (依赖 Context + AppConfig), 由宿主在 init 及 prefs 回调中 dispatch 推入。
 * 平台专属动作 (布局/搜索/底栏/主题弹窗/NumberPicker) 经构造函数 lambda 注入, 由宿主实现。
 */
class ThemeConfigScreenModel(
    private val onBookshelfLayout: () -> Unit,
    private val onSearchLayout: () -> Unit,
    private val onBottomNavConfig: () -> Unit,
    private val onThemeList: () -> Unit,
    private val onCustomizeDayTheme: () -> Unit,
    private val onCustomizeNightTheme: () -> Unit,
    private val onFontScale: () -> Unit,
) : ScreenModel {

    private val _state = MutableStateFlow(ThemeConfigUiState())
    val state: StateFlow<ThemeConfigUiState> = _state.asStateFlow()

    fun dispatch(event: ThemeConfigUiEvent) {
        when (event) {
            is ThemeConfigUiEvent.UpdateFontScaleSummary ->
                _state.value = _state.value.copy(fontScaleSummary = event.value)

            ThemeConfigUiEvent.BookshelfLayout -> onBookshelfLayout()
            ThemeConfigUiEvent.SearchLayout -> onSearchLayout()
            ThemeConfigUiEvent.BottomNavConfig -> onBottomNavConfig()
            ThemeConfigUiEvent.ThemeList -> onThemeList()
            ThemeConfigUiEvent.CustomizeDayTheme -> onCustomizeDayTheme()
            ThemeConfigUiEvent.CustomizeNightTheme -> onCustomizeNightTheme()
            ThemeConfigUiEvent.FontScale -> onFontScale()
        }
    }
}
