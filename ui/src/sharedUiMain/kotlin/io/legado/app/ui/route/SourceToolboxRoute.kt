package io.legado.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.legado.app.data.entities.BookSource
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.RouteEntry
import io.legado.app.ui.root.ScreenModelStore
import io.legado.app.ui.toolbox.SourceToolboxScreen
import io.legado.app.ui.toolbox.SourceToolboxScreenModel
import io.legado.app.ui.toolbox.SourceToolboxUiActions
import io.legado.app.ui.toolbox.ToolboxRuleMode
import io.legado.app.ui.toolbox.ToolboxStage
import io.legado.app.ui.toolbox.ToolboxUiEvent
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.toolbox_no_response
import legado.ui.generated.resources.toolbox_source_cleared
import org.jetbrains.compose.resources.stringResource

@Composable
fun SourceToolboxRoute(
    entry: RouteEntry,
    navigator: AppNavigator,
    screenModelStore: ScreenModelStore,
) {
    val noResponseText = stringResource(Res.string.toolbox_no_response)
    val sourceClearedText = stringResource(Res.string.toolbox_source_cleared)

    val screenModel = screenModelStore.getOrCreateTyped(entry) {
        SourceToolboxScreenModel(
            toast = { Toasters.get().toast(it) },
            noResponseText = noResponseText,
            sourceClearedText = sourceClearedText,
        )
    }
    val state by screenModel.uiState.collectAsState()

    val actions = remember(screenModel, navigator) {
        object : SourceToolboxUiActions {
            override fun onBack() {
                navigator.pop()
            }

            override fun onShowKeyboardConfig() {
                navigator.showOverlay(AppOverlay.Dialog("keyboardAssistsConfig"))
            }

            override fun onSourceSelected(source: BookSource) {
                screenModel.dispatch(ToolboxUiEvent.SelectSource(source))
            }

            override fun onClearSource() {
                screenModel.dispatch(ToolboxUiEvent.ClearSource)
            }

            override fun onStageChange(stage: ToolboxStage) {
                screenModel.dispatch(ToolboxUiEvent.StageChange(stage))
            }

            override fun onUrlChange(text: String) {
                screenModel.dispatch(ToolboxUiEvent.UrlChange(text))
            }

            override fun onInputChange(text: String) {
                screenModel.dispatch(ToolboxUiEvent.InputChange(text))
            }

            override fun onRequest() {
                screenModel.dispatch(ToolboxUiEvent.Request)
            }

            override fun onRunRule(mode: ToolboxRuleMode) {
                screenModel.dispatch(ToolboxUiEvent.RunRule(mode))
            }

            override fun onRunJs() {
                screenModel.dispatch(ToolboxUiEvent.RunJs)
            }

            override fun onTreeMode(on: Boolean) {
                screenModel.dispatch(ToolboxUiEvent.ResTreeMode(on))
            }
        }
    }

    SourceToolboxScreen(state = state, actions = actions)
}
