package io.legado.app.help.storage

import io.legado.app.constant.DirUsage
import io.legado.app.help.file.OhosDirAuthorizations

/** 鸿蒙无动态桌面图标 API, 空实现。 */
internal actual fun applyRestoredLauncherIcon() = Unit

/**
 * 备份 zip 写进用户选定目录: 写入前先激活该目录的持久授权。
 *
 * 鸿蒙的目录授权是持久态 (选目录时已 persistPermission), 但重启后必须先 activatePermission ——
 * 备份既有"手动备份"(选目录后预检可写性时已激活) 也有"自动备份"(直接写记忆目录) 两条入口,
 * 后者不经过预检, 故授权统一在写入前按需激活 ([OhosDirAuthorizations.ensureActivatedFor],
 * 未登记路径直通, 按备份用途匹配: 为书籍/导出/字体选的目录不放行), 激活失败抛明确异常,
 * 备份随之报失败原因, 不会静默丢备份。
 */
internal actual fun copyBackupIntoUserDir(
    zipFilePath: String,
    destination: String,
    fileName: String,
): Boolean {
    OhosDirAuthorizations.ensureActivatedFor(destination, DirUsage.Backup)
    BackupFileOps.copyFile(
        zipFilePath,
        destination.trimEnd('/') + BackupFileOps.separator + fileName,
    )
    return true
}
