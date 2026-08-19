package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.association.JsEditScreen
import io.legado.app.ui.association.JsEditScreenModel
import io.legado.app.ui.association.JsEditUiActions
import io.legado.app.ui.association.JsEditUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JS 编辑 shared 路由入口。
 *
 * AppRoute.JsEdit 为 data object (无参数), 通过 [ScreenModelStore] 复用
 * [JsEditScreenModel], 渲染 [JsEditScreen]。
 *
 * onSave 走 [PlatformServiceProviders.files.saveFile] + [BackupFileOps.writeText]
 * (与 BookmarkRoute / ReplaceRuleRoute 同一持久化模式);
 * Run 由 [JsEditScreenModel] 内部走 JsEngines eval, 无需宿主接入。
 */
@Composable
fun JsEditRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val scope = rememberCoroutineScope()
    val screenModel = screenModelStore.getOrCreateTyped(entry) { JsEditScreenModel() }
    val state by screenModel.state.collectAsState()

    val actions = object : JsEditUiActions {
        // 返回栈由导航器统一管理
        override fun onBack() {
            navigator.pop()
        }

        // 持久化 JS 代码到文件: 平台选择器选保存位置 + BackupFileOps 写入
        override fun onSave(code: String, fileName: String) {
            // 兜底文件名: 空默认 edit.js; 非 .js 后缀追加 .js
            val safeName = when {
                fileName.isBlank() -> "edit.js"
                fileName.endsWith(".js", ignoreCase = true) -> fileName
                else -> "$fileName.js"
            }
            scope.launch {
                try {
                    val path = withContext(IoDispatcher) {
                        PlatformServiceProviders.get().files.saveFile(safeName)
                    } ?: return@launch
                    withContext(IoDispatcher) {
                        BackupFileOps.writeText(path, code)
                    }
                    screenModel.markSaved()
                    Toasters.get().toast("保存成功")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("保存失败\n${e.message}", e, true)
                }
            }
        }
    }

    JsEditScreen(
        state = state,
        actions = actions,
        onRun = { screenModel.dispatch(JsEditUiEvent.Run) },
        onCodeChange = { screenModel.dispatch(JsEditUiEvent.CodeChange(it)) },
        onFileNameChange = { screenModel.dispatch(JsEditUiEvent.FileNameChange(it)) },
    )
}
