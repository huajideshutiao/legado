package io.legado.app.ui.main

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.DefaultData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.service.UpdateBookShared

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
 * - **平台特有方法**: [postLoad] (依赖 assets DefaultData) / [restoreWebDav] (依赖 app 端 AppWebDav)
 * - **平台特有状态**: [isActivityVisible] (callback 内判断是否显示通知)
 * - **Android 通知实现**: [AndroidUpdateBookCallback] object (由 [io.legado.app.App] 注册为
 *   [io.legado.app.help.service.UpdateBookCallbacks] 默认实现, 书架 VM 引擎共用), 桥接
 *   [io.legado.app.help.service.UpdateBookCallback] 到 `NotificationManagerCompat` +
 *   `startService<UpdateBookService>`
 * - **转发方法**: [upToc] / [forceRefresh] / [scheduleAutoUpdate] / [cancelRefreshJobs] /
 *   [isUpdate] / [markGroupAutoUpdated] / [onCleared] 直接转发到 [updateBookShared]
 *
 * 与桌面端 `DesktopMainViewModel` 改造对齐 (桌面端 callback 用 NotificationProgresses +
 * Toasters, 本类 callback 用 NotificationManagerCompat + appCtx.toastOnUi)。
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
     * callback 用 [AndroidUpdateBookCallback] (单例, 桥接 Android 通知 + toast)。
     */
    private val updateBookShared: UpdateBookShared by lazy {
        UpdateBookShared(viewModelScope, AndroidUpdateBookCallback)
    }

    /**
     * Activity 可见性 (通知显隐依据); 委托 [AndroidUpdateBookCallback.isActivityVisible]
     * (与书架 VM 引擎共用的默认 callback 同一标志, 保证两端引擎通知行为一致)
     */
    var isActivityVisible: Boolean
        get() = AndroidUpdateBookCallback.isActivityVisible
        set(value) {
            AndroidUpdateBookCallback.isActivityVisible = value
        }

    fun markGroupAutoUpdated(groupId: Long): Boolean {
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

}
