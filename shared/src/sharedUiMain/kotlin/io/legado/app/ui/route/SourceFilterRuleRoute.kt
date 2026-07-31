package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.ui.book.filter.SourceFilterRuleScreen
import io.legado.app.ui.book.filter.SourceFilterRuleScreenModel
import io.legado.app.ui.book.filter.SourceFilterRuleUiActions
import io.legado.app.ui.book.filter.SourceFilterRuleUiEvent
import io.legado.app.ui.compose.platform.AppBackHandler
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.RouteResultPayload
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson

/**
 * AppRoute.SourceFilterRule 路由下沉入口: 桥接 [SourceFilterRuleScreenModel] 状态与
 * [SourceFilterRuleScreen] 渲染。
 *
 * 平台相关回调 (编辑/新增/导入/导出) 全部走 [AppOverlay.Dialog], 由 shared OverlayContentHost
 * 渲染对应 [io.legado.app.ui.book.filter.SourceFilterEditDialog] / 文件选择 /
 * ImportSourceFilterRuleDialog / 在线导入 URL 弹窗 (ACache 历史)。
 */
@Composable
fun SourceFilterRuleRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        SourceFilterRuleScreenModel()
    }
    val state by screenModel.state.collectAsState()

    val actions = remember(screenModel, navigator) {
        object : SourceFilterRuleUiActions {
            override fun onBack() {
                // dirty 模式: 数据 flow 变更过才回传 Ok, 否则空 pop
                val payload =
                    if (screenModel.dataModified.value) RouteResultPayload.Ok
                    else RouteResultPayload.None
                navigator.pop(payload)
            }

            override fun onSearchKeyChange(key: String) {
                screenModel.dispatch(SourceFilterRuleUiEvent.SearchKeyChange(key))
            }

            override fun onToggleSelected(item: SourceFilterRule, checked: Boolean) {
                screenModel.dispatch(SourceFilterRuleUiEvent.ToggleSelected(item, checked))
            }

            override fun onSelectAll(all: Boolean) {
                screenModel.dispatch(SourceFilterRuleUiEvent.SelectAll(all))
            }

            override fun onRevertSelection() {
                screenModel.dispatch(SourceFilterRuleUiEvent.RevertSelection)
            }

            override fun onMoveItem(from: Int, to: Int) {
                screenModel.dispatch(SourceFilterRuleUiEvent.MoveItem(from, to))
            }

            override fun onPersistOrder() {
                screenModel.dispatch(SourceFilterRuleUiEvent.PersistOrder)
            }

            override fun onDeleteSelection() {
                screenModel.dispatch(SourceFilterRuleUiEvent.DeleteSelection)
            }

            override fun onDeleteRule(rule: SourceFilterRule) {
                screenModel.dispatch(SourceFilterRuleUiEvent.DeleteRule(rule))
            }

            override fun onDeleteAll() {
                screenModel.dispatch(SourceFilterRuleUiEvent.DeleteAll)
            }

            override fun onEnableSelection() {
                screenModel.dispatch(SourceFilterRuleUiEvent.EnableSelection)
            }

            override fun onDisableSelection() {
                screenModel.dispatch(SourceFilterRuleUiEvent.DisableSelection)
            }

            override fun onTopSelect() {
                screenModel.dispatch(SourceFilterRuleUiEvent.TopSelect)
            }

            override fun onBottomSelect() {
                screenModel.dispatch(SourceFilterRuleUiEvent.BottomSelect)
            }

            // payload 为选中规则 JSON, 平台走 HandleFileContract.EXPORT + showExportSuccess
            // (对照 app 端 SourceFilterRuleActivity.onExportSelection)
            override fun onExportSelection() {
                val json = GSON.toJson(screenModel.selection())
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceFilterRuleExport",
                        payload = json
                    )
                )
            }

            // payload 传规则 id, 平台据此初始化编辑弹窗
            // (对照 app 端 showDialogFragment(SourceFilterEditDialog(existing = rule)))
            override fun onEditRule(rule: SourceFilterRule) {
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceFilterRuleEdit",
                        payload = rule.id
                    )
                )
            }

            override fun onToTop(rule: SourceFilterRule) {
                screenModel.dispatch(SourceFilterRuleUiEvent.ToTop(rule))
            }

            override fun onToBottom(rule: SourceFilterRule) {
                screenModel.dispatch(SourceFilterRuleUiEvent.ToBottom(rule))
            }

            override fun onToggleEnabled(rule: SourceFilterRule, enabled: Boolean) {
                screenModel.dispatch(SourceFilterRuleUiEvent.ToggleEnabled(rule, enabled))
            }

            // payload 为 null 表示新建规则
            // (对照 app 端 showDialogFragment(SourceFilterEditDialog(existing = null)))
            override fun onAddRule() {
                navigator.showOverlay(AppOverlay.Dialog(key = "sourceFilterRuleEdit"))
            }

            // 平台渲染文件选择 + ImportSourceFilterRuleDialog
            // (对照 app 端 importDoc.launch { mode = FILE; allowExtensions = ... })
            override fun onImportLocal() {
                navigator.showOverlay(AppOverlay.Dialog(key = "sourceFilterRuleImportLocal"))
            }

            // 平台渲染 OnlineImportUrlDialog (ACache 历史) + ImportSourceFilterRuleDialog
            // (对照 app 端 showImportDialog())
            override fun onImportOnline() {
                navigator.showOverlay(AppOverlay.Dialog(key = "sourceFilterRuleImportOnline"))
            }
        }
    }

    SourceFilterRuleScreen(state = state, actions = actions)

    // 系统返回/ESC 走与标题栏返回同一条路径, 否则 dirty 时丢 RESULT_OK 回传
    // (对照 app 端 observe(): dataInit 后数据变更即 setResult(RESULT_OK))
    val backStack by navigator.backStack.collectAsState()
    val isTopEntry = backStack.lastOrNull()?.id == entry.id
    AppBackHandler(enabled = isTopEntry, onBack = actions::onBack)
}
