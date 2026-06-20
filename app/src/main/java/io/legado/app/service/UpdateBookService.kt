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
