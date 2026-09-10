package io.legado.desktop.help.storage

import io.legado.app.help.AppWebDavShared
import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.storage.BackupRestoreHook
import io.legado.app.help.storage.BackupRestoreHooks
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.jvmGetString

/**
 * 桌面端 [BackupRestoreHook]: 备份/恢复的平台收尾环节。
 *
 * 对照 app 端 `AndroidBackupRestoreHook`, 桌面端无 SAF / 无旧版 config.xml / 无启动图标切换,
 * 故 copyBackupTo / unZipBackup / readLegacyConfig 全走 shared 默认分支
 * ([io.legado.app.help.storage.BackupFileOps] 普通文件路径), 只补时间戳与恢复提示。
 */
object DesktopBackupRestoreHook : BackupRestoreHook {

    override fun onBackupStart() {
        markLastBackup()
    }

    override fun onRestoreFromZipFinished() {
        markLastBackup()
    }

    /** 恢复完成提示 (app 端还会切启动图标 + applyDayNight, 桌面端无对应能力)。 */
    override suspend fun onRestoreFinished() {
        runCatching { Toasters.get().toast(jvmGetString("restore_success")) }
    }

    /** 备份收尾: 上传阅读背景图 (逻辑已下沉 [AppWebDavShared.upBgs], 桌面端直接复用)。 */
    override suspend fun onBackupFinished(uploadToWebDav: Boolean) {
        if (!uploadToWebDav) return
        runCatching { AppWebDavShared.upBgs() }
    }

    // 与 app 端 LocalConfig.lastBackup 同 key, 桌面端落 PreferenceProvider
    private fun markLastBackup() {
        runCatching {
            PreferenceProviders.get()
                .putLong(LocalConfigKeys.lastBackup, System.currentTimeMillis())
        }
    }

    // onThemeConfigRestored 用接口默认实现: 默认已调 ThemeConfigProviders.get().upConfig(),
    // 桌面端注册的是 FileThemeConfigProvider, 恢复后主题列表会自动重载。
}

/** 桌面宿主启动早期注册一次 (任何备份/恢复之前)。 */
fun registerDesktopBackupRestoreHook() {
    BackupRestoreHooks.register(DesktopBackupRestoreHook)
}
