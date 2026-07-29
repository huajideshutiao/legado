package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ===== state =====

/**
 * 封面设置页 UI 状态 (动态 summary)。
 *
 * 封面数量/封面高度 summary 依赖平台资源 (Context + BookCover + AppConfig),
 * 由宿主解析后通过 [CoverConfigScreenModel.dispatch] 推入。
 */
data class CoverConfigUiState(
    val dayCoverSummary: String = "",
    val nightCoverSummary: String = "",
    val coverHeightSummary: String = "",
)

/**
 * 封面设置页交互事件。
 *
 * prefs 变更回调 (defaultCover/defaultCoverDark) 由宿主
 * OnSharedPreferenceChangeListener 承接原副作用, summary 文案更新通过 dispatch 推入本类。
 * 封面高度 summary 由宿主 NumberPicker 回调 dispatch 推入。
 */
sealed interface CoverConfigUiEvent {
    /** 日间默认封面数量 summary 变化。 */
    data class UpdateDayCoverSummary(val value: String) : CoverConfigUiEvent

    /** 夜间默认封面数量 summary 变化。 */
    data class UpdateNightCoverSummary(val value: String) : CoverConfigUiEvent

    /** 封面高度 summary 变化。 */
    data class UpdateCoverHeightSummary(val value: String) : CoverConfigUiEvent
}

// ===== ScreenModel =====

/**
 * 封面设置页 shared ScreenModel: 托管 [CoverConfigUiState]。
 *
 * 平台资源 (coverCountSummary/coverHeightSummary 字符串) 无法在 shared 层直接读取
 * (依赖 Context + BookCover.listDefaultCovers + AppConfig), 由宿主在 init 及
 * prefs/NumberPicker 回调中 dispatch 推入。
 */
class CoverConfigScreenModel : ScreenModel {

    private val _state = MutableStateFlow(CoverConfigUiState())
    val state: StateFlow<CoverConfigUiState> = _state.asStateFlow()

    fun dispatch(event: CoverConfigUiEvent) {
        when (event) {
            is CoverConfigUiEvent.UpdateDayCoverSummary ->
                _state.value = _state.value.copy(dayCoverSummary = event.value)

            is CoverConfigUiEvent.UpdateNightCoverSummary ->
                _state.value = _state.value.copy(nightCoverSummary = event.value)

            is CoverConfigUiEvent.UpdateCoverHeightSummary ->
                _state.value = _state.value.copy(coverHeightSummary = event.value)
        }
    }
}
