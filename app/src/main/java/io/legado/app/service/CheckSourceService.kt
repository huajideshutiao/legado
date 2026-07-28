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
import io.legado.app.data.appDb
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.setLiveProgress
import io.legado.app.model.CheckSourceShared
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 校验书源
 *
 * 业务流程 (checkSource/doCheckSource/checkBook) 已下沉到
 * [io.legado.app.model.CheckSourceShared] (shared commonMain)。
 * 本 Service 仅保留:
 * - Android 专属 Notification 构造与刷新
 * - lifecycleScope.launch + 自定义线程池调度入口
 * - 业务流程委托 [CheckSourceShared.checkSource]
 */
class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.threadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var originSize = 0
    private var finishCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(io.legado.shared.R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceActivity>("activity")
            )
            .addAction(
                io.legado.shared.R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
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
        searchCoroutine.close()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckSourceService)
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        checkJob = lifecycleScope.launch(searchCoroutine) {
            flow {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let {
                        emit(it)
                    }
                }
            }.onStart {
                originSize = ids.size
                finishCount = 0
                // 初始帧无书源名, 传 "" 会残留前导空格, trim 掉使其与后续带名字的帧一样左对齐
                notificationMsg = getString(R.string.progress_show, "", 0, originSize).trim()
                upNotification()
            }.onEachParallel(threadCount) {
                CheckSourceShared.checkSource(it)
            }.onEach {
                finishCount++
                notificationMsg = getString(
                    R.string.progress_show,
                    it.bookSourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                appDb.bookSourceDao.update(it)
            }.onCompletion {
                stopSelf()
            }.collect()
        }
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
