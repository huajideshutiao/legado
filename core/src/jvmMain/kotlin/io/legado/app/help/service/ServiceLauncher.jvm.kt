package io.legado.app.help.service

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.NotificationId
import io.legado.app.help.file.FileDownloaders
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.notification.NotificationProgresses
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
import io.legado.app.utils.openFileWithSystem
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.file.Paths

/**
 * [ServiceLauncher] 的桌面 JVM actual 实现。
 *
 * 桌面端无 Android Service 概念, 用 [CoroutineScope] 直接 launch 协程调度等价任务。
 *
 * # 设计要点
 * - 持有 [CoroutineScope] (SupervisorJob + Default dispatcher), 子任务异常不互相影响
 * - **startCacheBookService**: 接入已下沉的 [CacheBookShared], 创建 CacheBookModelShared +
 *   addDownload + 把 startProcessJob 跑到结束 (对照 app 端 CacheBookService.addDownloadData +
 *   download: 一次启动 = 一次会话, 入队失败也照样把调度跑到结束)
 * - **removeCacheBookService**: 调 `CacheBookShared.cacheBookMap[bookUrl]?.stop()` +
 *   postEvent (对照 app 端 CacheBookService.removeDownload, 桌面端无 stopSelf)
 * - **stopCacheBookService**: 调 [CacheBookShared.close] (对照 app 端 CacheBookService.onDestroy)
 * - **startUpdateBookService / stopUpdateBookService 已删 (2026-09)**: 零调用点死代码; 书架自动更新走共享触发链
 *   (进书架页 → 分组流发射 → UpdateBookShared.scheduleAutoUpdate), 取消刷新任务走
 *   BookshelfViewModel → UpdateBookShared, 均不停靠本启动器。
 * - **startDownloadService**: 真实下载, 用 [FileDownloaders] 写文件到
 *   `{desktopAppRootDir}/downloads/fileName`, 完成后交系统默认程序打开
 *   (对照 app 端 DownloadService 下载完 openFileUri; 打不开时 openFileWithSystem 内部记 AppLog,
 *   用户仍能从 toast 里的绝对路径找到文件)
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 *
 * # 注意
 * - startCacheBookService 的目录保障与内存目录快照兜底都在共享入队编排
 *   ([CacheBookShared.enqueueDownload]) 内, 四端缓存入口同一实现。
 * - downloadJob 重启逻辑 (app 端 CacheBookService.removeDownload 内
 *   `if (downloadJob == null && CacheBook.isRun) download()`) 桌面端由
 *   阅读 VM 监听 isRun 自行管理, 本方法不直接重启。
 */
class DesktopServiceLauncher(
    private val scope: CoroutineScope,
) : ServiceLauncher {

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        // 接入已下沉的 CacheBookShared: 创建模型 + 入队 + 把处理 job 跑到结束
        // (对照 app 端 CacheBookService.addDownloadData + download)
        scope.launch {
            CacheBookShared.enqueueDownload(bookUrl, start, end)
            // 取不到书 (不在书架/已删/无书源) 或目录保障失败时池为空, 本行让 job 立即结束: 与原版
            // addDownloadData 的 onFinally 无条件 download() (内部 startProcessJob 返回后 stopSelf)
            // 一致 —— "服务起来了但没有可入队的书" 同样以调度 job 结束收尾, 不留悬空会话
            CacheBookShared.startProcessJob()
        }
    }

    override fun stopCacheBookService() {
        // 对照 app 端 CacheBookService.onDestroy: 清理所有下载任务
        CacheBookShared.close()
        postEvent(EventBus.UP_DOWNLOAD, "")
    }

    override fun removeCacheBookService(bookUrl: String) {
        // 对照 app 端 CacheBookService.removeDownload: 仅移除单本书, 其他书继续
        CacheBookShared.cacheBookMap[bookUrl]?.stop()
        postEvent(EventBus.UP_DOWNLOAD, "")
        // 桌面端无 stopSelf; downloadJob 重启由桌面端阅读 VM 监听 isRun 自行管理
    }

    override fun startDownloadService(url: String, fileName: String) {
        // 真实下载: 用 FileDownloader 写文件到 {desktopAppRootDir}/downloads/fileName,
        // 完成后交系统默认程序打开 (对照 app 端 DownloadService 监听
        // ACTION_DOWNLOAD_COMPLETE 后 openFileUri: 安装包交给安装器, 其余交默认程序);
        // toast 带绝对路径, 打不开时用户仍能自己找到文件
        scope.launch {
            val destPath = Paths.get(desktopAppRootDir(), "downloads").toString()
            val ok = FileDownloaders.get().download(url, destPath, fileName)
            if (!ok) {
                AppLog.put("下载失败: url=$url fileName=$fileName", tag = "DesktopServiceLauncher")
                Toasters.get().toast("下载失败: $fileName")
                return@launch
            }
            val file = Paths.get(destPath, fileName).toFile()
            Toasters.get().toast("下载完成: ${file.absolutePath}")
            openFileWithSystem(file.absolutePath)
        }
    }
}

/**
 * [UpdateBookCallback] 的桌面实现: 把进度/提示桥接到已注册的 [NotificationProgresses]
 * (SystemTray 通知) 与 [Toasters]。文案与 nativeMain `NativeUpdateBookCallback` 一致。
 *
 * 进度通知固定用 [NotificationId.UpdateBookService] (与 app 端该通知同一 id)。
 */
object DesktopUpdateBookCallback : UpdateBookCallback {

    override fun onProgressUpdate(active: Boolean, title: String, content: String, count: Int, total: Int) {
        if (!active) {
            runCatching { NotificationProgresses.get().cancel(NotificationId.UpdateBookService) }
            return
        }
        runCatching {
            NotificationProgresses.get().showProgress(
                NotificationId.UpdateBookService, title, content, count, total,
            )
        }
    }

    override fun onProgressCancel() {
        runCatching { NotificationProgresses.get().cancel(NotificationId.UpdateBookService) }
    }

    override fun toastForceRefreshBusy() {
        runCatching { Toasters.get().toast("正在刷新中, 请稍后再试") }
    }

    override fun toastForceRefreshStart(count: Int) {
        runCatching { Toasters.get().toast("开始强制刷新 $count 本") }
    }

    override fun toastForceRefreshDone() {
        runCatching { Toasters.get().toast("刷新完成") }
    }
}

/**
 * 桌面宿主启动早期注册 [ServiceLauncher] 的 actual 实现。
 *
 * 调用时机: desktop main(), 在任何 commonMain 代码调用 `ServiceLaunchers.get()` 之前。
 *
 * @param scope 协程作用域, 用于 launch 后台任务; 不传则创建独立的 SupervisorJob + Default
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerDesktopServiceLauncher(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    ServiceLaunchers.register(DesktopServiceLauncher(scope))
}
