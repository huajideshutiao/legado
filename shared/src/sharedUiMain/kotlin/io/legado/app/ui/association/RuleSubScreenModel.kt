package io.legado.app.ui.association

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * 规则订阅页 ScreenModel (shared sharedUiMain)。
 *
 * 持有 [RuleSubUiState] 的 [MutableStateFlow], 订阅 ruleSubDao.flowAll,
 * 通过 [dispatch] 处理 save/delete/move/persistOrder/toTop/toBottom 事件,
 * 平台相关回调 (alert 编辑弹窗 / openSubscription / ImportXxxDialog) 仍由 Activity 实现。
 */
class RuleSubScreenModel : ScreenModel {

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    private val shared = RuleSubViewModelShared(scope)

    private val _uiState = MutableStateFlow(RuleSubUiState())
    val uiState: StateFlow<RuleSubUiState> = _uiState.asStateFlow()

    init {
        AppDbProviders.get().ruleSubDao.flowAll()
            .catch {
                AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            }
            .flowOn(IoDispatcher)
            .conflate()
            .onEach { subs ->
                _uiState.update { it.copy(ruleSubs = subs) }
            }
            .launchIn(scope)
    }

    fun dispatch(event: RuleSubUiEvent) {
        when (event) {
            is RuleSubUiEvent.Save -> shared.save(event.ruleSub)
            is RuleSubUiEvent.Delete -> shared.delete(event.ruleSub)
            is RuleSubUiEvent.Move -> _uiState.update { state ->
                state.copy(ruleSubs = state.ruleSubs.toMutableList().apply {
                    add(event.to, removeAt(event.from))
                })
            }

            RuleSubUiEvent.PersistOrder -> shared.upOrder(_uiState.value.ruleSubs)
            is RuleSubUiEvent.ToTop -> shared.toTop(event.ruleSub)
            is RuleSubUiEvent.ToBottom -> shared.toBottom(event.ruleSub)
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

sealed interface RuleSubUiEvent {
    data class Save(val ruleSub: RuleSub) : RuleSubUiEvent
    data class Delete(val ruleSub: RuleSub) : RuleSubUiEvent
    data class Move(val from: Int, val to: Int) : RuleSubUiEvent
    object PersistOrder : RuleSubUiEvent
    data class ToTop(val ruleSub: RuleSub) : RuleSubUiEvent
    data class ToBottom(val ruleSub: RuleSub) : RuleSubUiEvent
}
