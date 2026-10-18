package io.legado.app.help.file

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.deleteOrphanAtomicWriteTempFilesInDir
import io.legado.app.utils.canonicalizeUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * [FileDownloader] 的 nativeMain 真实实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉)。
 *
 * 用 Ktor HttpClient (引擎由各目标的依赖选择: iOS Darwin、鸿蒙 CIO) 下载到
 * [io.legado.app.utils.File] (okio 文件面, POSIX fs):
 * - 目标目录不存在时由整份写入契约建立 (临时件与目标同目录)
 * - 落盘走共享整份写入契约的 native 入口 [copyToFileAtomically]: 响应体分块读、
 *   分块写目标同目录的唯一临时件, 写完原子改名就位; 目标名上始终是完整内容 (不会出现半截下载),
 *   写失败 (含取消) 由契约清掉临时件, 下次成功下载覆盖同名文件。
 *   临时件名由共享契约生成, 目录清点方按名排除/回收
 * - 下载目录是用户可见目录, 没有目录清点方, 故写入方自清: 每次下载前回收下载目录本层里已成孤儿
 *   的临时件 ([deleteOrphanAtomicWriteTempFilesInDir]: 只认共享契约的临时件名且超过可回收时限,
 *   正在写的那份不会被判成孤儿; 下载的目标与临时件都在本层, 不扫子目录)
 * - 失败返回 false, 不抛异常 (与 [DesktopFileDownloader] 一致)
 *
 * # 设计要点
 * - suspend 内部整体切到 [Dispatchers.IO]: 落盘是阻塞式文件 IO, 而注册方的默认作用域是 `Dispatchers.Default`
 *   (见 `registerIosServiceLauncher` / `registerOhosServiceLauncher`)
 * - 分块流式写入, 内存峰值只有一块复用缓冲区, 与文件体积无关
 * - 路径分隔符恒为 "/" (POSIX 文件系统)
 */
class NativeFileDownloader : FileDownloader {

    override suspend fun download(url: String, destPath: String, fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                deleteOrphanAtomicWriteTempFilesInDir(destPath)
                val client = HttpClient()
                try {
                    val response = client.get(url.canonicalizeUri())
                    if (!response.status.isSuccess()) {
                        false
                    } else {
                        val targetPath =
                            if (destPath.endsWith("/")) "$destPath$fileName" else "$destPath/$fileName"
                        copyToFileAtomically(targetPath, response.bodyAsChannel())
                        true
                    }
                } finally {
                    client.close()
                }
            }.getOrElse { false }
        }
    }
}

/**
 * 注册 [NativeFileDownloader] 到 [FileDownloaders] (iOS/鸿蒙共用)。
 */
fun registerNativeFileDownloader() {
    FileDownloaders.register(NativeFileDownloader())
}
