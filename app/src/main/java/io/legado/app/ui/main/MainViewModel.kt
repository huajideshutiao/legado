package io.legado.app.ui.main

import android.app.Application
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.DefaultData
import io.legado.app.help.NotificationHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.service.UpdateBookCallback
import io.legado.app.help.service.UpdateBookShared
import io.legado.app.help.setLiveProgress
import io.legado.app.service.UpdateBookService
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx

/**
 * app 端主 ViewModel (书架数据层刷新引擎)。
 *
 * # 业务编排下沉说明 (UpdateBookShared)
 *
 * 原本目录更新 / 强制刷新 / 自动更新 / 预下载调度等编排逻辑全部在本类内实现
 * (upToc / forceRefresh / scheduleAutoUpdate / refreshBook / updateToc / addDownload /
 * cacheBook / startUpTocJob / onUpTocJobCompleted / addToWaitUp / pollWaitUpTocBook /
 * upPool 等), 与桌面端 `DesktopMainViewModel` 高度重复。本类已把这些**非平台特有**
 * 的编排逻辑下沉到 shared commonMain 的 [UpdateBookShared], 本类仅保留:
 * - **平台特有方法**: [observeGroupBooks] (依赖 Android Lifecycle) / [postLoad]
 *   (依赖 assets DefaultData) / [restoreWebDav] (依赖 app 端 AppWebDav)
 * - **平台特有状态**: [isActivityVisible] (callback 内判断是否显示通知) /
 *   [booksListRecycledViewPool] / [booksGridRecycledViewPool] (RecyclerView 复用池)
 * - **Android 通知实现**: [AndroidUpdateBookCallback] inner class, 桥接
 *   [UpdateBookCallback] 到 `NotificationManagerCompat` + `startService<UpdateBookService>`
 * - **转发方法**: [upToc] / [forceRefresh] / [scheduleAutoUpdate] / [cancelRefreshJobs] /
 *   [isUpdate] / [markGroupAutoUpdated] / [onCleared] 直接转发到 [updateBookShared]
 *
 * 与桌面端 `DesktopMainViewModel` 改造对齐 (桌面端 callback 用 NotificationProgresses +
 * Toasters, 本类 callback 用 NotificationManagerCompat + context.toastOnUi)。
 *
 * # UpdateBookService.kt 关系
 *
 * `io.legado.app.service.UpdateBookService` 是 Android Service 薄壳, 仅负责通知管理
 * (onCreate/onDestroy/startForegroundNotification/updateNotification), 无业务编排逻辑。
 * 本类通过 [AndroidUpdateBookCallback.onProgressUpdate] 内 `startService<UpdateBookService>`
 * 启动 Service 显示通知, 与原 `updateUpdateNotification` 行为一致; Service 本身不参与编排,
 * 业务编排全部在 [updateBookShared] 内 (与原 MainViewModel 一致)。
 */
class MainViewModel(application: Application) : BaseViewModel(application) {

    /**
     * UpdateBook 编排核心 (shared commonMain 下沉件), 持有实例并转发公共方法。
     *
     * scope 用 viewModelScope (随 Activity 销毁取消任务, 与原 MainViewModel 一致);
     * callback 用 [AndroidUpdateBookCallback] (桥接 Android 通知 + toast)。
     */
    private val updateBookShared: UpdateBookShared by lazy {
        UpdateBookShared(viewModelScope, AndroidUpdateBookCallback())
    }

    var isActivityVisible = true

    val booksListRecycledViewPool = RecycledViewPool().apply {
        setMaxRecycledViews(0, 30)
    }
    val booksGridRecycledViewPool = RecycledViewPool().apply {
        setMaxRecycledViews(0, 100)
    }

    /**
     * 本次应用进程内已触发过自动更新的分组ID, 避免 fragment 回收重建后再次触发。
     *
     * 注: [UpdateBookShared] 内部也持有同名状态 (供 Native 端使用), app 端保留独立状态
     * 与原行为一致 ([markGroupAutoUpdated] 转发到 [updateBookShared] 后, 本地集合仅作
     * UI 层快速判断; 实际去重由 [updateBookShared] 内部保证)。
     */
    private val autoUpdatedGroups = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun markGroupAutoUpdated(groupId: Long): Boolean {
        // 双写: 本地集合 (UI 层快速判断) + UpdateBookShared (Native 端共用)
        autoUpdatedGroups.add(groupId)
        return updateBookShared.markGroupAutoUpdated(groupId)
    }

    /**
     * 取消刷新/更新目录任务, 用于退出应用时清理, 避免弹出通知
     */
    fun cancelRefreshJobs() {
        updateBookShared.cancelRefreshJobs()
    }

    /**
     * Activity 可见性变化时刷新通知状态 (对照原 MainViewModel.updateUpdateNotification)。
     * Activity 可见时取消通知, 不可见时按当前任务状态显示通知。
     */
    fun updateUpdateNotification() {
        updateBookShared.refreshProgress()
    }

    /**
     * threadCount 改变时重建线程池 (对照原 MainViewModel.upPool)。
     */
    fun upPool() {
        updateBookShared.upPool()
    }

    fun isUpdate(bookUrl: String): Boolean {
        return updateBookShared.isUpdate(bookUrl)
    }

    /**
     * 主动更新目录, 不做时间窗判断 (用于下拉刷新 / 菜单项)
     */
    fun upToc(books: List<Book>) {
        updateBookShared.upToc(books)
    }

    /**
     * 强制刷新书籍信息, 无视 canUpdate 属性 (用于菜单项)
     */
    fun forceRefresh(books: List<Book>) {
        updateBookShared.forceRefresh(books)
    }

    /**
     * 自动更新目录, 跳过最近已检查过的书籍
     */
    fun scheduleAutoUpdate(books: List<Book>) {
        updateBookShared.scheduleAutoUpdate(books)
    }

    /**
     * 观察一个分组的书籍列表, 并在首次发射时触发一次自动更新.
     *
     * 排序逻辑由调用方通过 [sorter] 提供 (style1 用本地 bookSort, style2 用 AppConfig 按 groupId 取).
     * 生命周期感知由 [lifecycle] 接入: 只在 RESUMED 时下发, 与首次自动更新触发时机绑定.
     * 上游在 Default 上执行, 调用方在 collect 块里只做 UI 更新.
     */
    fun observeGroupBooks(
        groupId: Long,
        lifecycle: Lifecycle,
        sorter: (List<Book>) -> List<Book>,
    ): Flow<List<Book>> = runBlocking {
        appDb.bookDao.flowByGroup(groupId)
            .map { sorter(it) }
            .flowWithLifecycleAndDatabaseChangeFirst(
                lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            )
            .catch { AppLog.put("书架更新出错", it) }
            .onEach { list ->
                if (markGroupAutoUpdated(groupId) && AppConfig.autoRefreshBook) {
                    scheduleAutoUpdate(list)
                }
            }
            .conflate()
            .flowOn(Dispatchers.Default)
    }

    override fun onCleared() {
        super.onCleared()
        // UpdateBookShared.onCleared 内部调 cancelRefreshJobs (含 stopService<UpdateBookService>
        // 经 callback.onProgressCancel) + close upTocPool, 与原 MainViewModel.onCleared 行为对齐
        updateBookShared.onCleared()
    }

    fun postLoad() {
        execute {
            if (appDb.httpTTSDao.count() == 0) {
                DefaultData.httpTTS.let {
                    appDb.httpTTSDao.insert(*it.toTypedArray())
                }
            }
        }
    }

    fun restoreWebDav(name: String) {
        execute {
            AppWebDav.restoreWebDav(name)
        }
    }

    /**
     * [UpdateBookCallback] 的 Android actual 实现 (inner class, 持有 MainViewModel this)。
     *
     * 桥接 [UpdateBookShared] 的进度回调到 app 端 Android 通知栏:
     * - [onProgressUpdate]: `startService<UpdateBookService>` + `NotificationManagerCompat.notify`
     *   (NotificationCompat.Builder 设置进度, 与原 `updateUpdateNotification` 一致)
     * - [onProgressCancel]: `stopService<UpdateBookService>` (取消通知)
     * - [toastForceRefreshBusy]: `context.toastOnUi(R.string.force_refresh_busy)`
     * - [toastForceRefreshStart] / [toastForceRefreshDone]: app 端原 MainViewModel 无此 toast
     *   (仅桌面端有), no-op
     *
     * # isActivityVisible 处理
     * 原 `updateUpdateNotification` 在 `isActivityVisible=true` 时直接 `stopService` 不显示通知
     * (避免 Activity 可见时打扰用户)。本 callback 在 [onProgressUpdate] 内自行检查
     * [isActivityVisible], 行为对齐 (UpdateBookShared 不感知 isActivityVisible, 由 callback 处理)。
     */
    private inner class AndroidUpdateBookCallback : UpdateBookCallback {

        override fun onProgressUpdate(active: Boolean, title: String, content: String, count: Int, total: Int) {
            // Activity 可见时不显示通知 (与原 updateUpdateNotification 一致)
            if (isActivityVisible) {
                context.stopService<UpdateBookService>()
                return
            }
            context.startService<UpdateBookService>()
            if (NotificationManagerCompat.from(appCtx).areNotificationsEnabled()) {
                // title/content 已由 UpdateBookShared 计算 ("更新目录" / "强制刷新" + "count/total"),
                // 这里直接用, 与原 updateUpdateNotification 内 R.string.update_toc / R.string.force_refresh_book 等价
                // (原 title 用 R.string.update_toc = "更新目录", R.string.force_refresh_book = "强制刷新",
                //  UpdateBookShared 内硬编码 "更新目录" / "强制刷新" 与之对齐)
                val notificationBuilder =
                    NotificationCompat.Builder(context, AppConst.channelIdDownload)
                        .setSmallIcon(R.drawable.ic_update)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setLiveProgress(
                            count,
                            total,
                            shortText = if (total > 0) "$count/$total" else null
                        )
                        .addAction(
                            R.drawable.ic_stop_black_24dp,
                            context.getString(R.string.cancel),
                            context.servicePendingIntent<UpdateBookService>(IntentAction.stop)
                        )
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                try {
                    val notification = notificationBuilder.build()
                    NotificationHelp.logPromotable(notification)
                    NotificationManagerCompat.from(appCtx)
                        .notify(NotificationId.UpdateBookService, notification)
                } catch (e: Exception) {
                    AppLog.put("更新通知失败\n${e.localizedMessage}", e)
                }
            }
        }

        override fun onProgressCancel() {
            context.stopService<UpdateBookService>()
        }

        override fun toastForceRefreshBusy() {
            context.toastOnUi(R.string.force_refresh_busy)
        }

        override fun toastForceRefreshStart(count: Int) {
            // app 端原 MainViewModel 无此 toast (仅桌面端有), no-op
        }

        override fun toastForceRefreshDone() {
            // app 端原 MainViewModel 无此 toast (仅桌面端有), no-op
        }
    }

}
