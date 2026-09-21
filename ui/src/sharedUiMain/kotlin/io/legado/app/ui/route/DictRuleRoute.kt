package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.data.entities.DictRule
import io.legado.app.help.DefaultDataShared
import io.legado.app.ui.dict.rule.DictRuleScreen
import io.legado.app.ui.dict.rule.DictRuleScreenModel
import io.legado.app.ui.dict.rule.DictRuleUiActions
import io.legado.app.ui.dict.rule.DictRuleUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson

/**
 * 字典规则管理路由入口 (shared 下沉版)。
 *
 * 对照 app 端 DictRuleActivity 的路由装配, 复用 shared [DictRuleScreen] +
 * [DictRuleScreenModel]; 平台专属逻辑 (HandleFileContract/showDialogFragment/
 * showHelp 等) 通过 [AppOverlay.Dialog] 交由 shared OverlayContentHost 渲染,
 * 默认规则导入走 [DefaultDataShared], 业务事件走 [DictRuleScreenModel.dispatch]。
 */
@Composable
fun DictRuleRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val scope = rememberCoroutineScope()
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        DictRuleScreenModel(
            scope = scope,
            importDefaultRules = { DefaultDataShared.importDefaultDictRules() },
        )
    }
    val state by screenModel.state.collectAsState()
    val actions = remember(navigator) {
        object : DictRuleUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // payload 为 null 表示新建规则
            override fun onAddRule() {
                navigator.showOverlay(AppOverlay.Dialog(key = "dictRuleEdit"))
            }

            // payload 传规则 name, 平台据此初始化编辑弹窗
            override fun onEditRule(name: String) {
                navigator.showOverlay(AppOverlay.Dialog(key = "dictRuleEdit", payload = name))
            }

            // 平台渲染文件选择 + ImportDictRuleDialog
            override fun onImportLocal() {
                navigator.showOverlay(AppOverlay.Dialog(key = "dictRuleImportLocal"))
            }

            // 平台渲染 OnlineImportUrlDialog (ACache 历史) + ImportDictRuleDialog
            override fun onImportOnline() {
                navigator.showOverlay(AppOverlay.Dialog(key = "dictRuleImportOnline"))
            }

            override fun onImportDefault() {
                screenModel.dispatch(DictRuleUiEvent.ImportDefault)
            }

            // 平台渲染 showHelp("dictRuleHelp")
            override fun onHelp() {
                navigator.showOverlay(AppOverlay.Dialog(key = "dictRuleHelp"))
            }

            override fun onToggleSelected(item: DictRule, checked: Boolean) {
                screenModel.dispatch(DictRuleUiEvent.ToggleSelected(item, checked))
            }

            override fun onSelectAll(all: Boolean) {
                screenModel.dispatch(DictRuleUiEvent.SelectAll(all))
            }

            override fun onRevertSelection() {
                screenModel.dispatch(DictRuleUiEvent.RevertSelection)
            }

            override fun onMoveItem(from: Int, to: Int) {
                screenModel.dispatch(DictRuleUiEvent.MoveItem(from, to))
            }

            override fun onPersistOrder() {
                screenModel.dispatch(DictRuleUiEvent.PersistOrder)
            }

            override fun onUpdateRuleEnabled(item: DictRule, enabled: Boolean) {
                screenModel.dispatch(DictRuleUiEvent.Update(listOf(item.copy(enabled = enabled))))
            }

            override fun onDeleteRule(rule: DictRule) {
                screenModel.dispatch(DictRuleUiEvent.Delete(listOf(rule)))
            }

            override fun onDeleteSelection() {
                screenModel.dispatch(DictRuleUiEvent.Delete(screenModel.selection()))
            }

            override fun onEnableSelection() {
                screenModel.dispatch(DictRuleUiEvent.EnableSelection(screenModel.selection()))
            }

            override fun onDisableSelection() {
                screenModel.dispatch(DictRuleUiEvent.DisableSelection(screenModel.selection()))
            }

            // payload 为选中规则 JSON, 平台走 HandleFileContract.EXPORT + showExportSuccess
            override fun onExportSelection() {
                val json = GSON.toJson(screenModel.selection())
                navigator.showOverlay(AppOverlay.Dialog(key = "dictRuleExport", payload = json))
            }
        }
    }
    DictRuleScreen(state = state, actions = actions)
}
