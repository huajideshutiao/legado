package io.legado.app.help.file

import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.deleteOrphanAtomicWriteTempFilesInDir
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Paths

/**
 * [FileDownloader] 的桌面 JVM actual 实现。
 *
 * 用 OkHttp 同步下载到 [java.nio.file.Path], 走 [OkHttpClientProviders] 全局
 * OkHttpClient (与项目其他 OkHttp 调用一致, 复用连接池/超时/拦截器配置)。
 *
 * # 设计要点
 * - suspend 内部整体切到 `kotlinx.coroutines.Dispatchers.IO`: OkHttp 同步 execute 与落盘都是阻塞调用,
 *   而注册方的默认作用域是 `Dispatchers.Default` (见 `registerDesktopServiceLauncher`), 在 Default 上
 *   阻塞会占满 CPU 并行度
 * - 落盘走共享整份写入契约 [copyToFileAtomically]: 响应体分块流式写目标同目录的唯一临时件,
 *   写完原子改名就位; 目标名上始终是完整内容 (不会出现半截下载), 写失败由契约清掉临时件,
 *   下次成功下载覆盖同名文件。临时件名由共享契约生成, 目录清点方按名排除/回收
 * - 下载目录是用户可见目录, 没有目录清点方, 故写入方自清: 每次下载前回收下载目录本层里已成孤儿
 *   的临时件 ([deleteOrphanAtomicWriteTempFilesInDir]: 只认共享契约的临时件名且超过可回收时限,
 *   正在写的那份不会被判成孤儿; 下载的目标与临时件都在本层, 不扫子目录)
 * - 失败返回 false, 不抛异常 (与 native 实现一致)
 */
class DesktopFileDownloader : FileDownloader {

    override suspend fun download(url: String, destPath: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                deleteOrphanAtomicWriteTempFilesInDir(destPath)
                val request = Request.Builder().url(url).build()
                // 目标目录不存在时由整份写入契约建立 (临时件与目标同目录)
                val targetPath = Paths.get(destPath, fileName).toString()

                getOkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected code ${response.code}")
                    }
                    response.body.byteStream().use { input ->
                        copyToFileAtomically(targetPath, input)
                    }
                }
                true
            }.getOrElse {
                // 写失败时临时件已由契约清掉, 目标保持原有完整内容 (或不存在)
                false
            }
        }

    /**
     * 取全局 OkHttpClient (走 [OkHttpClientProviders], 与项目其他 OkHttp 调用一致)。
     * 未注册时回退到默认 OkHttpClient (兜底, 避免桌面端启动顺序依赖)。
     */
    private fun getOkHttpClient(): OkHttpClient {
        return kotlin.runCatching {
            OkHttpClientProviders.get().okHttpClient
        }.getOrElse {
            OkHttpClient()
        }
    }
}

/**
 * 桌面宿主启动早期注册 [FileDownloader] 的 actual 实现。
 *
 * 调用时机: desktop main(), 在任何 commonMain 代码调用 `FileDownloaders.get()` 之前。
 *
 * 模式参考 `registerDesktopConfig` / `registerDesktopWebBookProviders`。
 */
fun registerDesktopFileDownloader() {
    FileDownloaders.register(DesktopFileDownloader())
}
