package io.legado.app.help.media

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import io.legado.app.R

/**
 * 朗读/音频通用前台播放通知。
 *
 * 调用方只需要提供差异化部分(标题、副标题、按钮、媒体会话 token);
 * 通用骨架(VISIBILITY_PUBLIC / 静音 / OnlyAlertOnce / 媒体样式等)在这里。
 */
object MediaPlaybackNotification {

    data class Action(
        @DrawableRes val icon: Int,
        val title: String,
        val intent: PendingIntent?
    )

    fun build(
        context: Context,
        channelId: String,
        title: String,
        subtitle: String,
        cover: Bitmap?,
        contentIntent: PendingIntent?,
        actions: List<Action>,
        compactActionIndices: IntArray,
        sessionToken: MediaSessionCompat.Token? = null,
        subText: String? = null,
        category: String? = null,
        foregroundBehavior: Int? = null,
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, channelId)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
        cover?.let { builder.setLargeIcon(it) }
        subText?.let { builder.setSubText(it) }
        category?.let { builder.setCategory(it) }
        foregroundBehavior?.let { builder.setForegroundServiceBehavior(it) }
        actions.forEach { action ->
            builder.addAction(action.icon, action.title, action.intent)
        }
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(
                    *if (compactActionIndices.size > 3) compactActionIndices.sliceArray(0..2)
                    else compactActionIndices
                )
                .setMediaSession(sessionToken)
        )
        return builder
    }
}
