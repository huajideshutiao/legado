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
 * 备份 zip 写进用户选定的书籍保存位置下的 backup 子目录: 写入期间持该目录的 security-scoped 租约。
 *
 * 直接持租约 ([IosSecurityScopedStorage.acquireLease]) 而不走 NSFileCoordinator: 协调器会对
 * 尚不存在的目标文件报错, 而这里需要的只是授权 (与书籍保存目录写入同一模式)。
 * 租约按书籍用途取且按保存位置根匹配: 只有用户在书籍保存位置入口选定的目录才放行,
 * 为字体/图片选的目录不会。
 * 授权已被撤销/目录不在授权表里时 acquireLease 抛 SecurityException, 备份随之报失败原因,
 * 不会留下一个"看起来成功、实际没写出去"的备份。
 *
 * 沙盒内目录取到的是空租约, 无需 scope, 写盘照旧 (目标子目录由 [BackupFileOps.copyFile] 建出)。
 */
internal actual fun copyBackupIntoUserDir(
    zipFilePath: String,
    destination: String,
    fileName: String,
): Boolean {
    val lease = IosSecurityScopedStorage.acquireLease(destination, DirUsage.BookTree)
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
