package io.legado.app.help.file

import io.legado.app.help.http.OkHttpClientProviders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * [FileDownloader] 的桌面 JVM actual 实现。
 *
 * 用 OkHttp 同步下载到 [java.nio.file.Path], 走 [OkHttpClientProviders] 全局
 * OkHttpClient (与项目其他 OkHttp 调用一致, 复用连接池/超时/拦截器配置)。
 *
 * # 设计要点
 * - suspend 内部用 `kotlinx.coroutines.Dispatchers.IO` 阻塞式下载 (OkHttp 同步 execute)
 * - 目标目录不存在时自动创建 (Files.createDirectories)
 * - 临时文件下载 + 原子移动 (StandardCopyOption.ATOMIC_MOVE), 避免半下载文件被读
 * - 失败返回 false, 不抛异常 (与 Android 实现一致)
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class DesktopFileDownloader : FileDownloader {

    override suspend fun download(url: String, destPath: String, fileName: String): Boolean {
        return kotlin.runCatching {
            val client = getOkHttpClient()
            val request = Request.Builder().url(url).build()

            // 确保目标目录存在
            val destDir: Path = Paths.get(destPath)
            if (!Files.exists(destDir)) {
                Files.createDirectories(destDir)
            }

            // 临时文件 (".fileName.part"), 下载完成后原子移动到正式文件名
            val targetPath: Path = destDir.resolve(fileName)
            val tempPath: Path = destDir.resolve("$fileName.part")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code ${response.code}")
                }
                response.body.byteStream().use { input ->
                    Files.copy(input, tempPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            // 原子移动到正式文件名 (跨文件系统时退化为非原子 replace)
            try {
                Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrElse {
            // 下载失败: 清理临时文件 (避免残留 .part 占用空间)
            kotlin.runCatching {
                val tempPath = Paths.get(destPath, "$fileName.part")
                Files.deleteIfExists(tempPath)
            }
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
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerDesktopFileDownloader() {
    FileDownloaders.register(DesktopFileDownloader())
}
