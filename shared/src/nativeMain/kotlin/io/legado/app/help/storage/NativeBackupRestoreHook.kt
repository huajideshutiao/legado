@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.help.storage

import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import kotlin.time.Clock

/**
 * native (iOS/鸿蒙) 端 [BackupRestoreHook]: 记录备份时间戳 + 恢复完成提示。
 *
 * zip 复制/解压、config.xml 旧格式均无平台特例, 交回 [BackupShared] 的
 * [BackupFileOps] 默认实现 (返回 false / null)。
 *
 * app 端 onRestoreFinished 还有换图标/日夜间刷新: 换图标是唯一平台差异
 * (见 [applyRestoredLauncherIcon]), 主题由 AppTheme 订阅配置自动重组。
 *
 * 未注册时 [BackupRestoreHooks.get] 返回空默认实现 (备份/恢复照常, 仅丢时间戳/提示副作用)。
 */
private object NativeBackupRestoreHook : BackupRestoreHook {

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

    override suspend fun onRestoreFinished() {
        runCatching { Toasters.get().toast("恢复完成") }
        applyRestoredLauncherIcon()
    }
}

/** 宿主启动早期注册一次 (任何备份/恢复之前)。 */
fun registerNativeBackupRestoreHook() {
    BackupRestoreHooks.register(NativeBackupRestoreHook)
}

/**
 * 恢复完成后同步桌面图标 (对照 app 端 Restore.kt 的 LauncherIconHelp.changeIcon)。
 *
 * iOS 读回 launcherIcon 偏好经 setAlternateIconName 切换; 鸿蒙无动态图标, 空实现。
 */
internal expect fun applyRestoredLauncherIcon()
