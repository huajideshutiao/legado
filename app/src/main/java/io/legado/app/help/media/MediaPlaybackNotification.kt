@file:Suppress("DEPRECATION") // MediaSessionCompat / MediaStyle 整体弃用, 同 MediaHelp

package io.legado.app.help.media

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst

/**
 * 朗读/音频通用前台播放通知。
 *
 * 调用方只需要提供差异化部分(标题、副标题、按钮、点击去向、会话 token);
 * 通用骨架(VISIBILITY_PUBLIC / 静音 / OnlyAlertOnce / 媒体样式)在这里,
 * 两条通知因此不可能再出现参数不一致。
 */
object MediaPlaybackNotification {

    data class Action(
        @param:DrawableRes val icon: Int,
        val title: String,
        val intent: PendingIntent?
    )

    /**
     * 一次前台媒体通知的完整描述 (除标题/按钮这类每条通知各自的内容外, 其余全部定死在此)。
     *
     * 通知 id 不在这里: 它是宿主 [MediaNotificationHost] 的构造参数, 与内容无关。
     *
     * @param title          第一行 (播放状态 + 书名)
     * @param subtitle       第二行 (章节名, 无章节时的兜底文案)
     * @param subText        系统媒体卡片上的来源标注 ("朗读" / "音频")
     * @param cover          大图 (封面位图)
     * @param contentIntent  点击通知的去向
     * @param sessionToken   系统媒体会话令牌 (恒挂, 锁屏/车机媒体控件据此与通知联动)
     * @param actions        按钮 (顺序即显示顺序)
     */
    data class Spec(
        val title: String,
        val subtitle: String,
        val subText: String?,
        val cover: Bitmap?,
        val contentIntent: PendingIntent?,
        val sessionToken: MediaSessionCompat.Token?,
        val actions: List<Action>,
    )

    /** 紧凑态 (通知栏收起时) 展示的按钮位置: 上一 / 播放暂停 / 下一。 */
    private val CompactActionIndices = intArrayOf(1, 2, 3)

    fun build(context: Context, spec: Spec): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // when 钉死为 0: NotificationCompat.Builder 构造函数会写 when = currentTimeMillis,
            // 而 NotificationRecord.calculateRankingTimeMs() 优先取 app 提供的 when
            // (hasAppProvidedWhen() = when != 0 && when != creationTime), 于是每次重建通知都拿到新
            // 时间戳 → 通知被重排到通知栏顶部、展开态丢失。归零后落到"继承上一条 ranking time"分支,
            // 更新时排序稳定 (media3 DefaultMediaNotificationProvider 同样 setWhen(0L)+setShowWhen(false))。
            .setWhen(0L)
            .setShowWhen(false)
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
            .setContentTitle(spec.title)
            .setContentText(spec.subtitle)
            .setContentIntent(spec.contentIntent)
        // 归类与"立即出现在通知栏"不在此声明: 分类由 MediaStyle 在 build 时补 TRANSPORT,
        // 前台服务通知的显示延迟对"媒体通知或带动作的通知"本就不生效。
        spec.cover?.let { builder.setLargeIcon(it) }
        spec.subText?.let { builder.setSubText(it) }
        spec.actions.forEach { action ->
            builder.addAction(action.icon, action.title, action.intent)
        }
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(*CompactActionIndices)
                .setMediaSession(spec.sessionToken)
        )
        return builder
    }
}
