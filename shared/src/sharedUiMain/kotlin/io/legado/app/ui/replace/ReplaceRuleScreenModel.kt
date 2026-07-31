package io.legado.app.ui.replace

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 替换规则管理页 ScreenModel (KMP 版, sharedUiMain 共享)。
 *
 * 持有 [ReplaceRuleUiState] 并订阅 DAO flow, 通过 [dispatch] 处理 UI 事件。
 * 宿主 Activity 只负责平台专属行为 (对话框/文件/帮助), 状态与数据操作全部在本类完成。
 */
class ReplaceRuleScreenModel : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ReplaceRuleUiState())
    val state: StateFlow<ReplaceRuleUiState> = _state.asStateFlow()

    // DB 数据变更后通知宿主 setResult(RESULT_OK)
    private val _dataModified = MutableStateFlow(false)
    val dataModified: StateFlow<Boolean> = _dataModified.asStateFlow()

    private var dataInit = false
    private var flowJob: Job? = null

    init {
        observeRules()
        observeGroups()
    }

    private fun observeRules() {
        flowJob?.cancel()
        dataInit = false
        flowJob = scope.launch {
            val flow = when (val q = _state.value.query) {
                ReplaceQuery.All -> appDb.replaceRuleDao.flowAll()
                ReplaceQuery.NoGroup -> appDb.replaceRuleDao.flowNoGroup()
                is ReplaceQuery.Group -> appDb.replaceRuleDao.flowGroupSearch("%${q.name}%")
                is ReplaceQuery.Keyword -> appDb.replaceRuleDao.flowSearch("%${q.key}%")
            }
            flow.catch {
                AppLog.put("替换规则管理界面更新数据出错", it)
            }.flowOn(IoDispatcher).conflate().collect { list ->
                if (dataInit) _dataModified.value = true
                val selected = _state.value.selected.intersect(list.map { it.id }.toSet())
                _state.value = _state.value.copy(rules = list, selected = selected)
                dataInit = true
            }
        }
    }

    private fun observeGroups() {
        scope.launch {
            appDb.replaceRuleDao.flowGroups().conflate().collect {
                _state.value = _state.value.copy(groups = it)
            }
        }
    }

    fun dispatch(event: ReplaceRuleUiEvent) {
        when (event) {
            is ReplaceRuleUiEvent.SetQuery -> {
                _state.value = _state.value.copy(query = event.query)
                observeRules()
            }

            is ReplaceRuleUiEvent.ToggleSelect -> {
                val cur = _state.value.selected
                _state.value = _state.value.copy(
                    selected = if (event.checked) cur + event.item.id else cur - event.item.id
                )
            }

            is ReplaceRuleUiEvent.SelectAll -> {
                _state.value = _state.value.copy(
                    selected = if (event.all) _state.value.rules.map { it.id }
                        .toSet() else emptySet()
                )
            }

            ReplaceRuleUiEvent.RevertSelection -> {
                _state.value = _state.value.copy(
                    selected = _state.value.rules.map { it.id }.toSet() - _state.value.selected
                )
            }

            is ReplaceRuleUiEvent.Delete -> {
                scope.launch(IoDispatcher) {
                    appDb.replaceRuleDao.delete(event.rule)
                }
            }

            ReplaceRuleUiEvent.DeleteSelection -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    appDb.replaceRuleDao.delete(*selection.toTypedArray())
                }
            }

            is ReplaceRuleUiEvent.Update -> {
                scope.launch(IoDispatcher) {
                    appDb.replaceRuleDao.update(event.rule)
                }
            }

            is ReplaceRuleUiEvent.ToTop -> {
                scope.launch(IoDispatcher) {
                    event.rule.order = appDb.replaceRuleDao.minOrder() - 1
                    appDb.replaceRuleDao.update(event.rule)
                }
            }

            is ReplaceRuleUiEvent.ToBottom -> {
                scope.launch(IoDispatcher) {
                    event.rule.order = appDb.replaceRuleDao.maxOrder() + 1
                    appDb.replaceRuleDao.update(event.rule)
                }
            }

            ReplaceRuleUiEvent.TopSelect -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    var minOrder = appDb.replaceRuleDao.minOrder() - selection.size
                    selection.forEach { it.order = ++minOrder }
                    appDb.replaceRuleDao.update(*selection.toTypedArray())
                }
            }

            ReplaceRuleUiEvent.BottomSelect -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    var maxOrder = appDb.replaceRuleDao.maxOrder()
                    selection.forEach { it.order = maxOrder++ }
                    appDb.replaceRuleDao.update(*selection.toTypedArray())
                }
            }

            ReplaceRuleUiEvent.EnableSelection -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    val array = Array(selection.size) { selection[it].copy(isEnabled = true) }
                    appDb.replaceRuleDao.update(*array)
                }
            }

            ReplaceRuleUiEvent.DisableSelection -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    val array = Array(selection.size) { selection[it].copy(isEnabled = false) }
                    appDb.replaceRuleDao.update(*array)
                }
            }

            is ReplaceRuleUiEvent.Move -> {
                val list =
                    _state.value.rules.toMutableList().apply { add(event.to, removeAt(event.from)) }
                _state.value = _state.value.copy(rules = list)
            }

            ReplaceRuleUiEvent.PersistOrder -> {
                val rules = _state.value.rules
                rules.forEachIndexed { index, item -> item.order = index + 1 }
                scope.launch(IoDispatcher) {
                    appDb.replaceRuleDao.update(*rules.toTypedArray())
                }
            }
        }
    }

    fun selection(): List<ReplaceRule> =
        _state.value.rules.filter { _state.value.selected.contains(it.id) }

    override fun onCleared() {
        scope.cancel()
    }
}

data class ReplaceRuleUiState(
    val rules: List<ReplaceRule> = emptyList(),
    val groups: List<String> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val query: ReplaceQuery = ReplaceQuery.All,
)

sealed interface ReplaceRuleUiEvent {
    data class SetQuery(val query: ReplaceQuery) : ReplaceRuleUiEvent
    data class ToggleSelect(val item: ReplaceRule, val checked: Boolean) : ReplaceRuleUiEvent
    data class SelectAll(val all: Boolean) : ReplaceRuleUiEvent
    object RevertSelection : ReplaceRuleUiEvent
    data class Delete(val rule: ReplaceRule) : ReplaceRuleUiEvent
    object DeleteSelection : ReplaceRuleUiEvent
    data class Update(val rule: ReplaceRule) : ReplaceRuleUiEvent
    data class ToTop(val rule: ReplaceRule) : ReplaceRuleUiEvent
    data class ToBottom(val rule: ReplaceRule) : ReplaceRuleUiEvent
    object TopSelect : ReplaceRuleUiEvent
    object BottomSelect : ReplaceRuleUiEvent
    object EnableSelection : ReplaceRuleUiEvent
    object DisableSelection : ReplaceRuleUiEvent
    data class Move(val from: Int, val to: Int) : ReplaceRuleUiEvent
    object PersistOrder : ReplaceRuleUiEvent
}
