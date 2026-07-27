package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.setLiveOngoing
import io.legado.app.help.setLiveProgress
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent

/**
 * Android 端 "更新书籍" 前台 Service 薄壳 (仅负责通知管理 + 生命周期)。
 *
 * # 业务编排下沉说明 (UpdateBookShared)
 *
 * 原本目录更新 / 强制刷新 / 自动更新 / 预下载调度等**业务编排逻辑**位于
 * `io.legado.app.ui.main.MainViewModel` (upToc / forceRefresh / scheduleAutoUpdate /
 * refreshBook / updateToc / addDownload / cacheBook / startUpTocJob / cancelRefreshJobs 等),
 * 与桌面端 `DesktopMainViewModel` 高度重复, 已下沉到 shared commonMain 的
 * [io.legado.app.help.service.UpdateBookShared], 由 app / desktop / iOS / 鸿蒙复用。
 *
 * 本 Service **不参与业务编排**, 仅保留:
 * - **Android 前台通知占位**: [startForegroundNotification] 显示 "更新目录" 占位通知
 *   (进度由 `MainViewModel.AndroidUpdateBookCallback.onProgressUpdate` 经
 *   `NotificationManagerCompat.notify` 实时刷写同一通知 ID, 与本 Service 解耦)
 * - **生命周期标志**: [isRun] (供 app 端其他组件判断 Service 是否在跑, 对照
 *   `CacheBookService.isRun` 模式)
 * - **停止 action 处理**: [onStartCommand] 收到 [IntentAction.stop] 时 postEvent
 *   [EventBus.STOP_UP_BOOK] (由 UpdateBookShared 的 FlowBus 监听器取消所有任务),
 *   再 stopSelf
 * - **通知清理**: [onDestroy] 取消通知栏进度
 *
 * # 调用关系
 *
 * - **启动**: `MainViewModel.AndroidUpdateBookCallback.onProgressUpdate` 内
 *   `context.startService<UpdateBookService>` (有任务在跑时显示通知)
 * - **停止**: `MainViewModel.AndroidUpdateBookCallback.onProgressCancel` 内
 *   `context.stopService<UpdateBookService>` (任务完成/取消时取消通知)
 * - **用户取消**: 通知栏 "取消" 按钮 → PendingIntent → [onStartCommand] (action=stop)
 *   → postEvent(STOP_UP_BOOK) → UpdateBookShared 监听器取消任务 → callback.onProgressCancel
 *   → stopService<UpdateBookService> → [onDestroy] 取消通知
 *
 * # updateNotification 方法
 *
 * [updateNotification] 保留用于兼容历史调用方, 但当前 app 端 MainViewModel 改造后
 * 已不调用本方法 (直接用 `NotificationManagerCompat.notify` 刷写同一通知 ID, 与
 * UpdateBookShared.updateProgress → callback.onProgressUpdate 流程对齐)。
 * 桌面端无 Service, 直接用 `NotificationProgresses.showProgress`。
 */
class UpdateBookService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_update)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.update_toc))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<UpdateBookService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 进度由 MainViewModel 持有并实时刷写同一通知 ID, 这里的前台占位通知
            // 先标记为实时进行中, 避免提升前后样式跳变。
            .setLiveOngoing()
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRun = false
        NotificationManagerCompat.from(this).cancel(NotificationId.UpdateBookService)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> {
                postEvent(EventBus.STOP_UP_BOOK, "")
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun startForegroundNotification() {
        startForeground(NotificationId.UpdateBookService, notificationBuilder.build())
    }

    @Suppress("unused")
    fun updateNotification(title: String, content: String, progress: Int, total: Int) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notificationBuilder.setContentTitle(title)
            notificationBuilder.setContentText(content)
            notificationBuilder.setLiveProgress(
                progress, total,
                shortText = if (total > 0) "$progress/$total" else null
            )
            try {
                NotificationManagerCompat.from(this)
                    .notify(NotificationId.UpdateBookService, notificationBuilder.build())
            } catch (e: Exception) {
                AppLog.put("更新通知失败\n${e.localizedMessage}", e)
            }
        }
    }
}
