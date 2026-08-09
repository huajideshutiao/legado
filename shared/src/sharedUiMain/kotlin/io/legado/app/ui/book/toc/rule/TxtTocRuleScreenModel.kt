package io.legado.app.ui.book.toc.rule

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * TXT 目录规则页 ScreenModel (KMP 版, sharedUiMain 共享)。
 *
 * 持有 [TxtTocRuleUiState] 并订阅 DAO flow, 通过 [dispatch] 处理 UI 事件。
 * 宿主 Activity 只负责平台专属行为 (对话框/文件/帮助/默认规则导入 lambda),
 * 状态与数据操作全部在本类完成。
 *
 * @param importDefaultRules 平台专属: 导入默认 TxtToc 规则
 *   (app 端用 `DefaultData.importDefaultTocRules()`)
 */
class TxtTocRuleScreenModel(
    private val importDefaultRules: suspend () -> Unit,
) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    private val scope = screenModelScope("TXT目录规则")

    private val _state = MutableStateFlow(TxtTocRuleUiState())
    val state: StateFlow<TxtTocRuleUiState> = _state.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        scope.launch {
            appDb.txtTocRuleDao.observeAll()
                .catch {
                    AppLog.put("TXT目录规则界面获取数据失败\n${it.message}", it)
                }.flowOn(IoDispatcher).conflate().collect { rules ->
                    val selected = _state.value.selected.intersect(rules.map { it.id }.toSet())
                    _state.value = _state.value.copy(tocRules = rules, selected = selected)
                }
        }
    }

    fun dispatch(event: TxtTocRuleUiEvent) {
        when (event) {
            is TxtTocRuleUiEvent.ToggleSelect -> {
                val cur = _state.value.selected
                _state.value = _state.value.copy(
                    selected = if (event.checked) cur + event.item.id else cur - event.item.id
                )
            }

            is TxtTocRuleUiEvent.SelectAll -> {
                _state.value = _state.value.copy(
                    selected = if (event.all) _state.value.tocRules.map { it.id }
                        .toSet() else emptySet()
                )
            }

            TxtTocRuleUiEvent.RevertSelection -> {
                _state.value = _state.value.copy(
                    selected = _state.value.tocRules.map { it.id }.toSet() - _state.value.selected
                )
            }

            TxtTocRuleUiEvent.DelSelection -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    appDb.txtTocRuleDao.delete(*selection.toTypedArray())
                }
            }

            is TxtTocRuleUiEvent.Del -> {
                scope.launch(IoDispatcher) {
                    appDb.txtTocRuleDao.delete(event.item)
                }
            }

            is TxtTocRuleUiEvent.EnableSelection -> {
                val selection = selection()
                scope.launch(IoDispatcher) {
                    val array = selection.map { it.copy(enable = event.enabled) }.toTypedArray()
                    appDb.txtTocRuleDao.insert(*array)
                }
            }

            is TxtTocRuleUiEvent.Move -> {
                val list = _state.value.tocRules.toMutableList()
                    .apply { add(event.to, removeAt(event.from)) }
                _state.value = _state.value.copy(tocRules = list)
            }

            TxtTocRuleUiEvent.PersistOrder -> {
                val rules = _state.value.tocRules
                rules.forEachIndexed { index, item -> item.serialNumber = index + 1 }
                scope.launch(IoDispatcher) {
                    appDb.txtTocRuleDao.update(*rules.toTypedArray())
                }
            }

            is TxtTocRuleUiEvent.ToTop -> {
                scope.launch(IoDispatcher) {
                    event.item.serialNumber = appDb.txtTocRuleDao.minOrder() - 1
                    appDb.txtTocRuleDao.update(event.item)
                }
            }

            is TxtTocRuleUiEvent.ToBottom -> {
                scope.launch(IoDispatcher) {
                    event.item.serialNumber = appDb.txtTocRuleDao.maxOrder() + 1
                    appDb.txtTocRuleDao.update(event.item)
                }
            }

            is TxtTocRuleUiEvent.EnableRule -> {
                scope.launch(IoDispatcher) {
                    event.item.enable = event.enabled
                    appDb.txtTocRuleDao.update(event.item)
                }
            }

            is TxtTocRuleUiEvent.Save -> {
                scope.launch(IoDispatcher) {
                    appDb.txtTocRuleDao.insert(event.rule)
                }
            }

            TxtTocRuleUiEvent.ImportDefault -> {
                scope.launch(IoDispatcher) {
                    importDefaultRules.invoke()
                }
            }
        }
    }

    fun selection(): List<TxtTocRule> =
        _state.value.tocRules.filter { _state.value.selected.contains(it.id) }

    override fun onCleared() {
        scope.cancel()
    }
}

sealed interface TxtTocRuleUiEvent {
    data class ToggleSelect(val item: TxtTocRule, val checked: Boolean) : TxtTocRuleUiEvent
    data class SelectAll(val all: Boolean) : TxtTocRuleUiEvent
    object RevertSelection : TxtTocRuleUiEvent
    object DelSelection : TxtTocRuleUiEvent
    data class Del(val item: TxtTocRule) : TxtTocRuleUiEvent
    data class EnableSelection(val enabled: Boolean) : TxtTocRuleUiEvent
    data class Move(val from: Int, val to: Int) : TxtTocRuleUiEvent
    object PersistOrder : TxtTocRuleUiEvent
    data class ToTop(val item: TxtTocRule) : TxtTocRuleUiEvent
    data class ToBottom(val item: TxtTocRule) : TxtTocRuleUiEvent
    data class EnableRule(val item: TxtTocRule, val enabled: Boolean) : TxtTocRuleUiEvent
    data class Save(val rule: TxtTocRule) : TxtTocRuleUiEvent
    object ImportDefault : TxtTocRuleUiEvent
}
