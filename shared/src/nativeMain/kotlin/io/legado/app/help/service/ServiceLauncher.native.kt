package io.legado.app.help.service

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.file.FileDownloaders
import io.legado.app.help.notification.NotificationProgresses
import io.legado.app.help.toast.Toasters
import io.legado.app.model.CacheBookShared
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [ServiceLauncher] 的 Native (iOS / 鸿蒙) 共用 actual 实现。
 *
 * 原 iosMain/ohosMain 两份 actual 几乎逐字节相同 (仅日志 API 不同), 下沉 nativeMain 共用,
 * 平台端经 [registerIosServiceLauncher] / [registerOhosServiceLauncher] 注册。
 * 日志统一走 [io.legado.app.constant.AppLog] (native host 内部 println, iOS 端进 NSLog)。
 *
 * 设计要点 (对照 jvmMain DesktopServiceLauncher): 持有 [CoroutineScope]
 * (SupervisorJob + Default dispatcher, 子任务异常不互相影响);
 * start/remove/stopCacheBookService 接入已下沉的 [CacheBookShared] (对照 app 端
 * CacheBookService); start/stopUpdateBookService 接入 [UpdateBookShared]
 * (Native 无 MainViewModel, 从 DB 查所有书后 scheduleAutoUpdate);
 * startDownloadService 用 [FileDownloaders] 写文件 (iOS 沙盒 Documents 替代 user.home,
 * 鸿蒙 {user.dir}/legado_data/files)。
 *
 * Native 端无 Android Service 生命周期, 协程生命周期 = scope 生命周期 (进程退出时结束)。
 * 平台差异经 callback 注入: [NativeUpdateBookCallback] 桥接 NotificationProgresses +
 * Toasters (iOS: UNUserNotificationCenter / 鸿蒙: notificationManager)。
 */
class NativeServiceLauncher(
    private val scope: CoroutineScope,
) : ServiceLauncher {

    /**
     * UpdateBook 编排核心 (commonMain 下沉件), 持有实例以复用 across startUpdateBookService /
     * stopUpdateBookService 调用 (与 app 端 MainViewModel 持有 UpdateBookShared 一致)。
     *
     * callback 优先取 [UpdateBookCallbacks.getDefault] (iOS / 鸿蒙端经 [registerNativeUpdateBookCallback]
     * 注册的真实 [NativeUpdateBookCallback], 桥接 NotificationProgresses + Toasters);
     * 未注册时 fallback 到 null (UpdateBookShared 内部对 null callback 做容错)。
     */
    private val updateBookShared: UpdateBookShared by lazy {
        UpdateBookShared(scope, UpdateBookCallbacks.getDefault() ?: NativeUpdateBookCallback)
    }

    override fun startCacheBookService(bookUrl: String, start: Int, end: Int) {
        // 接入已下沉的 CacheBookShared: 创建模型 + 入队 + 启动处理 job
        // (对照 jvmMain DesktopServiceLauncher.startCacheBookService / app 端 CacheBookService.addDownloadData + download)
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
            // Native 端无 cachePool CoroutineDispatcher, 用 scope 默认 dispatcher
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
        // Native 端无 stopSelf; downloadJob 重启由调用方监听 isRun 自行管理
    }

    override fun startUpdateBookService() {
        // 接入已下沉的 UpdateBookShared (commonMain 编排核心):
        // 从 DB 查所有书后调 scheduleAutoUpdate 触发自动更新 (替代原 println stub)。
        // scheduleAutoUpdate 内部按 canUpdate + lastCheckTime 时间窗过滤, 自动跳过本地书 /
        // 不可更新书 / 最近 10 分钟已检查的书, 与 app 端 MainViewModel.scheduleAutoUpdate 行为一致。
        scope.launch {
            // 协程内直接 suspend 调用 (原 runBlocking 嵌套有死锁风险)
            val books = AppDbProviders.get().bookDao.all()
            updateBookShared.scheduleAutoUpdate(books)
        }
    }

    override fun stopUpdateBookService() {
        // 取消所有更新任务 (对照 app 端 MainViewModel.cancelRefreshJobs, 但无 stopService)
        updateBookShared.cancelRefreshJobs()
    }

    override fun startDownloadService(url: String, fileName: String) {
        // 真实下载: 用 FileDownloader 写文件到 filesDir/downloads/fileName
        // (对照桌面端 ~/.legado/downloads; Native 端用 AppFilesDirs.get().filesDir 即
        // iOS 端沙盒 Documents 目录 / 鸿蒙端 {user.dir}/legado_data/files 替代 user.home)
        scope.launch {
            val destPath = AppFilesDirs.get().filesDir + "/downloads"
            val ok = FileDownloaders.get().download(url, destPath, fileName)
            if (!ok) {
                AppLog.put("下载失败: url=$url fileName=$fileName", tag = "NativeServiceLauncher")
            }
        }
    }
}

/**
 * [UpdateBookCallback] 的 Native (iOS / 鸿蒙) 共用真实实现 (KP8+ 已真实化)。
 *
 * # 背景
 * 原为 stub (println), 仅打日志不触达用户。iOS 端已注册 [io.legado.app.help.notification.IosNotificationProgress]
 * (UNUserNotificationCenter) 与 [io.legado.app.help.toast.IosToaster] (UIAlertController);
 * 鸿蒙端 KP7+ 已注册 [io.legado.app.help.notification.OhosNotificationProgress] (notificationManager)
 * 与 [io.legado.app.help.toast.OhosToaster] (promptAction.showToast) 真实实现,
 * 经 napi 桥接 ArkTS 系统能力。
 *
 * 本 callback 把 UpdateBook 进度通知 / toast 反馈桥接到这两个已注册的真实 provider,
 * 替代原 stub。与 app 端 MainViewModel 桥接 NotificationManager + toastOnUi 一致,
 * 与桌面端阅读 VM 桥接 NotificationProgresses + Toasters 一致。
 *
 * # 复用而非复制
 * 不直接调平台 API (避免与 IosNotificationProgress / OhosNotificationProgress / IosToaster /
 * OhosToaster 重复实现), 而是经 commonMain provider 容器 [NotificationProgresses] / [Toasters]
 * 取已注册的真实实现。
 *
 * # 行为对照
 * - [onProgressUpdate]: active=true 调 [NotificationProgresses.get().showProgress] (title/content/count/total),
 *   active=false 调 [NotificationProgresses.get().cancel] (与接口契约"false=应取消"一致)
 * - [onProgressCancel]: 调 [NotificationProgresses.get().cancel]
 * - [toastForceRefreshBusy]: Toasters.get().toast("正在刷新中, 请稍后再试") (与桌面端文案一致)
 * - [toastForceRefreshStart]: Toasters.get().toast("开始强制刷新 $count 本") (与桌面端文案一致)
 * - [toastForceRefreshDone]: Toasters.get().toast("刷新完成") (与桌面端文案一致)
 *
 * # 注册时机
 * [registerNativeUpdateBookCallback] 在 [io.legado.app.help.config.registerIosProviders] /
 * [io.legado.app.help.config.registerOhosProviders] 中
 * `registerIosToaster`/`registerOhosToaster` + `registerIosNotificationProgress`/`registerOhosNotificationProgress`
 * 之后、`registerIosServiceLauncher`/`registerOhosServiceLauncher` 之前调用
 * (NativeServiceLauncher 构造 updateBookShared 时经 [UpdateBookCallbacks.getDefault] 取本实现)。
 *
 * 替代原 iosMain IosUpdateBookCallback + registerIosUpdateBookCallback (行为完全一致,
 * 已下沉到 nativeMain 共用, 避免平台 actual 重复)。
 *
 * 模式参考 desktop 端宿主持有 UpdateBookCallback 实现桥接 NotificationProgresses + Toasters。
 */
object NativeUpdateBookCallback : UpdateBookCallback {

    override fun onProgressUpdate(active: Boolean, title: String, content: String, count: Int, total: Int) {
        // active=false: 接口契约"应取消", 调 cancel (与 onProgressCancel 一致)
        if (!active) {
            NotificationProgresses.get().cancel()
            return
        }
        // active=true: 显示进度通知 (title + content 文案由 UpdateBookShared 拼好, count/total 为进度)
        NotificationProgresses.get().showProgress(title, content, count, total)
    }

    override fun onProgressCancel() {
        NotificationProgresses.get().cancel()
    }

    override fun toastForceRefreshBusy() {
        // 文案与桌面端宿主一致
        Toasters.get().toast("正在刷新中, 请稍后再试")
    }

    override fun toastForceRefreshStart(count: Int) {
        // 文案与桌面端宿主一致
        Toasters.get().toast("开始强制刷新 $count 本")
    }

    override fun toastForceRefreshDone() {
        // 文案与桌面端宿主一致
        Toasters.get().toast("刷新完成")
    }
}

/**
 * Native (iOS / 鸿蒙) 宿主启动早期注册真实 [UpdateBookCallback] (覆盖原 stub)。
 *
 * 调用时机: 在 `registerIosToaster`/`registerOhosToaster` + `registerIosNotificationProgress`/
 * `registerOhosNotificationProgress` 之后 (本 callback 委托这两个 provider)、
 * `registerIosServiceLauncher`/`registerOhosServiceLauncher` 之前
 * ([NativeServiceLauncher.updateBookShared] lazy 构造时经 [UpdateBookCallbacks.getDefault] 取本实现)。
 *
 * 替代原 iosMain IosUpdateBookCallback + registerIosUpdateBookCallback (行为完全一致,
 * 已下沉到 nativeMain 共用, 避免平台 actual 重复)。
 */
fun registerNativeUpdateBookCallback() {
    UpdateBookCallbacks.registerDefault(NativeUpdateBookCallback)
}

/**
 * iOS 宿主启动早期注册 [ServiceLauncher] 的 actual 实现 (委托 [NativeServiceLauncher])。
 *
 * 调用时机: iOS app 启动早期, 在任何 commonMain 代码调用 `ServiceLaunchers.get()` 之前。
 *
 * @param scope 协程作用域, 用于 launch 后台任务; 不传则创建独立的 SupervisorJob + Default
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` / jvmMain `registerDesktopServiceLauncher`。
 *
 * # 下沉说明
 * 原实现在 `iosMain/.../ServiceLauncher.ios.kt` 内 (IosServiceLauncher), 与鸿蒙端
 * OhosServiceLauncher 几乎逐字节相同 (仅 NSLog↔println 差异)。下沉到 nativeMain 后,
 * 本函数仅作为入口别名 (注册 [NativeServiceLauncher]), 保留 iOS 命名以兼容
 * [registerIosProviders] 调用方。
 */
fun registerIosServiceLauncher(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    ServiceLaunchers.register(NativeServiceLauncher(scope))
}

/**
 * 鸿蒙 (OHOS) 宿主启动早期注册 [ServiceLauncher] 的 actual 实现 (委托 [NativeServiceLauncher])。
 *
 * 调用时机: 鸿蒙 app 启动早期, 在任何 commonMain 代码调用 `ServiceLaunchers.get()` 之前。
 *
 * @param scope 协程作用域, 用于 launch 后台任务; 不传则创建独立的 SupervisorJob + Default
 *
 * 模式参考 `registerAndroidMediaNotificationProvider` / jvmMain `registerDesktopServiceLauncher`。
 *
 * # 下沉说明
 * 原实现在 `ohosMain/.../ServiceLauncher.ohos.kt` 内 (OhosServiceLauncher), 与 iOS 端
 * IosServiceLauncher 几乎逐字节相同 (仅 NSLog↔println 差异)。下沉到 nativeMain 后,
 * 本函数仅作为入口别名 (注册 [NativeServiceLauncher]), 保留鸿蒙命名以兼容
 * [registerOhosProviders] 调用方。
 */
fun registerOhosServiceLauncher(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    ServiceLaunchers.register(NativeServiceLauncher(scope))
}
