package io.legado.app.help

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.legado.app.utils.File
import io.legado.app.utils.atomicWriteTempPath
import io.legado.app.utils.ensureParentDirectory
import okio.FileSystem
import okio.Path.Companion.toPath

/** 通道 → 文件的拷贝块大小 (64KB): 内存占用只有这一块复用缓冲区, 与文件体积无关。 */
private const val CHANNEL_COPY_BUFFER_SIZE = 64 * 1024

/**
 * 流式整份写入 [path]: native 端入口, 内容从挂起通道 [channel] 分块读出。
 *
 * Ktor 响应体只有挂起通道 (拿不到 [io.legado.app.utils.InputStream]), 共享的
 * [copyToFileAtomically] (InputStream 参数) 在该端字面不可达, 故按同一落盘机制另设本重载:
 * 临时件名用 [atomicWriteTempPath] 生成 (同目标的两次写入绝不共用一条路径), 写完用
 * [FileUtilsCommon.replaceFileAtomically] 改名就位; 任一步失败都删掉临时件并抛出 —— 目标名上
 * 始终是原有的完整内容 (或不存在), 半截字节只会落在临时件里。
 *
 * 目标在用户可见目录 (导出/分享/下载目录) 时没有目录清点方, 写入方须在每条退出路径自行清掉
 * 临时件 (见 [io.legado.app.utils.ATOMIC_WRITE_TEMP_SUFFIX])。
 *
 * @throws Exception 读通道或写临时件失败时抛原异常 (含取消); 改名失败时抛 [IllegalStateException]
 */
suspend fun copyToFileAtomically(path: String, channel: ByteReadChannel) {
    val tempPath = atomicWriteTempPath(path)
    try {
        File(tempPath).ensureParentDirectory()
        FileSystem.SYSTEM.write(tempPath.toPath()) {
            val buffer = ByteArray(CHANNEL_COPY_BUFFER_SIZE)
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n == -1) break
                if (n > 0) write(buffer, 0, n)
            }
        }
        check(FileUtilsCommon.replaceFileAtomically(tempPath, path)) {
            "整份写入失败, 目标保持原内容: $path"
        }
    } catch (t: Throwable) {
        FileUtilsCommon.delete(tempPath)
        throw t
    }
}
