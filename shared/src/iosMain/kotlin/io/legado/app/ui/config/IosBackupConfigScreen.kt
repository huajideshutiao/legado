package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端备份设置页入口 (包装 shared/sharedUiMain 的 [BackupConfigScreen])。
 *
 * 阻塞点: SAF/文件选择 (onBackupPath) 与 WebDav 备份/恢复动作均 stub,
 * 待后续接入 iOS 平台文档选取器与 WebDav 客户端。
 *
 * @param onBack 返回回调
 */
@Composable
fun IosBackupConfigScreen(
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("backup_restore"),
            onBack = onBack,
        )
        // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val backupPathText = rememberString("ios_backup_path_not_implemented")
        val webDavBackupText = rememberString("ios_webdav_backup_not_implemented")
        val webDavRestoreText = rememberString("ios_webdav_restore_not_implemented")
        val restoreIgnoreText = rememberString("ios_restore_ignore_not_implemented")
        // KP-iOS: summary 与动作均 stub, 待接入 iOS 文档选取器与 WebDav 客户端
        BackupConfigScreen(
            webDavUrlSummary = "",
            webDavAccountSummary = "",
            webDavPasswordSummary = "",
            webDavDirSummary = "",
            webDavDeviceNameSummary = "",
            backupPathSummary = "",
            onBackupPath = {
                // TODO: iOS 端 SAF 等价文档选取器 (pickDocuments), KP6+ 接入
                Toasters.get().toast(backupPathText)
            },
            onWebDavBackup = {
                // TODO: iOS 端 WebDav 备份, KP6+ 接入
                Toasters.get().toast(webDavBackupText)
            },
            onWebDavBackupLong = {
                Toasters.get().toast(webDavBackupText)
            },
            onWebDavRestore = {
                Toasters.get().toast(webDavRestoreText)
            },
            onWebDavRestoreLong = {
                Toasters.get().toast(webDavRestoreText)
            },
            onRestoreIgnore = {
                Toasters.get().toast(restoreIgnoreText)
            },
        )
    }
}
