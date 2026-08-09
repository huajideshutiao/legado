package io.legado.app.ui.association

import io.legado.app.model.script.JsEngines
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.screenModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JS 编辑页 shared ScreenModel: 托管 [JsEditUiState]。
 *
 * app 端无独立 JsEditActivity (JS 编辑分散在 BookSourceEdit / CodeDialog 等),
 * 本类作为通用 JS 代码编辑器状态宿主, 桌面/iOS 端可直接复用。
 *
 * - 编辑状态 (code/fileName/saved) 由本类管理
 * - Run: 走 [JsEngines] 一次性 eval (commonMain 共享引擎抽象), 结果/异常回写 state
 * - Save: 仅标记 saved=true, 实际持久化 (文件系统/数据库) 由宿主通过 [JsEditUiActions.onSave] 实现
 */
class JsEditScreenModel : ScreenModel {

    // 自管 scope (与 ReadRecordScreenModel 一致, 由 ScreenModelStore 调 onCleared)
    private val scope = screenModelScope("JS编辑")

    private val _state = MutableStateFlow(JsEditUiState())
    val state: StateFlow<JsEditUiState> = _state.asStateFlow()

    fun dispatch(event: JsEditUiEvent) {
        when (event) {
            is JsEditUiEvent.CodeChange -> {
                _state.update { it.copy(code = event.code, saved = false) }
            }

            is JsEditUiEvent.FileNameChange -> {
                _state.update { it.copy(fileName = event.fileName, saved = false) }
            }

            JsEditUiEvent.Run -> runJs()

            JsEditUiEvent.ClearResult -> {
                _state.update { it.copy(result = null, error = null) }
            }
        }
    }

    // 标记已保存 (宿主 onSave 成功后调用)
    fun markSaved() {
        _state.update { it.copy(saved = true) }
    }

    private fun runJs() {
        val code = _state.value.code
        if (code.isBlank()) return
        _state.update { it.copy(isRunning = true, result = null, error = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    JsEngines.get().eval(code)
                }
                _state.update {
                    it.copy(isRunning = false, result = result?.toString(), error = null)
                }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(isRunning = false, result = null, error = e)
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}

/**
 * JS 编辑页 UI 状态 (immutable)。
 *
 * @param code 当前编辑的 JS 源码
 * @param fileName 文件名 (用于保存提示, 不含路径)
 * @param saved 自上次编辑后是否已保存 (CodeChange/FileNameChange 重置为 false)
 * @param result JS 执行返回值的字符串表示, null 表示无运行结果
 * @param error JS 执行抛出的异常, null 表示无错误
 * @param isRunning 是否正在执行 JS (Run 期间置 true, 禁用运行按钮)
 */
data class JsEditUiState(
    val code: String = "",
    val fileName: String = "",
    val saved: Boolean = false,
    val result: String? = null,
    val error: Throwable? = null,
    val isRunning: Boolean = false,
)

/** JS 编辑页可下沉到 shared 层处理的 UI 事件 */
sealed interface JsEditUiEvent {
    /** 代码内容变更 */
    data class CodeChange(val code: String) : JsEditUiEvent

    /** 文件名变更 */
    data class FileNameChange(val fileName: String) : JsEditUiEvent

    /** 执行 JS (走 JsEngines 一次性 eval) */
    data object Run : JsEditUiEvent

    /** 清除上次运行结果/错误 */
    data object ClearResult : JsEditUiEvent
}
