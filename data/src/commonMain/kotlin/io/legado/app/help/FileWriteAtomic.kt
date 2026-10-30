package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.utils.InputStream
import io.legado.app.utils.atomicWriteTempPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/**
 * 整份写入 [path] (先写同目录唯一临时件, 再原子改名就位), 成功返回 true。
 *
 * 目标文件在写入期间始终可读为完整内容: 直接覆盖写会在写完之前把目标截断, 与此同时按同一
 * 路径读该文件的一方 (存在性判断、路径直读) 会拿到半截字节。写入方跨线程/跨进程并发写同一
 * 目标时, 覆盖写造成的半截文件会被读方当作完整内容消费。
 *
 * 临时件与目标同目录 (改名才同卷), 名字按共享契约生成 ([io.legado.app.utils.atomicWriteTempPath]:
 * 随机唯一串, 两次写入不共用路径); 写临时件失败、改名失败、资格检查的取消与替换异常的
 * 每条路径都清掉本次临时件 (取消与异常原样传播), 目标保持原有完整内容 (或不存在)。
 * 被强杀残留的临时件是孤儿件, 由目录清点方按
 * [io.legado.app.utils.isAtomicWriteTempName] 排除并回收。
 */
fun writeBytesAtomically(path: String, data: ByteArray, parent: Job? = null): Boolean {
    val tempPath = atomicWriteTempPath(path)
    try {
        // 准备阶段与最终替换前各核一次: 已撤销的请求不得继续写临时件、更不得动正式件
        parent?.ensureActive()
        if (!FileUtilsCommon.writeBytes(tempPath, data)) {
            return false
        }
        // 进入正式提交 (替换正式名) 之前最后核一次: 已撤销不得动正式件
        parent?.ensureActive()
        if (FileUtilsCommon.replaceFileAtomically(tempPath, path)) return true
        AppLog.put("整份写入失败, 目标保持原内容: $path")
        return false
    } finally {
        // 写失败/改名失败/资格检查取消/替换异常的每条路径都清掉本次临时件;
        // 改名成功后临时件已就位为目标, 同名不再存在, 这里的删除是无害空操作
        FileUtilsCommon.delete(tempPath)
    }
}

/**
 * 整份写入文本 (UTF-8): [writeBytesAtomically] 的文本入口, 落盘机制就是同一份
 * (同目录临时件 + 原子改名), 不另做一套。
 *
 * 文本类缓存 (章节正文/配置快照) 同样是"写整份、另一条路径随时整读"的用法: 直接覆盖写会让
 * 读方在写入未完成时拿到半截文本 (缺尾/混码), 且读方拿不出可判别的错误。
 */
fun writeTextAtomically(path: String, text: String, parent: Job? = null): Boolean =
    writeBytesAtomically(path, text.encodeToByteArray(), parent)

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
fun copyToFileAtomically(path: String, input: InputStream, parent: Job? = null) {
    val tempPath = atomicWriteTempPath(path)
    try {
        // 准备循环每块前核父 ([FileUtilsCommon.copyToFile] 内), 这里补进入前的资格
        parent?.ensureActive()
        FileUtilsCommon.copyToFile(tempPath, input, parent)
        // 最终替换前再核一次: 已撤销不得动正式件
        parent?.ensureActive()
        check(FileUtilsCommon.replaceFileAtomically(tempPath, path)) {
            "整份写入失败, 目标保持原内容: $path"
        }
    } catch (t: Throwable) {
        FileUtilsCommon.delete(tempPath)
        throw t
    }
}
