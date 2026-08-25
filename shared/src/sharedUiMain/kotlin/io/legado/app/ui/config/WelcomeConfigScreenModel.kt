package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动闪屏配置 UI 状态 (immutable)。
 * AppConfig 仅 app 端可用, 由宿主 Activity 解析后通过 [WelcomeConfigScreenModel.dispatch] 推入。
 */
data class WelcomeConfigUiState(
    val enableWelcome: Boolean = true,
    val welcomeShowTime: Int = 600,
    val welcomeShowText: Boolean = true,
    val welcomeShowIcon: Boolean = true,
    val welcomeImage: String? = null,
    val welcomeImageDark: String? = null,
)

/**
 * 启动闪屏 shared ScreenModel: 托管 [WelcomeConfigUiState]。
 * 实际配置读取/持久化留在 app 端 (AppConfig), 本类只承接渲染所需快照。
 */
class WelcomeConfigScreenModel : ScreenModel {

    private val _state = MutableStateFlow(WelcomeConfigUiState())
    val state: StateFlow<WelcomeConfigUiState> = _state.asStateFlow()

    fun dispatch(event: WelcomeConfigUiEvent) {
        when (event) {
            is WelcomeConfigUiEvent.Update -> _state.value = event.state
            is WelcomeConfigUiEvent.ShowTimeChange -> _state.value =
                _state.value.copy(welcomeShowTime = event.time)

            is WelcomeConfigUiEvent.ImagePicked -> _state.value = if (event.isNight) {
                _state.value.copy(welcomeImageDark = event.path)
            } else {
                _state.value.copy(welcomeImage = event.path)
            }

            is WelcomeConfigUiEvent.ImageCleared -> _state.value = if (event.isNight) {
                _state.value.copy(welcomeImageDark = null)
            } else {
                _state.value.copy(welcomeImage = null)
            }
        }
    }
}

sealed interface WelcomeConfigUiEvent {
    data class Update(val state: WelcomeConfigUiState) : WelcomeConfigUiEvent
    data class ShowTimeChange(val time: Int) : WelcomeConfigUiEvent
    data class ImagePicked(val isNight: Boolean, val path: String) : WelcomeConfigUiEvent
    data class ImageCleared(val isNight: Boolean) : WelcomeConfigUiEvent
}
