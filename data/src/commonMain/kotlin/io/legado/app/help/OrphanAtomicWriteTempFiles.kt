package io.legado.app.help

import io.legado.app.utils.isOrphanAtomicWriteTemp
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 孤儿临时件的回收 (共享实现, 四端同一份)。
 *
 * 整份写入被强杀会在目标同目录留下临时件 (名字契约见 [io.legado.app.utils.ATOMIC_WRITE_TEMP_SUFFIX]);
 * 它既不是缓存条目也没有写入方管理, 只能由目录清点方顺带回收。判定用共享的
 * [isOrphanAtomicWriteTemp] (临时件 + 超过可回收时限), 正在写的那份刚创建, 不会被删掉。
 *
 * 只在已经要列目录的清点动作里调用 (缓存目录清点 / LRU 淘汰 / 缓存 provider 初始化),
 * 不另起后台任务; 目录不存在或读不出来时无事发生 (listFiles 吞成空集)。
 *
 * 回收只覆盖缓存/条目目录: 导出目录、分享目录、用户选定目录没有清点方, 那里的临时件不自动回收
 * (见 [io.legado.app.utils.ATOMIC_WRITE_TEMP_SUFFIX] 的回收边界说明), 写入方须自己清。
 *
 * [path] 是孤儿临时件就删掉它。
 *
 * @param nowMillis 本次清点的当前时刻 (同一批文件共用一个时刻, 避免逐个取时钟)
 * @return 是否真的删掉了
 */
fun deleteOrphanAtomicWriteTempFile(path: String, nowMillis: Long): Boolean {
    val name = FileUtilsCommon.getName(path)
    if (!isOrphanAtomicWriteTemp(name, FileUtilsCommon.lastModified(path), nowMillis)) return false
    return FileUtilsCommon.delete(path)
}

/**
 * 回收 [dirPath] 及其子目录下所有孤儿临时件。
 *
 * 递归覆盖目标与临时件不同层的目录 (章节正文/图片缓存的目标在 `book_cache/<书>/` 与
 * `book_cache/<书>/images/` 两层); 写入方落点就在目录本层时用
 * [deleteOrphanAtomicWriteTempFilesInDir], 不扫与本次写入无关的子目录。
 *
 * @return 删除个数
 */
fun deleteOrphanAtomicWriteTempFiles(dirPath: String): Int =
    deleteOrphanAtomicWriteTempFiles(dirPath, systemCurrentTimeMillis(), recursive = true)

/**
 * 只回收 [dirPath] 本层的孤儿临时件 (不递归)。
 *
 * 目标与临时件同目录的写入方 (单文件下载: 目标与临时件都在下载目录本层) 只会把临时件留在本层,
 * 不扫子目录不遗漏本写入方的残留件。
 *
 * @return 删除个数
 */
fun deleteOrphanAtomicWriteTempFilesInDir(dirPath: String): Int =
    deleteOrphanAtomicWriteTempFiles(dirPath, systemCurrentTimeMillis(), recursive = false)

private fun deleteOrphanAtomicWriteTempFiles(dirPath: String, now: Long, recursive: Boolean): Int {
    var deleted = 0
    FileUtilsCommon.listFiles(dirPath).forEach { path ->
        if (deleteOrphanAtomicWriteTempFile(path, now)) deleted++
    }
    if (recursive) {
        FileUtilsCommon.listSubDirs(dirPath).forEach { child ->
            deleted += deleteOrphanAtomicWriteTempFiles(child, now, recursive = true)
        }
    }
    return deleted
}
