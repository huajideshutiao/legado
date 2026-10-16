package io.legado.app.ui.book.read.config

import io.legado.app.constant.AppLog
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.writeBytesAtomically

/**
 * 把字体文件复制进内置字体目录 (字体扫描与加载都只认这个目录)。
 *
 * 源可能是用户选定目录里的文件、系统面板复制进沙盒的临时件或云盘上的占位件: 复制进内置目录后
 * 不再依赖目录授权与云盘下载状态, 同名文件按覆盖语义替换 (整份复制, 落盘机制复用备份侧的
 * [BackupFileOps.copyFile])。
 *
 * @param fileName 目标文件名 (取选择面板给的显示名, 不用物化副本为防重名加前缀的名字)
 * @return 复制成功返回 true; 读源失败/写盘失败记日志后返回 false (调用方如实报失败)
 */
fun importFontFileToInternalDir(srcRef: String, fileName: String): Boolean =
    runCatching {
        BackupFileOps.copyFile(
            srcRef,
            FileUtilsCommon.getPath(DataStorageProviders.get().fontsDir, fileName),
        )
    }.onFailure {
        AppLog.put("字体导入失败: $fileName\n${it.message}", it)
    }.isSuccess

/**
 * 把字体字节写进内置字体目录 (面板回传文档地址的端先自己读字节再调本函数)。
 */
fun saveFontBytesToInternalDir(fileName: String, bytes: ByteArray): Boolean =
    bytes.isNotEmpty() &&
        writeBytesAtomically(
            FileUtilsCommon.getPath(DataStorageProviders.get().fontsDir, fileName),
            bytes,
        )
