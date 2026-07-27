package io.legado.app.help.file

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri

/**
 * [FileDownloader] 的 Android actual 实现。
 *
 * 走系统 [android.app.DownloadManager], 行为对齐 app 端 `DownloadService.startDownload`
 * (见 `app/src/main/java/io/legado/app/service/DownloadService.kt`)。
 *
 * # 设计要点
 * - shared androidMain 不依赖 splitties, 用 `context.getSystemService` 直接取
 *   [DownloadManager] (与 splitties.systemservices.downloadManager 等价)
 * - 目标目录走 [Environment.DIRECTORY_DOWNLOADS] (与 app 端一致), [destPath] 参数
 *   被忽略 (DownloadManager 仅支持系统公共目录); 如需自定义目录, 调用方应自行实现
 * - suspend 立即返回 true 表示已成功入队 (与 DownloadManager 异步语义一致),
 *   不等待下载完成 (避免阻塞协程, 完成事件由系统通知栏回调)
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class AndroidFileDownloader(
    private val context: Context,
) : FileDownloader {

    /** 取系统 DownloadManager (等价 splitties.systemservices.downloadManager)。 */
    private val downloadManager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override suspend fun download(url: String, destPath: String, fileName: String): Boolean {
        return kotlin.runCatching {
            val request = DownloadManager.Request(url.toUri()).apply {
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setTitle(fileName)
                // app 端用 FileUtils.getMimeType, shared 不依赖 app 工具类,
                // 走系统自动嗅探 (传 null 让 DownloadManager 自行判断)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            downloadManager.enqueue(request)
            true
        }.getOrElse {
            // 失败 (常见: 无存储权限 / URL 非法) 返回 false, 不抛异常 (与 app 端 catch toast 一致)
            false
        }
    }
}

/**
 * 安卓宿主启动早期注册 [FileDownloader] 的 actual 实现。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `FileDownloaders.get()` 之前。
 *
 * @param context 任意 Context (推荐传 `appCtx`), 用于 DownloadManager
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerAndroidFileDownloader(context: Context) {
    FileDownloaders.register(AndroidFileDownloader(context.applicationContext))
}
