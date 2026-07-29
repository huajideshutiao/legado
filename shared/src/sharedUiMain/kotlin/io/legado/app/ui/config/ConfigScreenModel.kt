package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ===== state / events =====

/**
 * ConfigActivity 级 UI 状态 (immutable): 跨设置页共享的备份/恢复进度。
 */
data class ConfigUiState(
    val backupRestoreMessage: String? = null,
)

/**
 * ConfigActivity 级配置变更事件。
 *
 * - [UpdateBackupRestoreState]: 宿主桥接备份/恢复进度到 UI
 * - 其余事件: 由宿主注入 lambda 执行 (AppWebDav / BookHelp / Backup / Restore 等重 Android 依赖留 app 端)
 */
sealed interface ConfigUiEvent {
    data class UpdateBackupRestoreState(val message: String?) : ConfigUiEvent
    object CancelBackupRestore : ConfigUiEvent
    object UpWebDavConfig : ConfigUiEvent
    object ClearCache : ConfigUiEvent
    object ClearWebViewData : ConfigUiEvent
    object ShrinkDatabase : ConfigUiEvent
    data class Backup(val backupPath: String, val uploadToWebDav: Boolean) : ConfigUiEvent
    data class Restore(val uri: String) : ConfigUiEvent
    data class RestoreWebDav(val name: String) : ConfigUiEvent
    data class LoadBackupNames(val onSuccess: (List<String>) -> Unit) : ConfigUiEvent
}

// ===== ScreenModel =====

/**
 * ConfigActivity shared ScreenModel: 托管 [ConfigUiState], 通过 [dispatch] 处理事件。
 *
 * 平台专属动作 (WebDav 配置更新 / 缓存清理 / WebView 数据清理 / 数据库收缩 /
 * 备份 / 恢复) 经构造函数 lambda 注入, 由宿主实现; shared 端不持有 Android Context。
 */
class ConfigScreenModel(
    private val onCancelBackupRestore: () -> Unit,
    private val onUpWebDavConfig: () -> Unit,
    private val onClearCache: () -> Unit,
    private val onClearWebViewData: () -> Unit,
    private val onShrinkDatabase: () -> Unit,
    private val onBackup: (backupPath: String, uploadToWebDav: Boolean) -> Unit,
    private val onRestore: (uri: String) -> Unit,
    private val onRestoreWebDav: (name: String) -> Unit,
    private val onLoadBackupNames: (onSuccess: (List<String>) -> Unit) -> Unit,
) : ScreenModel {

    private val _state = MutableStateFlow(ConfigUiState())
    val state: StateFlow<ConfigUiState> = _state.asStateFlow()

    fun dispatch(event: ConfigUiEvent) {
        when (event) {
            is ConfigUiEvent.UpdateBackupRestoreState ->
                _state.update { it.copy(backupRestoreMessage = event.message) }

            ConfigUiEvent.CancelBackupRestore -> onCancelBackupRestore()
            ConfigUiEvent.UpWebDavConfig -> onUpWebDavConfig()
            ConfigUiEvent.ClearCache -> onClearCache()
            ConfigUiEvent.ClearWebViewData -> onClearWebViewData()
            ConfigUiEvent.ShrinkDatabase -> onShrinkDatabase()
            is ConfigUiEvent.Backup -> onBackup(event.backupPath, event.uploadToWebDav)
            is ConfigUiEvent.Restore -> onRestore(event.uri)
            is ConfigUiEvent.RestoreWebDav -> onRestoreWebDav(event.name)
            is ConfigUiEvent.LoadBackupNames -> onLoadBackupNames(event.onSuccess)
        }
    }
}
