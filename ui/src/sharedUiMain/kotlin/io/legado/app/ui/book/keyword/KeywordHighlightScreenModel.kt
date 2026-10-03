package io.legado.app.ui.book.keyword

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.KeywordHighlight
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 关键词高亮列表页 UI 状态。
 */
data class KeywordHighlightUiState(
    val rules: List<KeywordHighlight> = emptyList(),
)

/**
 * 关键词高亮列表页 (shared)。
 *
 * 对齐 [io.legado.app.ui.book.filter.SourceFilterRuleScreenModel] 的结构:
 * 数据流直订 DAO flow, 变更后列表与阅读层 (ChapterHighlightState 订阅同一 flow) 自动回推,
 * 无需经路由回传结果。
 */
class KeywordHighlightScreenModel : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    // 自管 scope (screenModelScope 统一入口, 异常兜底 + 路由销毁时取消)
    private val scope = screenModelScope("关键词高亮")

    private val _state = MutableStateFlow(KeywordHighlightUiState())
    val state: StateFlow<KeywordHighlightUiState> = _state.asStateFlow()

    init {
        scope.launch {
            appDb.keywordHighlightDao.flowAll()
                .collect { rules -> _state.update { it.copy(rules = rules) } }
        }
    }

    /** 新增 (id=0) 取 maxOrder+1 排在末尾; update 用 REPLACE 幂等落库 */
    fun save(rule: KeywordHighlight) {
        scope.launch {
            runCatching {
                if (rule.id == 0L) {
                    val maxOrder = appDb.keywordHighlightDao.maxOrder()
                    appDb.keywordHighlightDao.insert(rule.copy(sortOrder = maxOrder + 1))
                } else {
                    appDb.keywordHighlightDao.update(rule)
                }
            }.onFailure { AppLog.put("保存关键词高亮失败\n${it.message}", it) }
        }
    }

    fun toggleEnabled(rule: KeywordHighlight, enabled: Boolean) {
        scope.launch { appDb.keywordHighlightDao.update(rule.copy(isEnabled = enabled)) }
    }

    fun delete(rule: KeywordHighlight) {
        scope.launch { appDb.keywordHighlightDao.delete(rule) }
    }
}
