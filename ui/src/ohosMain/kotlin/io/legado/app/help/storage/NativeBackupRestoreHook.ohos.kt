package io.legado.app.help.storage

import io.legado.app.constant.DirUsage
import io.legado.app.help.file.OhosDirAuthorizations

/** 鸿蒙无动态桌面图标 API, 空实现。 */
internal actual fun applyRestoredLauncherIcon() = Unit

/**
 * 备份 zip 写进用户选定的书籍保存位置下的 backup 子目录: 写入前先按书籍用途激活该目录的持久授权。
 *
 * 鸿蒙的目录授权在选择目录时已 persistPermission 持久化, 重启后须先 activatePermission ——
 * 手动备份与自动备份两条入口都在写入前按需激活 ([OhosDirAuthorizations.ensureActivatedFor],
 * 保存位置须在书籍用途下已登记, 应用私有目录直通; 子目录路径按根前缀匹配同样命中),
 * 激活失败或未登记抛明确异常, 备份随之报失败原因, 不会静默丢备份。
 */
internal actual fun copyBackupIntoUserDir(
    zipFilePath: String,
    destination: String,
    fileName: String,
): Boolean {
    OhosDirAuthorizations.ensureActivatedFor(destination, DirUsage.BookTree)
    BackupFileOps.copyFile(
        zipFilePath,
        destination.trimEnd('/') + BackupFileOps.separator + fileName,
    )
    return true
}
