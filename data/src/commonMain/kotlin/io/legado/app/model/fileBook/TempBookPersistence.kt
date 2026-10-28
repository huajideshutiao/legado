package io.legado.app.model.fileBook

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.atomic

/**
 * 临时输入 (解压条目/请求暂存) 的长期化。
 *
 * 书文件来源位于请求暂存目录时，导入前先复制到应用内长期目录 (`filesDir/books`)：
 * 导入完成后清理暂存/解压结果不会删掉新入库书籍的真实文件。外部永久原件不经过这里，
 * 仍保持原版的引用导入与同名覆盖语义。
 */
object TempBookPersistence {

    private val sequence = atomic(0L)

    /**
     * 把 [tempPath] 复制为应用内长期文件，保留原文件名供展示，返回长期文件路径。
     *
     * 目标名带时间戳与进程内序号，不复用旧副本，也不影响对外显示名。
     */
    fun persist(tempPath: String, fileName: String): String {
        val sep = BackupFileOps.separator
        val dir = AppFilesDirs.get().filesDir + sep + "books"
        BackupFileOps.createFolderIfNotExist(dir)
        // 独占目录 + 原文件名: 独占用在目录上, 不进书名/originName
        val requestDir = dir + sep + "import-" + systemCurrentTimeMillis() + "-" +
            sequence.incrementAndGet()
        BackupFileOps.createFolderIfNotExist(requestDir)
        val target = requestDir + sep + fileName
        BackupFileOps.copyFile(tempPath, target)
        return target
    }
}
