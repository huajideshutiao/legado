package io.legado.app.ui.config

import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ===== state / events =====

/**
 * 备份设置页 UI 状态 (immutable)。
 *
 * 6 个 summary (webDavUrl/Account/Password/Dir/DeviceName + backupPath) 由宿主
 * 在 OnSharedPreferenceChangeListener 回调中通过 [BackupConfigScreenModel.dispatch]
 * 推入 [BackupConfigUiEvent.UpdateXxxSummary]; 宿主 Activity/桌面端负责解析平台资源
 * (R.string.web_dav_url_s 等) 后传字符串进来。
 *
 * @param webDavUrlSummary         WebDav 地址 summary
 * @param webDavAccountSummary     WebDav 账号 summary
 * @param webDavPasswordSummary    WebDav 密码 summary (宿主按长度补 "*")
 * @param webDavDirSummary         WebDav 子目录 summary
 * @param webDavDeviceNameSummary  WebDav 设备名 summary
 * @param backupPathSummary        本地备份路径 summary
 */
data class BackupConfigUiState(
    val webDavUrlSummary: String = "",
    val webDavAccountSummary: String = "",
    val webDavPasswordSummary: String = "",
    val webDavDirSummary: String = "",
    val webDavDeviceNameSummary: String = "",
    val backupPathSummary: String = "",
)

/**
 * 备份设置页用户交互事件。
 *
 * - [UpdateWebDavUrlSummary] / [UpdateWebDavAccountSummary] / [UpdateWebDavPasswordSummary] /
 *   [UpdateWebDavDirSummary] / [UpdateWebDavDeviceNameSummary] / [UpdateBackupPathSummary]:
 *   prefs 变更后宿主更新对应 summary
 * - [SelectBackupPath]: 选择本地备份目录 (宿主注入 SAF/桌面文档选取器)
 * - [Backup]: 备份 (上传到 WebDav), [BackupLocal] 长按仅本地备份
 * - [Restore]: 从 WebDav 备份恢复, [RestoreFromLocal] 长按从本地 zip 恢复
 * - [RestoreIgnore]: 备份忽略项设置
 */
sealed interface BackupConfigUiEvent {
    data class UpdateWebDavUrlSummary(val summary: String) : BackupConfigUiEvent
    data class UpdateWebDavAccountSummary(val summary: String) : BackupConfigUiEvent
    data class UpdateWebDavPasswordSummary(val summary: String) : BackupConfigUiEvent
    data class UpdateWebDavDirSummary(val summary: String) : BackupConfigUiEvent
    data class UpdateWebDavDeviceNameSummary(val summary: String) : BackupConfigUiEvent
    data class UpdateBackupPathSummary(val summary: String) : BackupConfigUiEvent
    object SelectBackupPath : BackupConfigUiEvent
    object Backup : BackupConfigUiEvent
    object BackupLocal : BackupConfigUiEvent
    object Restore : BackupConfigUiEvent
    object RestoreFromLocal : BackupConfigUiEvent
    object RestoreIgnore : BackupConfigUiEvent
}

// ===== ScreenModel =====

/**
 * 备份设置页 shared ScreenModel: 托管 [BackupConfigUiState], 通过 [dispatch] 处理事件。
 *
 * 平台专属动作 (SAF 文档选取器/权限请求/WaitDialog/BackupConfigShared.ignoreConfig 弹窗) 经
 * 构造函数 lambda 注入, 由宿主实现; shared 端不持有 Android Context / FileDoc。
 *
 * @param onBackup          备份动作 (uploadToWebDav=true 上传 WebDav, false 仅本地)
 * @param onRestore         从 WebDav 备份恢复 (无备份文件时宿主回退到本地)
 * @param onRestoreFromLocal 从本地 zip 恢复 (长按)
 * @param onSelectBackupPath 选择本地备份目录
 * @param onRestoreIgnore   备份忽略项设置
 */
class BackupConfigScreenModel(
    private val onBackup: (uploadToWebDav: Boolean) -> Unit,
    private val onRestore: () -> Unit,
    private val onRestoreFromLocal: () -> Unit,
    private val onSelectBackupPath: () -> Unit,
    private val onRestoreIgnore: () -> Unit,
) : ScreenModel {

    /**
     * 页面级协程作用域 (对照 app 端 viewModelScope): 生命周期与 ScreenModel 绑定,
     * 由 ScreenModelStore.retain/clear → onCleared 取消。宿主 Route 的交互协程
     * (备份/恢复/选文件等) 全部跑在这里, 避免页面组合销毁后延迟回调再 launch 抛
     * ForgottenCoroutineScopeException。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(BackupConfigUiState())
    val state: StateFlow<BackupConfigUiState> = _state.asStateFlow()

    fun dispatch(event: BackupConfigUiEvent) {
        when (event) {
            is BackupConfigUiEvent.UpdateWebDavUrlSummary ->
                _state.update { it.copy(webDavUrlSummary = event.summary) }

            is BackupConfigUiEvent.UpdateWebDavAccountSummary ->
                _state.update { it.copy(webDavAccountSummary = event.summary) }

            is BackupConfigUiEvent.UpdateWebDavPasswordSummary ->
                _state.update { it.copy(webDavPasswordSummary = event.summary) }

            is BackupConfigUiEvent.UpdateWebDavDirSummary ->
                _state.update { it.copy(webDavDirSummary = event.summary) }

            is BackupConfigUiEvent.UpdateWebDavDeviceNameSummary ->
                _state.update { it.copy(webDavDeviceNameSummary = event.summary) }

            is BackupConfigUiEvent.UpdateBackupPathSummary ->
                _state.update { it.copy(backupPathSummary = event.summary) }

            BackupConfigUiEvent.SelectBackupPath -> onSelectBackupPath()
            BackupConfigUiEvent.Backup -> onBackup(true)
            BackupConfigUiEvent.BackupLocal -> onBackup(false)
            BackupConfigUiEvent.Restore -> onRestore()
            BackupConfigUiEvent.RestoreFromLocal -> onRestoreFromLocal()
            BackupConfigUiEvent.RestoreIgnore -> onRestoreIgnore()
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
