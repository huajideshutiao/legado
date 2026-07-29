package io.legado.app.ui.replace.edit

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.ScreenModel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 替换规则编辑页 ScreenModel (KMP 版, sharedUiMain 共享)。
 *
 * 持有 [ReplaceEditUiState] 并通过 [dispatch] 处理 UI 事件。
 * 宿主 Activity 负责表单字段 (FieldState) + 平台专属行为 (剪贴板/帮助/对话框),
 * 数据加载/保存/粘贴等业务在本类完成。
 *
 * @param clipTextProvider 剪贴板文本提供者 (app 端 { getClipText() }, desktop 端 AWT Clipboard)
 */
class ReplaceEditScreenModel(
    private val clipTextProvider: () -> String?,
) : ScreenModel {

    private val appDb get() = AppDbProviders.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ReplaceEditUiState())
    val state: StateFlow<ReplaceEditUiState> = _state.asStateFlow()

    fun dispatch(event: ReplaceEditUiEvent) {
        when (event) {
            is ReplaceEditUiEvent.Init -> {
                Coroutine.async(scope = scope) {
                    if (event.id > 0) {
                        appDb.replaceRuleDao.findById(event.id)
                    } else {
                        val patternStr = event.pattern ?: ""
                        ReplaceRule(
                            name = patternStr,
                            pattern = patternStr,
                            isRegex = event.isRegex,
                            scope = event.scope,
                        )
                    }
                }.onSuccess { rule ->
                    // findById 可能返回 null (规则已被删), 对齐 ReplaceEditViewModelShared 的空跳过策略
                    if (rule == null) return@onSuccess
                    _state.value = _state.value.copy(
                        replaceRule = rule,
                        isAdd = event.id <= 0,
                        showRegular = rule.isRegex,
                    )
                    event.onLoaded(rule)
                }
            }

            is ReplaceEditUiEvent.Save -> {
                Coroutine.async(scope = scope) {
                    event.rule.checkValid()
                    if (event.rule.order == Int.MIN_VALUE) {
                        event.rule.order = appDb.replaceRuleDao.maxOrder() + 1
                    }
                    appDb.replaceRuleDao.insert(event.rule)
                }.onSuccess {
                    event.onSuccess()
                }.onError {
                    Toasters.get().toast("save error, ${it.message}")
                    it.printStackTraceOnDebug()
                }
            }

            ReplaceEditUiEvent.Delete -> {
                val rule = _state.value.replaceRule ?: return
                scope.launch(IoDispatcher) {
                    appDb.replaceRuleDao.delete(rule)
                }
            }

            is ReplaceEditUiEvent.ToggleRegular -> {
                _state.value = _state.value.copy(showRegular = event.useRegex)
            }

            is ReplaceEditUiEvent.PasteRule -> {
                Coroutine.async(scope = scope) {
                    val text = clipTextProvider()
                    if (text.isNullOrBlank()) {
                        throw NoStackTraceException("剪贴板为空")
                    }
                    GSON.fromJsonObject<ReplaceRule>(text).getOrNull()
                        ?: throw NoStackTraceException("格式不对")
                }.onSuccess { rule ->
                    _state.value = _state.value.copy(
                        replaceRule = rule,
                        showRegular = rule.isRegex,
                    )
                    event.onSuccess(rule)
                }.onError {
                    Toasters.get().toast(it.message ?: "Error")
                    it.printStackTraceOnDebug()
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

data class ReplaceEditUiState(
    val replaceRule: ReplaceRule? = null,
    val isAdd: Boolean = true,
    val showRegular: Boolean = false,
)

sealed interface ReplaceEditUiEvent {
    data class Init(
        val id: Long,
        val pattern: String?,
        val isRegex: Boolean,
        val scope: String?,
        val onLoaded: (ReplaceRule) -> Unit,
    ) : ReplaceEditUiEvent

    data class Save(val rule: ReplaceRule, val onSuccess: () -> Unit) : ReplaceEditUiEvent

    object Delete : ReplaceEditUiEvent

    data class ToggleRegular(val useRegex: Boolean) : ReplaceEditUiEvent

    data class PasteRule(val onSuccess: (ReplaceRule) -> Unit) : ReplaceEditUiEvent
}
