package io.legado.app.ui.book.read

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前章节起效替换规则对话框 shared ScreenModel。
 *
 * effectiveReplaceRules 来源于 ReadBook 共享状态（[ReadBookShared.curTextChapter] 的
 * [TextChapterShared.effectiveReplaceRules]），通过构造函数注入 provider 由 Init 事件内部读取，
 * 避免宿主重复取数。本类依据 [AppConfigProviders.chineseConverterType] 决定是否附加"繁简转换"占位项。
 * 繁简转换项实例由 [chineseConvert] 暴露，宿主 onItemClick 用 === 比较识别。
 */
class EffectiveReplacesScreenModel(
    private val getEffectiveReplaceRules: () -> List<ReplaceRule>,
) : ScreenModel {

    // 繁简转换占位项，宿主用 === 比较识别
    val chineseConvert = ReplaceRule(0, "繁简转换")

    private val _state = MutableStateFlow(EffectiveReplacesUiState())
    val state: StateFlow<EffectiveReplacesUiState> = _state.asStateFlow()

    fun dispatch(event: EffectiveReplacesUiEvent) {
        when (event) {
            is EffectiveReplacesUiEvent.Init -> {
                val rules = getEffectiveReplaceRules()
                val enabled = AppConfigProviders.get().chineseConverterType > 0
                val items = if (enabled) rules + chineseConvert else rules
                _state.value = EffectiveReplacesUiState(items)
            }
        }
    }
}

data class EffectiveReplacesUiState(
    val items: List<ReplaceRule> = emptyList(),
)

sealed interface EffectiveReplacesUiEvent {
    data object Init : EffectiveReplacesUiEvent
}
