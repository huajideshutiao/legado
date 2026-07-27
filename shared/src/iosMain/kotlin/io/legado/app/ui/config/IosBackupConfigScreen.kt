package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.pickDirectory
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.launch

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
        val webDavBackupText = rememberString("ios_webdav_backup_not_implemented")
        val webDavRestoreText = rememberString("ios_webdav_restore_not_implemented")
        val restoreIgnoreText = rememberString("ios_restore_ignore_not_implemented")
        val scope = rememberCoroutineScope()
        val prefs = remember { PreferenceProviders.get() }
        var backupPath by remember { mutableStateOf(prefs.getString(PreferKey.backupPath)) }
        // KP-iOS: backupPath 已接入, 其余 summary 与动作均 stub, 待接入 WebDav 客户端
        BackupConfigScreen(
            webDavUrlSummary = "",
            webDavAccountSummary = "",
            webDavPasswordSummary = "",
            webDavDirSummary = "",
            webDavDeviceNameSummary = "",
            backupPathSummary = backupPath,
            onBackupPath = {
                // 选目录写回 PreferKey.backupPath (对齐 desktop WebDavConfigScreen)
                scope.launch {
                    val url = pickDirectory() ?: return@launch
                    val path = url.path ?: return@launch
                    prefs.putString(PreferKey.backupPath, path)
                    backupPath = path
                }
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
