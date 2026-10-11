@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.legado.app.help.storage

import io.legado.app.help.config.LocalConfigKeys
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.crash.CrashMarkerProvider
import io.legado.app.help.crash.CrashMarkerProviders
import io.legado.app.help.crash.NativeCrashLogWriter
import io.legado.app.help.toast.Toasters
import kotlin.time.Clock

/**
 * native (iOS/鸿蒙) 端 [BackupRestoreHook]: 记录备份时间戳 + 恢复完成提示。
 *
 * 恢复侧无平台特例 (config.xml 旧格式 / 解压都走 [RestoreShared] 默认实现); 备份侧只有一处
 * 平台差异: 用户选定的备份目录在沙盒之外, 写入前必须先取得该目录的持久授权
 * ([copyBackupIntoUserDir])。
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

    /** 备份 zip 落地到用户选定目录前先取得该目录授权, 再写 ([copyBackupIntoUserDir])。 */
    override fun copyBackupTo(zipFilePath: String, destination: String, fileName: String): Boolean =
        copyBackupIntoUserDir(zipFilePath, destination, fileName)

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
 * 注册 iOS/鸿蒙的异常退出标记实现 (对照原版 `LocalConfig.appCrash` 语义, 两端共用)。
 *
 * 与其余三端的差别只有信号路径: 信号处理器内不能走偏好存储 (需分配/加锁), 故
 * [NativeCrashLogWriter.writeSignal] 改落标记文件; 本实现把两条来源合并 ——
 * 异常钩子写偏好存储 (与 Android/桌面同键), 信号写文件。
 */
fun registerNativeCrashMarkerProvider() {
    CrashMarkerProviders.register(NativeCrashMarker)
}

private object NativeCrashMarker : CrashMarkerProvider {

    override fun mark() {
        runCatching {
            LocalConfigProviders.get().putBoolean(LocalConfigKeys.appCrash, true)
        }
    }

    override fun consumePending(): Boolean = runCatching {
        // 信号路径的标记文件优先消费 (它与偏好存储可能同时存在: 同一次崩溃只走其中一条路径,
        // 但两次崩溃可能一条写文件一条写偏好)。两个候选目录都查, 覆盖"注册前就崩"的早期崩溃
        val fromFile = NativeCrashLogWriter.consumePendingMarker()
        val store = LocalConfigProviders.get()
        val fromPrefs = store.getBoolean(LocalConfigKeys.appCrash, false)
        if (fromPrefs) store.putBoolean(LocalConfigKeys.appCrash, false)
        fromFile || fromPrefs
    }.getOrDefault(false)
}

/**
 * 把备份 zip 写进用户选定目录 (内容与 [BackupFileOps.copyFile] 同一份)。
 *
 * 用户选定目录在应用沙盒之外: iOS 靠 security-scoped 租约, 鸿蒙靠已持久化的目录授权激活 ——
 * 两者都必须在写入前取得, 否则跨启动后写入被系统拒绝; 授权失效时报明确失败 (不谎报已备份)。
 * 沙盒内目录 (应用私有目录) 无需授权直接写入。
 *
 * @return true = 已由本端完成复制
 */
internal expect fun copyBackupIntoUserDir(
    zipFilePath: String,
    destination: String,
    fileName: String,
): Boolean

/**
 * 恢复完成后同步桌面图标 (对照 app 端 Restore.kt 的 LauncherIconHelp.changeIcon)。
 *
 * iOS 读回 launcherIcon 偏好经 setAlternateIconName 切换; 鸿蒙无动态图标, 空实现。
 */
internal expect fun applyRestoredLauncherIcon()
