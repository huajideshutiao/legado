package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.utils.InputStream
import io.legado.app.utils.atomicWriteTempPath

/**
 * 整份写入 [path] (先写同目录唯一临时件, 再原子改名就位), 成功返回 true。
 *
 * 目标文件在写入期间始终可读为完整内容: 直接覆盖写会在写完之前把目标截断, 与此同时按同一
 * 路径读该文件的一方 (存在性判断、路径直读) 会拿到半截字节。写入方跨线程/跨进程并发写同一
 * 目标时, 覆盖写造成的半截文件会被读方当作完整内容消费。
 *
 * 临时件与目标同目录 (改名才同卷), 名字按共享契约生成 ([io.legado.app.utils.atomicWriteTempPath]:
 * 随机唯一串, 两次写入不共用路径); 写临时件失败或改名失败时清掉临时件并返回 false,
 * 目标保持原有完整内容 (或不存在)。被强杀残留的临时件是孤儿件, 由目录清点方按
 * [io.legado.app.utils.isAtomicWriteTempName] 排除并回收。
 */
fun writeBytesAtomically(path: String, data: ByteArray): Boolean {
    val tempPath = atomicWriteTempPath(path)
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

/**
 * 整份写入文本 (UTF-8): [writeBytesAtomically] 的文本入口, 落盘机制就是同一份
 * (同目录临时件 + 原子改名), 不另做一套。
 *
 * 文本类缓存 (章节正文/配置快照) 同样是"写整份、另一条路径随时整读"的用法: 直接覆盖写会让
 * 读方在写入未完成时拿到半截文本 (缺尾/混码), 且读方拿不出可判别的错误。
 */
fun writeTextAtomically(path: String, text: String): Boolean =
    writeBytesAtomically(path, text.encodeToByteArray())

/**
 * 流式整份写入 [path]: 分块写同目录临时件, 写成功后原子改名就位; 任一步失败都清掉临时件并抛出。
 *
 * 与 [writeBytesAtomically] 同一落盘机制 (同目录唯一临时件 + 原子改名) 与同一条读方前提
 * ("目标名存在" = 内容完整), 差别只在本入口按块写流 —— 大体积内容 (在线音频) 不该有整份
 * 内存峰值。失败沿用 [FileUtilsCommon.copyToFile] 的异常通道 (流式失败即失败, 调用方拿得到
 * 原始类型与堆栈): 目标保持原有完整内容 (或不存在), 不会在目标名上留下半截字节让读方复用。
 *
 * @throws Exception 写临时件失败时抛复制异常; 改名失败时抛 [IllegalStateException]
 */
fun copyToFileAtomically(path: String, input: InputStream) {
    val tempPath = atomicWriteTempPath(path)
    try {
        FileUtilsCommon.copyToFile(tempPath, input)
        check(FileUtilsCommon.replaceFileAtomically(tempPath, path)) {
            "整份写入失败, 目标保持原内容: $path"
        }
    } catch (t: Throwable) {
        FileUtilsCommon.delete(tempPath)
        throw t
    }
}
