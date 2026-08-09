package io.legado.app.ui.book.filter

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 书源过滤规则页 ScreenModel (KMP 版, sharedUiMain 共享)。
 *
 * 持有 [SourceFilterRuleUiState] 并订阅 DAO flow, 通过 [dispatch] 处理 UI 事件。
 * 宿主 Activity 只负责平台专属行为 (对话框/文件/ACache), 状态与数据操作全部在本类完成。
 */
class SourceFilterRuleScreenModel : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    private val scope = screenModelScope("书源筛选规则")

    private val _state = MutableStateFlow(SourceFilterRuleUiState())
    val state: StateFlow<SourceFilterRuleUiState> = _state.asStateFlow()

    // DB 数据变更后通知宿主 setResult(RESULT_OK)
    private val _dataModified = MutableStateFlow(false)
    val dataModified: StateFlow<Boolean> = _dataModified.asStateFlow()

    private var dataInit = false
    private var flowJob: Job? = null

    init {
        observe(null)
    }

    private fun observe(searchKey: String?) {
        flowJob?.cancel()
        dataInit = false
        flowJob = scope.launch {
            val flow = if (searchKey.isNullOrEmpty()) {
                appDb.sourceFilterRuleDao.flowAll()
            } else {
                appDb.sourceFilterRuleDao.flowSearch("%$searchKey%")
            }
            flow.catch {
                AppLog.put("过滤规则管理界面更新数据出错", it)
            }.flowOn(IoDispatcher).throttleLatest(100).collect { rules ->
                if (dataInit) _dataModified.value = true
                val selected = _state.value.selected.intersect(rules.map { it.id }.toSet())
                _state.value = _state.value.copy(
                    rules = rules,
                    selected = selected,
                    searchKey = searchKey ?: _state.value.searchKey,
                )
                dataInit = true
            }
        }
    }

    fun dispatch(event: SourceFilterRuleUiEvent) {
        when (event) {
            is SourceFilterRuleUiEvent.SearchKeyChange -> {
                _state.value = _state.value.copy(searchKey = event.key)
                observe(event.key)
            }

            is SourceFilterRuleUiEvent.ToggleSelected -> {
                val cur = _state.value.selected
                _state.value = _state.value.copy(
                    selected = if (event.checked) cur + event.item.id else cur - event.item.id
                )
            }

            is SourceFilterRuleUiEvent.SelectAll -> {
                _state.value = _state.value.copy(
                    selected = if (event.all) _state.value.rules.map { it.id }
                        .toSet() else emptySet()
                )
            }

            SourceFilterRuleUiEvent.RevertSelection -> {
                _state.value = _state.value.copy(
                    selected = _state.value.rules.map { it.id }.toSet() - _state.value.selected
                )
            }

            is SourceFilterRuleUiEvent.MoveItem -> {
                val list =
                    _state.value.rules.toMutableList().apply { add(event.to, removeAt(event.from)) }
                _state.value = _state.value.copy(rules = list)
            }

            SourceFilterRuleUiEvent.PersistOrder -> {
                val rules = _state.value.rules
                rules.forEachIndexed { index, item -> item.order = index + 1 }
                mutate { appDb.sourceFilterRuleDao.update(*rules.toTypedArray()) }
            }

            SourceFilterRuleUiEvent.DeleteSelection -> {
                val selection = selection()
                mutate { appDb.sourceFilterRuleDao.delete(*selection.toTypedArray()) }
            }

            is SourceFilterRuleUiEvent.DeleteRule -> {
                mutate { appDb.sourceFilterRuleDao.delete(event.rule) }
            }

            SourceFilterRuleUiEvent.DeleteAll -> {
                mutate { appDb.sourceFilterRuleDao.deleteAll() }
            }

            SourceFilterRuleUiEvent.EnableSelection -> {
                val selection = selection()
                mutate {
                    val array = Array(selection.size) { selection[it].copy(enabled = true) }
                    appDb.sourceFilterRuleDao.update(*array)
                }
            }

            SourceFilterRuleUiEvent.DisableSelection -> {
                val selection = selection()
                mutate {
                    val array = Array(selection.size) { selection[it].copy(enabled = false) }
                    appDb.sourceFilterRuleDao.update(*array)
                }
            }

            SourceFilterRuleUiEvent.TopSelect -> {
                val selection = selection()
                mutate {
                    var minOrder = appDb.sourceFilterRuleDao.minOrder() - selection.size
                    selection.forEach { it.order = ++minOrder }
                    appDb.sourceFilterRuleDao.update(*selection.toTypedArray())
                }
            }

            SourceFilterRuleUiEvent.BottomSelect -> {
                val selection = selection()
                mutate {
                    var maxOrder = appDb.sourceFilterRuleDao.maxOrder()
                    selection.forEach { it.order = ++maxOrder }
                    appDb.sourceFilterRuleDao.update(*selection.toTypedArray())
                }
            }

            is SourceFilterRuleUiEvent.ToTop -> {
                mutate {
                    event.rule.order = appDb.sourceFilterRuleDao.minOrder() - 1
                    appDb.sourceFilterRuleDao.update(event.rule)
                }
            }

            is SourceFilterRuleUiEvent.ToBottom -> {
                mutate {
                    event.rule.order = appDb.sourceFilterRuleDao.maxOrder() + 1
                    appDb.sourceFilterRuleDao.update(event.rule)
                }
            }

            is SourceFilterRuleUiEvent.ToggleEnabled -> {
                mutate { appDb.sourceFilterRuleDao.update(event.rule.copy(enabled = event.enabled)) }
            }

            is SourceFilterRuleUiEvent.Save -> {
                scope.launch(IoDispatcher) {
                    SearchBookFilter.save(event.rule, event.isNew)
                }
            }
        }
    }

    // DAO 写后刷新 SearchBookFilter 缓存
    private fun mutate(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(IoDispatcher) {
            try {
                block()
                SearchBookFilter.reload()
            } catch (e: Throwable) {
                e.printStackTraceOnDebug()
            }
        }
    }

    fun selection(): List<SourceFilterRule> =
        _state.value.rules.filter { _state.value.selected.contains(it.id) }

    override fun onCleared() {
        scope.cancel()
    }
}

sealed interface SourceFilterRuleUiEvent {
    data class SearchKeyChange(val key: String) : SourceFilterRuleUiEvent
    data class ToggleSelected(val item: SourceFilterRule, val checked: Boolean) :
        SourceFilterRuleUiEvent

    data class SelectAll(val all: Boolean) : SourceFilterRuleUiEvent
    object RevertSelection : SourceFilterRuleUiEvent
    data class MoveItem(val from: Int, val to: Int) : SourceFilterRuleUiEvent
    object PersistOrder : SourceFilterRuleUiEvent
    object DeleteSelection : SourceFilterRuleUiEvent
    data class DeleteRule(val rule: SourceFilterRule) : SourceFilterRuleUiEvent
    object DeleteAll : SourceFilterRuleUiEvent
    object EnableSelection : SourceFilterRuleUiEvent
    object DisableSelection : SourceFilterRuleUiEvent
    object TopSelect : SourceFilterRuleUiEvent
    object BottomSelect : SourceFilterRuleUiEvent
    data class ToTop(val rule: SourceFilterRule) : SourceFilterRuleUiEvent
    data class ToBottom(val rule: SourceFilterRule) : SourceFilterRuleUiEvent
    data class ToggleEnabled(val rule: SourceFilterRule, val enabled: Boolean) :
        SourceFilterRuleUiEvent

    data class Save(val rule: SourceFilterRule, val isNew: Boolean) : SourceFilterRuleUiEvent
}
