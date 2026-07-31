package io.legado.app.help.file

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import io.legado.app.utils.File

/**
 * [FileDownloader] 的 nativeMain 真实实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉)。
 *
 * 用 Ktor HttpClient (CIO engine, 纯 Kotlin, iOS/鸿蒙 linuxArm64 变体可用) 同步下载到
 * [kotlin.io.File] (POSIX fs, Kotlin/Native 标准库支持):
 * - 目标目录不存在时自动创建 ([File.mkdirs], 与 NSFileManager.createDirectoryAtPath 等价)
 * - 用先写 `.tmp` 再 [File.renameTo] 实现原子写入, 避免半下载文件被读
 *   (替代原 iOS 端 `NSData.writeToFile(atomically=true)`, kotlin.io.File 无原生原子写)
 * - 失败返回 false, 不抛异常 (与 jvmAndAndroidMain 桌面实现一致)
 *
 * # 设计要点
 * - suspend 内部用 [Dispatchers.IO] 阻塞式下载
 *   (Ktor CIO engine 在 iOS/linuxArm64 上是 true blocking, 与 OkHttp JVM 同步 execute 等价)
 * - 整文件 bytes 一次性写入 (与原 iOS bodyAsBytes + NSData.create / 鸿蒙 bodyAsBytes + writeBytes 行为等价)
 * - 路径分隔符恒为 "/" (POSIX 文件系统)
 *
 * # 下沉说明
 * 原 iosMain [IosFileDownloader] (NSFileManager + NSData.writeToFile atomically) 与
 * ohosMain [OhosFileDownloader] (kotlin.io.File + tmpFile + renameTo) 行为等价。
 * 按 nativeMain 下沉约束 (NSFileManager↔kotlin.io.File 差异统一用 kotlin.io.File),
 * 合并到 nativeMain, iOS/鸿蒙共用; 原子写统一用 tmpFile + renameTo (kotlin.io.File 无原子写 API)。
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` / `DesktopFileDownloader`。
 */
class NativeFileDownloader : FileDownloader {

    override suspend fun download(url: String, destPath: String, fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                // 确保目标目录存在 (mkdirs 递归创建, 与 Files.createDirectories / NSFileManager.createDirectoryAtPath 等价)
                val destDir = File(destPath)
                if (!destDir.exists()) {
                    destDir.mkdirs()
                }

                // 用 Ktor HttpClient 同步下载 (CIO engine, 全量 bodyAsBytes 装入内存)
                // 与原 iOS/鸿蒙端 HttpClient(CIO) + client.get(url) + bodyAsBytes() 行为等价
                val client = HttpClient(CIO)
                try {
                    val response = client.get(url)
                    if (!response.status.isSuccess()) {
                        false
                    } else {
                        val bytes = response.bodyAsBytes()

                        // 原子写入: 先写 .tmp 再 renameTo 目标 (替代 NSData.writeToFile(atomically=true))
                        // 失败路径: tmpFile 残留不污染目标文件, 下次成功下载会覆盖
                        val targetPath = if (destPath.endsWith("/")) "$destPath$fileName" else "$destPath/$fileName"
                        val targetFile = File(targetPath)
                        val tmpFile = File("$targetPath.tmp")
                        tmpFile.writeBytes(bytes)
                        // renameTo 失败兜底: 删除 tmpFile 避免残留, 返回 false
                        if (!tmpFile.renameTo(targetFile)) {
                            tmpFile.delete()
                            false
                        } else {
                            true
                        }
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
