package io.legado.app.help.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId

/**
 * [NotificationProgress] 的 Android actual 实现。
 *
 * 用 [NotificationCompat.Builder] + `setProgress` 显示进度通知, 行为对齐 app 端
 * `MainViewModel.updateUpdateNotification` (见 `app/src/main/java/io/legado/app/ui/main/MainViewModel.kt`)。
 *
 * # 设计要点
 * - shared androidMain 不依赖 splitties, 通过构造函数接收 [Context]
 * - 通知 channel 用 [AppConst.channelIdDownload] (commonMain 已下沉, 与 app 端一致)
 * - 通知 ID 用 [NotificationId.CacheBookService] (103, 与 app 端 CacheBookService 一致)
 * - 小图标用 `android.R.drawable.stat_sys_download` (系统资源, shared 无需访问 app 的 R)
 * - **不调 setLiveProgress**: app 端的 `setLiveProgress` 扩展在 app 模块, shared 不能引用;
 *   安卓 16 LiveUpdate 能力由 app 端 Service 自身负责 (app 端 `CacheBookService` 等仍直接
 *   构建通知并调 `setLiveProgress`); 本类仅作为 commonMain 跨平台入口的兜底实现
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class AndroidNotificationProgress(
    private val context: Context,
) : NotificationProgress {

    /** 通知 ID, 对齐 app 端 NotificationId.CacheBookService。 */
    private val notificationId: Int = NotificationId.CacheBookService

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    override fun showProgress(title: String, content: String, progress: Int, max: Int) {
        // 通知权限检查 (与 app 端 MainViewModel.updateUpdateNotification 一致)
        if (!notificationManager.areNotificationsEnabled()) return

        val determinate = max > 0 && progress >= 0
        val safeProgress = if (determinate) progress.coerceIn(0, max) else 0

        val builder = NotificationCompat.Builder(context, AppConst.channelIdDownload)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(if (determinate) max else 0, safeProgress, !determinate)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        kotlin.runCatching {
            notificationManager.notify(notificationId, builder.build())
        }
    }

    override fun cancel() {
        notificationManager.cancel(notificationId)
    }
}

/**
 * 安卓宿主启动早期注册 [NotificationProgress] 的 actual 实现。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `NotificationProgresses.get()` 之前。
 *
 * @param context 任意 Context (推荐传 `appCtx`), 用于 NotificationManagerCompat
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerAndroidNotificationProgress(context: Context) {
    NotificationProgresses.register(AndroidNotificationProgress(context.applicationContext))
}
