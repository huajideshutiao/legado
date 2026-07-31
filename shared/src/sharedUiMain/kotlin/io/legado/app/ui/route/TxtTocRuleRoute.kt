package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultDataShared
import io.legado.app.ui.book.toc.rule.TxtTocRuleScreen
import io.legado.app.ui.book.toc.rule.TxtTocRuleScreenModel
import io.legado.app.ui.book.toc.rule.TxtTocRuleUiActions
import io.legado.app.ui.book.toc.rule.TxtTocRuleUiEvent
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson

/**
 * TXT 目录规则管理 shared 路由入口。
 * 通过 [ScreenModelStore] 复用 [TxtTocRuleScreenModel], 渲染 [TxtTocRuleScreen]。
 */
@Composable
fun TxtTocRuleRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        TxtTocRuleScreenModel(importDefaultRules = { DefaultDataShared.importDefaultTocRules() })
    }
    val state by screenModel.state.collectAsState()

    val actions = remember(screenModel, navigator) {
        object : TxtTocRuleUiActions {
            override fun onBack() {
                navigator.pop()
            }

            // 对话框类: 规则编辑/在线导入/本地导入/帮助均走 Overlay, 由 shared OverlayContentHost 渲染
            override fun onAddRule() {
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleEdit"))
            }

            override fun onEditRule(item: TxtTocRule) {
                navigator.showOverlay(
                    AppOverlay.Dialog(key = "txtTocRuleEdit", payload = item.id.toString())
                )
            }

            override fun onImportLocal() {
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleImportLocal"))
            }

            override fun onImportOnline() {
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleImportOnline"))
            }

            override fun onHelp() {
                navigator.showOverlay(AppOverlay.Dialog(key = "help", payload = "txtTocRuleHelp"))
            }

            // dispatch 类: 状态与数据操作全部委托 ScreenModel
            override fun onImportDefault() {
                screenModel.dispatch(TxtTocRuleUiEvent.ImportDefault)
            }

            override fun onToggleSelect(item: TxtTocRule, checked: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.ToggleSelect(item, checked))
            }

            override fun onSelectAll(all: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.SelectAll(all))
            }

            override fun onRevertSelection() {
                screenModel.dispatch(TxtTocRuleUiEvent.RevertSelection)
            }

            override fun onDelSelection() {
                screenModel.dispatch(TxtTocRuleUiEvent.DelSelection)
            }

            override fun onDel(item: TxtTocRule) {
                screenModel.dispatch(TxtTocRuleUiEvent.Del(item))
            }

            override fun onEnableSelection(enabled: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.EnableSelection(enabled))
            }

            override fun onMove(from: Int, to: Int) {
                screenModel.dispatch(TxtTocRuleUiEvent.Move(from, to))
            }

            override fun onPersistOrder() {
                screenModel.dispatch(TxtTocRuleUiEvent.PersistOrder)
            }

            override fun onToTop(item: TxtTocRule) {
                screenModel.dispatch(TxtTocRuleUiEvent.ToTop(item))
            }

            override fun onToBottom(item: TxtTocRule) {
                screenModel.dispatch(TxtTocRuleUiEvent.ToBottom(item))
            }

            override fun onEnableRule(item: TxtTocRule, enabled: Boolean) {
                screenModel.dispatch(TxtTocRuleUiEvent.EnableRule(item, enabled))
            }

            // payload 为选中规则 JSON, 平台走 HandleFileContract.EXPORT + showExportSuccess
            override fun onExportSelection() {
                val json = GSON.toJson(screenModel.selection())
                navigator.showOverlay(AppOverlay.Dialog(key = "txtTocRuleExport", payload = json))
            }
        }
    }

    TxtTocRuleScreen(state = state, actions = actions)
}
