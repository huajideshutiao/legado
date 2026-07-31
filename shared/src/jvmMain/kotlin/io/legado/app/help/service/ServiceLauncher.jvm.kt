package io.legado.app.help.service

import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.help.file.FileDownloaders
import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.help.notification.NotificationProgresses
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
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
 *   addDownload + 在 scope 内 launch startProcessJob (对照 app 端 CacheBookService.addDownloadData +
 *   download)
 * - **removeCacheBookService**: 调 [CacheBookShared.cacheBookMap][bookUrl]?.stop] +
 *   postEvent (对照 app 端 CacheBookService.removeDownload, 桌面端无 stopSelf)
 * - **stopCacheBookService**: 调 [CacheBookShared.close] (对照 app 端 CacheBookService.onDestroy)
 * - **startUpdateBookService / stopUpdateBookService**: 接入已下沉的 [UpdateBookShared],
 *   start 从 DB 查全部书调 [UpdateBookShared.scheduleAutoUpdate], stop 调
 *   [UpdateBookShared.cancelRefreshJobs] (对照 app 端 MainViewModel 同名方法)
 * - **startDownloadService**: 真实下载, 用 [FileDownloaders] 写文件到
 *   `{desktopAppRootDir}/downloads/fileName`
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 *
 * # 注意
 * - startCacheBookService 的 chapterList 加载 + 目录为空时拉取详情/目录逻辑
 *   (app 端 CacheBookService.addDownloadData 内 mutex.withLock 段) 由桌面端
 *   阅读 VM 的 cacheBook()/addDownload() 在调用 startCacheBookService 前
 *   自行处理, 本方法仅做 addDownload + startProcessJob (与桌面端原 DesktopCacheBook 配合
 *   桌面端阅读 VM 的现有分工一致)。
 * - downloadJob 重启逻辑 (app 端 CacheBookService.removeDownload 内
 *   `if (downloadJob == null && CacheBook.isRun) download()`) 桌面端由
 *   阅读 VM 监听 isRun 自行管理, 本方法不直接重启。
 */
class DesktopServiceLauncher(
    private val scope: CoroutineScope,
) : ServiceLauncher {

    /**
     * UpdateBook 编排核心 (commonMain 下沉件), 跨 start/stopUpdateBookService 复用同一实例
     * (与 app 端 MainViewModel 持有 UpdateBookShared 一致)。
     */
    private val updateBookShared: UpdateBookShared by lazy {
        UpdateBookShared(scope, UpdateBookCallbacks.getDefault() ?: DesktopUpdateBookCallback)
    }

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        // 接入已下沉的 CacheBookShared: 创建模型 + 入队 + 启动处理 job
        // (对照 app 端 CacheBookService.addDownloadData + download)
        scope.launch {
            val cacheBook = CacheBookShared.getOrCreate(bookUrl) ?: return@launch
            // end<0 表示下载到最后一章 (app 端 CacheBookService.addDownloadData 内
            // `if (end < 0) book.lastChapterIndex else min(end, book.lastChapterIndex)`)
            val actualEnd = if (end < 0) {
                cacheBook.book.lastChapterIndex
            } else {
                minOf(end, cacheBook.book.lastChapterIndex)
            }
            cacheBook.addDownload(start, actualEnd)
            // 启动 startProcessJob (对照 app 端 CacheBookService.download)
            // 桌面端无 cachePool CoroutineDispatcher, 用 scope 默认 dispatcher
            CacheBookShared.startProcessJob(scope.coroutineContext)
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

    override fun startUpdateBookService() {
        // 接入已下沉的 UpdateBookShared (commonMain 编排核心): 从 DB 查所有书后调
        // scheduleAutoUpdate 触发自动更新 (内部按 canUpdate + lastCheckTime 时间窗过滤)。
        scope.launch {
            val books = AppDbProviders.get().bookDao.all()
            updateBookShared.scheduleAutoUpdate(books)
        }
    }

    override fun stopUpdateBookService() {
        // 对照 app 端 MainViewModel.cancelRefreshJobs (桌面端无 stopService)
        updateBookShared.cancelRefreshJobs()
    }

    override fun startDownloadService(url: String, fileName: String) {
        // 真实下载: 用 FileDownloader 写文件到 {desktopAppRootDir}/downloads/fileName
        scope.launch {
            val destPath = Paths.get(desktopAppRootDir(), "downloads").toString()
            val ok = FileDownloaders.get().download(url, destPath, fileName)
            if (!ok) {
                System.err.println("[DesktopServiceLauncher] download failed: url=$url fileName=$fileName")
            }
        }
    }
}

/**
 * [UpdateBookCallback] 的桌面实现: 把进度/提示桥接到已注册的 [NotificationProgresses]
 * (SystemTray 通知) 与 [Toasters]。文案与 nativeMain `NativeUpdateBookCallback` 一致。
 */
object DesktopUpdateBookCallback : UpdateBookCallback {

    override fun onProgressUpdate(active: Boolean, title: String, content: String, count: Int, total: Int) {
        if (!active) {
            runCatching { NotificationProgresses.get().cancel() }
            return
        }
        runCatching { NotificationProgresses.get().showProgress(title, content, count, total) }
    }

    override fun onProgressCancel() {
        runCatching { NotificationProgresses.get().cancel() }
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
