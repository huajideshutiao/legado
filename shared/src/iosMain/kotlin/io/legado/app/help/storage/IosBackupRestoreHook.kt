@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.IosPlatformCapabilities
import kotlin.time.Clock

/**
 * iOS 端 [BackupRestoreHook]: 记录备份时间戳 + 恢复完成提示。
 *
 * zip 复制/解压、config.xml 旧格式均无平台特例, 交回 [BackupShared] 的
 * [BackupFileOps] 默认实现 (返回 false / null)。
 */
private object IosBackupRestoreHook : BackupRestoreHook {

    // 与 app 端 LocalConfig.lastBackup 同 key, 供自动备份判断间隔
    private const val KEY_LAST_BACKUP = "lastBackup"

    private fun markBackupTime() {
        runCatching {
            PreferenceProviders.get()
                .putLong(KEY_LAST_BACKUP, Clock.System.now().toEpochMilliseconds())
        }
    }

    override fun onBackupStart() = markBackupTime()

    override fun onRestoreFromZipFinished() = markBackupTime()

    // app 端此处还有换图标/日夜间刷新; iOS 恢复 launcherIcon 偏好后经 setAlternateIconName
    // 同步切换桌面图标 (对照 app 端 Restore.kt), 主题由 AppTheme 订阅配置自动重组
    override suspend fun onRestoreFinished() {
        runCatching { Toasters.get().toast("恢复完成") }
        runCatching {
            val icon = PreferenceProviders.get().getString(PreferKey.launcherIcon, "ic_launcher")
            IosPlatformCapabilities.changeLauncherIcon(icon)
        }
    }
}

/** 宿主启动早期注册一次 (任何备份/恢复之前)。 */
fun registerIosBackupRestoreHook() {
    BackupRestoreHooks.register(IosBackupRestoreHook)
}
