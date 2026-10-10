package io.legado.app.help.storage

import io.legado.app.constant.DirUsage
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.IosPlatformCapabilities
import io.legado.app.utils.IosSecurityScopedStorage

/** iOS: 恢复 launcherIcon 偏好后经 setAlternateIconName 同步切换桌面图标。 */
internal actual fun applyRestoredLauncherIcon() {
    runCatching {
        val icon = PreferenceProviders.get().getString(PreferKey.launcherIcon, "ic_launcher")
        IosPlatformCapabilities.changeLauncherIcon(icon)
    }
}

/**
 * 备份 zip 写进用户选定目录: 写入期间持该目录的 security-scoped 租约。
 *
 * 直接持租约 ([IosSecurityScopedStorage.acquireLease]) 而不走 NSFileCoordinator: 协调器会对
 * 尚不存在的目标文件报错, 而这里需要的只是授权 (与"导出目录"写入同一模式)。
 * 租约按备份用途取: 只有用户在备份入口选定的目录才放行, 为书籍/导出/字体选的目录不会。
 * 授权已被撤销/目录不在授权表里时 acquireLease 抛 SecurityException, 备份随之报失败原因,
 * 不会留下一个"看起来成功、实际没写出去"的备份。
 *
 * 沙盒内目录 (平台惯例目录) 取到的是空租约, 无需 scope, 写盘照旧。
 */
internal actual fun copyBackupIntoUserDir(
    zipFilePath: String,
    destination: String,
    fileName: String,
): Boolean {
    val lease = IosSecurityScopedStorage.acquireLease(destination, DirUsage.Backup)
    try {
        BackupFileOps.copyFile(
            zipFilePath,
            destination.trimEnd('/') + BackupFileOps.separator + fileName,
        )
    } finally {
        lease.close()
    }
    return true
}
