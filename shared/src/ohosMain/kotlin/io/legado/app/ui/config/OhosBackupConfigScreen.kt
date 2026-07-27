package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * 鸿蒙端备份设置页入口 (包装 shared/sharedUiMain 的 [BackupConfigScreen])。
 *
 * 实现模式参考 iOS 端 [IosBackupConfigScreen]: 复用 sharedUiMain 跨平台 Composable,
 * 避免复制代码; material3 原生组件 (TopAppBar 等) 接入待 API 明确后逐步替换顶栏。
 *
 * 阻塞点: SAF/文件选择 (onBackupPath) 与 WebDav 备份/恢复动作均 stub,
 * 待后续接入鸿蒙平台文档选取器与 WebDav 客户端。
 *
 * @param onBack 返回回调
 */
@Composable
fun OhosBackupConfigScreen(
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("backup_restore"),
            onBack = onBack,
        )
        // 鸿蒙端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
        val backupPathText = rememberString("ohos_backup_path_not_implemented")
        val webDavBackupText = rememberString("ohos_webdav_backup_not_implemented")
        val webDavRestoreText = rememberString("ohos_webdav_restore_not_implemented")
        val restoreIgnoreText = rememberString("ohos_restore_ignore_not_implemented")
        // 鸿蒙端: summary 与动作均 stub, 待接入鸿蒙文档选取器与 WebDav 客户端
        BackupConfigScreen(
            webDavUrlSummary = "",
            webDavAccountSummary = "",
            webDavPasswordSummary = "",
            webDavDirSummary = "",
            webDavDeviceNameSummary = "",
            backupPathSummary = "",
            onBackupPath = {
                // TODO: 鸿蒙端 SAF 等价文档选取器, 后续接入
                Toasters.get().toast(backupPathText)
            },
            onWebDavBackup = {
                // TODO: 鸿蒙端 WebDav 备份, 后续接入
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
