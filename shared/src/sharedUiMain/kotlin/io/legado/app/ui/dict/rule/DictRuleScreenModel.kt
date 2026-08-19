package io.legado.app.ui.dict.rule

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 字典规则管理页 ScreenModel (KMP 版, sharedUiMain 共享)。
 *
 * 对照 app 端原 [DictRuleActivity] 的 `mutableStateOf` 状态 + [DictRuleViewModel] 业务,
 * 下沉为 shared [ScreenModel]:
 * - state (规则列表 + 选中集合) 用 [MutableStateFlow], Compose 宿主用 `collectAsState` 订阅
 * - init 订阅 [AppDbProviders.get].dictRuleDao.flowAll 自动更新 state (含 selected 清理)
 * - [dispatch] 统一入口处理业务事件 (delete/update/enableSelection/disableSelection/
 *   importDefault/persistOrder/moveItem/toggleSelected/selectAll/revertSelection)
 * - 平台专属逻辑 (DefaultData/HandleFileContract/showDialogFragment/showHelp 等)
 *   通过 [DictRuleUiActions] 留在 Activity, 业务编排复用本类
 *
 * @param scope 协程作用域 (app 端传 lifecycleScope, 桌面端可传 rememberCoroutineScope)
 * @param importDefaultRules 平台专属: 导入默认字典规则
 *   (app 端 `DefaultData.importDefaultDictRules()`)
 */
class DictRuleScreenModel(
    private val scope: CoroutineScope,
    private val importDefaultRules: suspend () -> Unit,
) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    private val _state = MutableStateFlow(DictRuleUiState())
    val state: StateFlow<DictRuleUiState> = _state.asStateFlow()

    init {
        scope.launch {
            appDb.dictRuleDao.flowAll()
                .catch {
                    AppLog.put("字典规则获取数据失败\n${it.message}", it)
                }
                .flowOn(IoDispatcher)
                .collect { rules ->
                    // 落库回推后清理已不存在的选中项
                    val names = rules.map { it.name }.toSet()
                    _state.value = DictRuleUiState(
                        dictRules = rules,
                        selected = _state.value.selected.intersect(names),
                    )
                }
        }
    }

    fun dispatch(event: DictRuleUiEvent) {
        when (event) {
            is DictRuleUiEvent.Update -> update(*event.rules.toTypedArray())
            is DictRuleUiEvent.Delete -> delete(*event.rules.toTypedArray())
            is DictRuleUiEvent.EnableSelection -> enableSelection(*event.rules.toTypedArray())
            is DictRuleUiEvent.DisableSelection -> disableSelection(*event.rules.toTypedArray())
            DictRuleUiEvent.ImportDefault -> importDefault()
            DictRuleUiEvent.PersistOrder -> persistOrder()
            is DictRuleUiEvent.MoveItem -> moveItem(event.from, event.to)
            is DictRuleUiEvent.ToggleSelected -> toggleSelected(event.item, event.checked)
            is DictRuleUiEvent.SelectAll -> selectAll(event.all)
            DictRuleUiEvent.RevertSelection -> revertSelection()
        }
    }

    /** 当前选中规则 (对照 app 端 DictRuleActivity.selection())。 */
    fun selection(): List<DictRule> {
        val cur = _state.value
        return cur.dictRules.filter { cur.selected.contains(it.name) }
    }

    // ===== 业务写入 (对照 DictRuleViewModelShared) =====

    private fun update(vararg dictRule: DictRule) {
        scope.launch(IoDispatcher) {
            try {
                appDb.dictRuleDao.update(*dictRule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = "更新字典规则出错\n${e.message}"
                AppLog.put(msg, e)
                Toasters.get().toast(msg)
            }
        }
    }

    private fun delete(vararg dictRule: DictRule) {
        scope.launch(IoDispatcher) {
            try {
                appDb.dictRuleDao.delete(*dictRule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = "删除字典规则出错\n${e.message}"
                AppLog.put(msg, e)
                Toasters.get().toast(msg)
            }
        }
    }

    private fun enableSelection(vararg dictRule: DictRule) {
        scope.launch(IoDispatcher) {
            val array = dictRule.map { it.copy(enabled = true) }.toTypedArray()
            appDb.dictRuleDao.insert(*array)
        }
    }

    private fun disableSelection(vararg dictRule: DictRule) {
        scope.launch(IoDispatcher) {
            val array = dictRule.map { it.copy(enabled = false) }.toTypedArray()
            appDb.dictRuleDao.insert(*array)
        }
    }

    private fun importDefault() {
        scope.launch(IoDispatcher) {
            importDefaultRules.invoke()
        }
    }

    // ===== UI 选中/排序状态 (对照 Activity 原 mutableStateOf 操作) =====

    private fun toggleSelected(item: DictRule, checked: Boolean) {
        val cur = _state.value
        _state.value = cur.copy(
            selected = if (checked) cur.selected + item.name else cur.selected - item.name
        )
    }

    private fun selectAll(all: Boolean) {
        val cur = _state.value
        _state.value = cur.copy(
            selected = if (all) cur.dictRules.map { r -> r.name }.toSet() else emptySet()
        )
    }

    private fun revertSelection() {
        val cur = _state.value
        _state.value = cur.copy(
            selected = cur.dictRules.map { r -> r.name }.toSet() - cur.selected
        )
    }

    private fun moveItem(from: Int, to: Int) {
        val cur = _state.value
        _state.value = cur.copy(
            dictRules = cur.dictRules.toMutableList().apply { add(to, removeAt(from)) }
        )
    }

    private fun persistOrder() {
        val rules = _state.value.dictRules
        rules.forEachIndexed { index, item -> item.sortNumber = index + 1 }
        update(*rules.toTypedArray())
    }
}

/**
 * 字典规则管理页 UI 事件 (供 [DictRuleScreenModel.dispatch] 处理)。
 *
 * 业务事件: [Update]/[Delete]/[EnableSelection]/[DisableSelection]/
 *           [ImportDefault]/[PersistOrder]
 * UI 状态事件: [ToggleSelected]/[SelectAll]/[RevertSelection]/[MoveItem]
 */
sealed interface DictRuleUiEvent {
    /** 更新规则 (单条 enabled 切换 / 编辑后保存)。 */
    data class Update(val rules: List<DictRule>) : DictRuleUiEvent

    /** 删除规则 (单条删除确认 / 批量删除选中)。 */
    data class Delete(val rules: List<DictRule>) : DictRuleUiEvent

    /** 启用选中项。 */
    data class EnableSelection(val rules: List<DictRule>) : DictRuleUiEvent

    /** 禁用选中项。 */
    data class DisableSelection(val rules: List<DictRule>) : DictRuleUiEvent

    /** 导入默认规则 (平台专属逻辑通过 lambda 注入 ScreenModel)。 */
    object ImportDefault : DictRuleUiEvent

    /** 拖拽即时换位 (仅改 state, 不写库)。 */
    data class MoveItem(val from: Int, val to: Int) : DictRuleUiEvent

    /** 松手落库排序号。 */
    object PersistOrder : DictRuleUiEvent

    /** 切换单条选中态。 */
    data class ToggleSelected(val item: DictRule, val checked: Boolean) : DictRuleUiEvent

    /** 全选/全不选。 */
    data class SelectAll(val all: Boolean) : DictRuleUiEvent

    /** 反选。 */
    object RevertSelection : DictRuleUiEvent
}
