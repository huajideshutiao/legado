package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.editTextPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.preferenceCategory
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.auto_check_new_backup_s
import legado.shared.generated.resources.auto_check_new_backup_t
import legado.shared.generated.resources.backup
import legado.shared.generated.resources.backup_path
import legado.shared.generated.resources.backup_restore
import legado.shared.generated.resources.backup_summary
import legado.shared.generated.resources.only_latest_backup_s
import legado.shared.generated.resources.only_latest_backup_t
import legado.shared.generated.resources.restore
import legado.shared.generated.resources.restore_ignore
import legado.shared.generated.resources.restore_ignore_summary
import legado.shared.generated.resources.restore_summary
import legado.shared.generated.resources.sub_dir
import legado.shared.generated.resources.sync_book_progress_plus_s
import legado.shared.generated.resources.sync_book_progress_plus_t
import legado.shared.generated.resources.sync_book_progress_s
import legado.shared.generated.resources.sync_book_progress_t
import legado.shared.generated.resources.web_dav_account
import legado.shared.generated.resources.web_dav_pw
import legado.shared.generated.resources.web_dav_set
import legado.shared.generated.resources.web_dav_url
import legado.shared.generated.resources.webdav_device_name
import org.jetbrains.compose.resources.stringResource

/**
 * 备份设置页（迁 pref_config_backup.xml）。逐条对齐原条目顺序/key/默认值。
 * WebDav 各项写 prefs 后仍走宿主的 OnSharedPreferenceChangeListener 承接（刷 summary + upWebDavConfig）；
 * 动态 summary 用 state 承接；备份/恢复动作（含长按本地备份/恢复）与文件选择/权限 launcher 逐字保留在宿主。
 *
 * 下沉 shared/sharedUiMain:
 * - stringResource(R.string.xxx) → stringResource(Res.string.xxx)
 * - AppConfig.syncBookProgress/webDavDir/webDavDeviceName → AppConfigProviders.get().xxx
 *   (AppConfigAccessor 接口已扩展 webDavDir/webDavDeviceName 字段, 各平台 actual 注入)
 * - 与 app 端原包名/类名一致, app/desktop 端共用。
 */
@Composable
fun BackupConfigScreen(
    webDavUrlSummary: String,
    webDavAccountSummary: String,
    webDavPasswordSummary: String,
    webDavDirSummary: String,
    webDavDeviceNameSummary: String,
    backupPathSummary: String,
    onBackupPath: () -> Unit,
    onWebDavBackup: () -> Unit,
    onWebDavBackupLong: () -> Unit,
    onWebDavRestore: () -> Unit,
    onWebDavRestoreLong: () -> Unit,
    onRestoreIgnore: () -> Unit,
    editDialogWidthFraction: Float = 1f,
) {
    // syncBookProgress -> syncBookProgressPlus 依赖联动（复刻 android:dependency）
    // AppConfigProviders.get().syncBookProgress 替代原 AppConfig.syncBookProgress (跨平台 provider 注入)
    var syncBookProgress by remember {
        mutableStateOf(AppConfigProviders.get().syncBookProgress)
    }

    val titleWebDavSet = stringResource(Res.string.web_dav_set)
    val titleWebDavUrl = stringResource(Res.string.web_dav_url)
    val titleWebDavAccount = stringResource(Res.string.web_dav_account)
    val titleWebDavPw = stringResource(Res.string.web_dav_pw)
    val titleSubDir = stringResource(Res.string.sub_dir)
    val titleDeviceName = stringResource(Res.string.webdav_device_name)
    val titleSyncProgress = stringResource(Res.string.sync_book_progress_t)
    val summarySyncProgress = stringResource(Res.string.sync_book_progress_s)
    val titleSyncProgressPlus = stringResource(Res.string.sync_book_progress_plus_t)
    val summarySyncProgressPlus = stringResource(Res.string.sync_book_progress_plus_s)
    val titleBackupRestore = stringResource(Res.string.backup_restore)
    val titleBackupPath = stringResource(Res.string.backup_path)
    val titleBackup = stringResource(Res.string.backup)
    val summaryBackup = stringResource(Res.string.backup_summary)
    val titleRestore = stringResource(Res.string.restore)
    val summaryRestore = stringResource(Res.string.restore_summary)
    val titleRestoreIgnore = stringResource(Res.string.restore_ignore)
    val summaryRestoreIgnore = stringResource(Res.string.restore_ignore_summary)
    val titleOnlyLatest = stringResource(Res.string.only_latest_backup_t)
    val summaryOnlyLatest = stringResource(Res.string.only_latest_backup_s)
    val titleAutoCheckNewBackup = stringResource(Res.string.auto_check_new_backup_t)
    val summaryAutoCheckNewBackup = stringResource(Res.string.auto_check_new_backup_s)

    AppTheme {
        PreferenceScreen {
            preferenceCategory(titleWebDavSet)
            // Android 保持 AlertDialog 基准宽度；桌面宿主可显式传 0.8f。
            editTextPreference(
                prefKey = PreferKey.webDavUrl,
                title = titleWebDavUrl,
                summary = webDavUrlSummary,
                widthFraction = editDialogWidthFraction,
            )
            editTextPreference(
                prefKey = PreferKey.webDavAccount,
                title = titleWebDavAccount,
                summary = webDavAccountSummary,
                widthFraction = editDialogWidthFraction,
            )
            editTextPreference(
                prefKey = PreferKey.webDavPassword,
                title = titleWebDavPw,
                summary = webDavPasswordSummary,
                isPassword = true,
                widthFraction = editDialogWidthFraction,
            )
            editTextPreference(
                prefKey = PreferKey.webDavDir,
                title = titleSubDir,
                summary = webDavDirSummary,
                // 复刻 setOnBindEditTextListener 预填 AppConfig.webDavDir（默认 "legado"）
                defaultValue = AppConfigProviders.get().webDavDir,
                widthFraction = editDialogWidthFraction,
            )
            editTextPreference(
                prefKey = PreferKey.webDavDeviceName,
                title = titleDeviceName,
                summary = webDavDeviceNameSummary,
                // 复刻预填 AppConfig.webDavDeviceName（app 端默认 Build.MODEL, 其他平台默认空串）
                defaultValue = AppConfigProviders.get().webDavDeviceName,
                widthFraction = editDialogWidthFraction,
            )
            switchPreference(
                prefKey = PreferKey.syncBookProgress,
                title = titleSyncProgress,
                summary = summarySyncProgress,
                defaultValue = true,
                onCheckedChange = { syncBookProgress = it },
            )
            switchPreference(
                prefKey = PreferKey.syncBookProgressPlus,
                title = titleSyncProgressPlus,
                summary = summarySyncProgressPlus,
                defaultValue = false,
                enabled = syncBookProgress,
            )

            preferenceCategory(titleBackupRestore)
            preference(
                title = titleBackupPath,
                summary = backupPathSummary,
                onClick = onBackupPath,
            )
            preference(
                title = titleBackup,
                summary = summaryBackup,
                onClick = onWebDavBackup,
                onLongClick = onWebDavBackupLong,
            )
            preference(
                title = titleRestore,
                summary = summaryRestore,
                onClick = onWebDavRestore,
                onLongClick = onWebDavRestoreLong,
            )
            preference(
                title = titleRestoreIgnore,
                summary = summaryRestoreIgnore,
                onClick = onRestoreIgnore,
            )
            switchPreference(
                prefKey = PreferKey.onlyLatestBackup,
                title = titleOnlyLatest,
                summary = summaryOnlyLatest,
                defaultValue = true,
            )
            switchPreference(
                prefKey = PreferKey.autoCheckNewBackup,
                title = titleAutoCheckNewBackup,
                summary = summaryAutoCheckNewBackup,
                defaultValue = true,
            )
        }
    }
}
