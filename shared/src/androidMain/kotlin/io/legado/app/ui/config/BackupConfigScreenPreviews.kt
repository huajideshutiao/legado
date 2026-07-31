package io.legado.app.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.ui.preview.registerStubAppConfig

/**
 * [BackupConfigScreen.kt] 中 [BackupConfigScreen] 的 @Preview。
 *
 * BackupConfigScreen 在 `remember { mutableStateOf(AppConfigProviders.get().syncBookProgress) }`
 * 中首帧即读 `AppConfigProviders.get()`, 而 [LegadoThemePreview] 的 `SideEffect` 注册 stub
 * 在首次 composition 成功后才执行, 时机太晚会导致首帧抛 "not registered"。
 * 故在此 Preview 顶部直接调用 [registerStubAppConfig] (幂等) 提前注册, 再进入 LegadoThemePreview。
 *
 * rememberString 在 jvm Preview 端未命中 key 时返回 key 本身, 故部分文案为 key 字符串。
 */

@Preview
@Composable
fun BackupConfigScreenPreview() {
    // 首帧 remember 读 AppConfigProviders, 须在 composition 前注册 stub (SideEffect 时机太晚)
    registerStubAppConfig()
    LegadoThemePreview {
        BackupConfigScreen(
            webDavUrlSummary = "https://dav.jianguoyun.com/dav/",
            webDavAccountSummary = "user@example.com",
            webDavPasswordSummary = "已设置",
            webDavDirSummary = "legado",
            webDavDeviceNameSummary = "我的设备",
            backupPathSummary = "/storage/emulated/0/legado/backup",
            onBackupPath = {},
            onWebDavBackup = {},
            onWebDavBackupLong = {},
            onWebDavRestore = {},
            onWebDavRestoreLong = {},
            onRestoreIgnore = {},
        )
    }
}

@Preview
@Composable
fun BackupConfigScreenDarkPreview() {
    registerStubAppConfig()
    LegadoThemePreview(dark = true) {
        BackupConfigScreen(
            webDavUrlSummary = "https://dav.jianguoyun.com/dav/",
            webDavAccountSummary = "user@example.com",
            webDavPasswordSummary = "已设置",
            webDavDirSummary = "legado",
            webDavDeviceNameSummary = "我的设备",
            backupPathSummary = "/storage/emulated/0/legado/backup",
            onBackupPath = {},
            onWebDavBackup = {},
            onWebDavBackupLong = {},
            onWebDavRestore = {},
            onWebDavRestoreLong = {},
            onRestoreIgnore = {},
        )
    }
}

@Preview
@Composable
fun BackupConfigScreenEmptyPreview() {
    // 未配置 WebDav 时的空 summary 态
    registerStubAppConfig()
    LegadoThemePreview {
        BackupConfigScreen(
            webDavUrlSummary = "",
            webDavAccountSummary = "",
            webDavPasswordSummary = "",
            webDavDirSummary = "legado",
            webDavDeviceNameSummary = "",
            backupPathSummary = "/storage/emulated/0/legado/backup",
            onBackupPath = {},
            onWebDavBackup = {},
            onWebDavBackupLong = {},
            onWebDavRestore = {},
            onWebDavRestoreLong = {},
            onRestoreIgnore = {},
        )
    }
}
