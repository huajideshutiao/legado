package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.utils.randomUUIDString

/**
 * 整份写入 [path] (先写同目录唯一临时件, 再原子改名就位), 成功返回 true。
 *
 * 目标文件在写入期间始终可读为完整内容: 直接覆盖写会在写完之前把目标截断, 与此同时按同一
 * 路径读该文件的一方 (存在性判断、路径直读) 会拿到半截字节。写入方跨线程/跨进程并发写同一
 * 目标时, 覆盖写造成的半截文件会被读方当作完整内容消费。
 *
 * 临时件与目标同目录 (改名才同卷), 名字带随机唯一串, 两次写入不共用路径; 写临时件失败或
 * 改名失败时清掉临时件并返回 false, 目标保持原有完整内容 (或不存在)。
 */
fun writeBytesAtomically(path: String, data: ByteArray): Boolean {
    val tempPath = "$path.${randomUUIDString()}$ATOMIC_WRITE_TEMP_SUFFIX"
    if (!FileUtilsCommon.writeBytes(tempPath, data)) {
        // 写失败可能已建出父目录与空临时件, 一并清掉
        FileUtilsCommon.delete(tempPath)
        return false
    }
    if (FileUtilsCommon.replaceFileAtomically(tempPath, path)) return true
    FileUtilsCommon.delete(tempPath)
    AppLog.put("整份写入失败, 目标保持原内容: $path")
    return false
}

/** 临时件后缀 (落在目标同目录, 改名就位才能同卷)。 */
private const val ATOMIC_WRITE_TEMP_SUFFIX = ".tmp"

/**
 * 整份写入文本 (UTF-8): [writeBytesAtomically] 的文本入口, 落盘机制就是同一份
 * (同目录临时件 + 原子改名), 不另做一套。
 *
 * 文本类缓存 (章节正文/配置快照) 同样是"写整份、另一条路径随时整读"的用法: 直接覆盖写会让
 * 读方在写入未完成时拿到半截文本 (缺尾/混码), 且读方拿不出可判别的错误。
 */
fun writeTextAtomically(path: String, text: String): Boolean =
    writeBytesAtomically(path, text.encodeToByteArray())
