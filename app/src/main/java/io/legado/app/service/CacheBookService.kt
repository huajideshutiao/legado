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
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.setLiveProgress
import io.legado.app.model.CacheBook
import io.legado.app.model.CacheBookShared
import io.legado.app.notificationManager
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 缓存书籍服务
 */
class CacheBookService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private var downloadJob: Job? = null
    private var notificationContent = androidAppString("service_starting")
    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(androidAppString("offline_cache"))
            //.setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            androidAppString("cancel"),
            servicePendingIntent<CacheBookService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                notificationContent = CacheBook.downloadSummary
                upCacheBookNotification()
                postEvent(EventBus.UP_DOWNLOAD, "")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> addDownloadData(
                    intent.getStringExtra("bookUrl"),
                    intent.getIntExtra("start", 0),
                    intent.getIntExtra("end", 0)
                )

                IntentAction.remove -> removeDownload(intent.getStringExtra("bookUrl"))
                IntentAction.stop -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        CacheBook.close()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, "")
        upCacheBookFinishNotification()
    }

    private fun upCacheBookFinishNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(androidAppString("offline_cache"))
            .setContentText("缓存完成")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        bookUrl ?: return
        execute {
            // 目录保障、章节范围收敛、内存目录快照兜底都在共享入队编排内; 目录保障失败时
            // 共享层已把该书的下载任务移出, 这里不再单独移除
            if (!CacheBookShared.enqueueDownload(bookUrl, start, end)) return@execute
            notificationContent = CacheBook.downloadSummary
            upCacheBookNotification()
        }.onFinally {
            if (downloadJob == null) {
                download()
            }
        }
    }

    private fun removeDownload(bookUrl: String?) {
        CacheBook.cacheBookMap[bookUrl]?.stop()
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (downloadJob == null && CacheBook.isRun) {
            download()
            return
        }
        if (CacheBook.cacheBookMap.isEmpty()) {
            stopSelf()
        }
    }

    private fun download() {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            CacheBook.startProcessJob()
            stopSelf()
        }
    }

    private fun upCacheBookNotification() {
        val (done, total) = CacheBook.downloadProgress
        notificationBuilder.setContentText(notificationContent)
        notificationBuilder.setLiveProgress(
            done, total,
            shortText = if (total > 0) "$done/$total" else null
        )
        val notification = notificationBuilder.build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val (done, total) = CacheBook.downloadProgress
        notificationBuilder.setContentText(notificationContent)
        notificationBuilder.setLiveProgress(
            done, total,
            shortText = if (total > 0) "$done/$total" else null
        )
        val notification = notificationBuilder.build()
        startForeground(NotificationId.CacheBookService, notification)
    }

}