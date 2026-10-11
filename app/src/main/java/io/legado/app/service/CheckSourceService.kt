package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.IntentData
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.setLiveProgress
import io.legado.app.model.CheckSourceRunner
import io.legado.app.model.Debug
import io.legado.app.notificationManager
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 校验书源
 *
 * 业务流程 (checkSource/doCheckSource/checkBook) 已下沉到
 * [io.legado.app.model.CheckSourceShared] (shared commonMain), 并发编排
 * (逐 id 取源 → 限流并发 → 进度回调 → 完成回调) 与执行用的固定线程池口径已下沉到
 * [io.legado.app.model.CheckSourceRunner]。
 * 本 Service 仅保留:
 * - Android 专属 Notification 构造与刷新
 * - lifecycleScope.launch 入口
 */
class CheckSourceService : BaseService() {
    private var notificationMsg = androidAppString("service_starting")
    private var checkJob: Job? = null
    private var originSize = 0
    private var finishCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(androidAppString("check_book_source"))
            .setContentIntent(
                // 独立 action (IntentAction.activityCheckSource) 区分 PendingIntent 身份
                // (见 BaseReadAloudService 通知注释)
                activityPendingIntent<MainActivity>(IntentAction.activityCheckSource) {
                    putExtra("route", "book_source_manage")
                }
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                androidAppString("cancel"),
                servicePendingIntent<CheckSourceService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkSourceSelectedIds")?.let {
                check(it)
            }

            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckSourceService)
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        checkJob = lifecycleScope.launch {
            CheckSourceRunner.run(
                ids = ids,
                onStart = { total -> reportProgress("", 0, total) },
                onProgress = { name, finish, total -> reportProgress(name, finish, total) },
                onComplete = {
                    stopSelf()
                }
            )
        }
    }

    /**
     * 记下这一帧进度并刷新通知。
     *
     * 首帧 (onStart) 传空书源名: 格式串内 %1$s 位置留白, trim 后与后续带名字的帧一样左对齐
     * (对照原版 check 的 onStart 分支)。
     */
    private fun reportProgress(name: String, finish: Int, total: Int) {
        finishCount = finish
        originSize = total
        notificationMsg = androidAppString("progress_show", name, finish, total).trim()
        upNotification()
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setLiveProgress(
            finishCount, originSize,
            shortText = if (originSize > 0) "$finishCount/$originSize" else null
        )
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setLiveProgress(
            finishCount, originSize,
            shortText = if (originSize > 0) "$finishCount/$originSize" else null
        )
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }

}
