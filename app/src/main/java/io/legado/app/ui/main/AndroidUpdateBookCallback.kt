package io.legado.app.ui.main

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.NotificationHelp
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.service.UpdateBookCallback
import io.legado.app.help.setLiveProgress
import io.legado.app.service.UpdateBookService
import io.legado.app.ui.main.AndroidUpdateBookCallback.onProgressCancel
import io.legado.app.ui.main.AndroidUpdateBookCallback.onProgressUpdate
import io.legado.app.ui.main.AndroidUpdateBookCallback.toastForceRefreshBusy
import io.legado.app.ui.main.AndroidUpdateBookCallback.toastForceRefreshDone
import io.legado.app.ui.main.AndroidUpdateBookCallback.toastForceRefreshStart
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * [UpdateBookCallback] 的 Android 实现 (单例, 由 [io.legado.app.App] 注册为默认实现)。
 *
 * 供 shared [io.legado.app.ui.bookshelf.BookshelfViewModel] 构造 UpdateBookShared 刷新引擎时
 * 经 [io.legado.app.help.service.UpdateBookCallbacks.getDefault] 取用 (iOS/鸿蒙端对应
 * [io.legado.app.help.service.NativeUpdateBookCallback], 桌面端对应
 * [io.legado.app.help.service.DesktopUpdateBookCallback])。
 *
 * 桥接进度到 Android 通知栏 (逻辑与下沉前 MainViewModel.updateUpdateNotification 一致):
 * - [onProgressUpdate]: `startService<UpdateBookService>` + `NotificationManagerCompat.notify`
 *   (NotificationCompat.Builder 设置进度)
 * - [onProgressCancel]: `stopService<UpdateBookService>` (取消通知)
 * - [toastForceRefreshBusy]: `toastOnUi(androidAppString("force_refresh_busy"))`
 * - [toastForceRefreshStart] / [toastForceRefreshDone]: app 端原 MainViewModel 无此 toast
 *   (仅桌面端有), no-op
 *
 * # isActivityVisible 处理
 * 原 `updateUpdateNotification` 在 `isActivityVisible=true` 时直接 `stopService` 不显示通知
 * (避免 Activity 可见时打扰用户)。MainActivity 经 [MainViewModel.isActivityVisible] 维护本标志
 * (MainViewModel 与书架 VM 各自的引擎共用本单例, 通知行为一致)。
 */
object AndroidUpdateBookCallback : UpdateBookCallback {

    /** Activity 是否可见: 可见时不显示进度通知 (原 MainViewModel.isActivityVisible 语义) */
    @Volatile
    var isActivityVisible = true

    override fun onProgressUpdate(
        active: Boolean,
        title: String,
        content: String,
        count: Int,
        total: Int
    ) {
        // Activity 可见时不显示通知 (与原 updateUpdateNotification 一致)
        if (isActivityVisible) {
            appCtx.stopService<UpdateBookService>()
            return
        }
        appCtx.startService<UpdateBookService>()
        if (NotificationManagerCompat.from(appCtx).areNotificationsEnabled()) {
            // title/content 已由 UpdateBookShared 计算 (appString(AppStringKey.update_toc /
            // force_refresh_book) → "update_toc" / "force_refresh_book"
            // 多语言文案 + "count/total"), 这里直接用
            val notificationBuilder =
                NotificationCompat.Builder(appCtx, AppConst.channelIdDownload)
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
                        androidAppString("cancel"),
                        appCtx.servicePendingIntent<UpdateBookService>(IntentAction.stop)
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
        appCtx.stopService<UpdateBookService>()
    }

    override fun toastForceRefreshBusy() {
        appCtx.toastOnUi(androidAppString("force_refresh_busy"))
    }

    override fun toastForceRefreshStart(count: Int) {
        // app 端原 MainViewModel 无此 toast (仅桌面端有), no-op
    }

    override fun toastForceRefreshDone() {
        // app 端原 MainViewModel 无此 toast (仅桌面端有), no-op
    }
}
