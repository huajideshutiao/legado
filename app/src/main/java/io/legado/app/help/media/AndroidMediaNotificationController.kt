package io.legado.app.help.media

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import io.legado.app.constant.AppLog

/**
 * [MediaNotificationController] 的 Android actual 实现。
 *
 * 内部用 [MediaSessionCompat] 维护媒体会话, 用 [MediaPlaybackNotification] 构建通知
 * (与现有 BaseReadAloudService / AudioPlayService 视觉一致), 用 [NotificationManager]
 * 推送通知。前台服务场景下, 调用方注入 [foregroundService] 后, [startForeground] 会
 * 走 `Service.startForeground`; 未注入时退化为 `NotificationManager.notify`。
 *
 * 设计原则:
 * 1. 不修改 app/service/ 下现有 Service 代码。本类是新增的"通用"控制器, 现有 Service
 *    保持原状; 未来跨平台调用方可以通过 [MediaNotificationProviders] 拿到本类实例。
 * 2. 所有平台特定细节(PendingIntent / Bitmap / Drawable 资源 ID)通过外部注入的工厂
 *    获得, 本类不直接依赖 R.drawable.* / 具体Activity / BookCover, 保持通用性。
 * 3. 通知快照短路: 与现有 Service 一致, 元数据 + 状态 + 封面未变化时跳过 rebuild,
 *    避免高频刷新通知。
 *
 * @param context    上下文(用于 NotificationManager / MediaSessionCompat)
 * @param sessionTag MediaSession 标签, 如 "readAloud" / "AudioPlayService"
 */
class AndroidMediaNotificationController(
    private val context: Context,
    private val sessionTag: String,
) : MediaNotificationController {

    // ===================== 外部注入点 =====================

    /** 根据 [PlaybackState.Action] 构造通知按钮的 PendingIntent(触发 Service 命令)。 */
    var actionIntentFactory: ((PlaybackState.Action) -> PendingIntent?)? = null

    /** 通知内容点击 PendingIntent。 */
    var contentIntent: PendingIntent? = null

    /** 封面 URL -> Bitmap 加载器(避免本类直接依赖 BookCover)。 */
    var artworkLoader: ((String?) -> Bitmap?)? = null

    /** 调用方提供 Service 引用时, [startForeground] 走 Service.startForeground; 否则走 NotificationManager.notify。 */
    var foregroundService: Service? = null

    /** 图标资源 ID 解析器: 根据 Action 返回 R.drawable.* 。 */
    var iconResolver: ((PlaybackState.Action) -> Int)? = null

    /** 媒体按钮接收器 PendingIntent(用于 MediaSession.setMediaButtonReceiver)。 */
    var mediaButtonReceiverIntent: PendingIntent? = null

    // ===================== 内部状态 =====================

    private var mediaSession: MediaSessionCompat? = null
    private var notificationId: Int = 0
    private var channelId: String = ""
    private var currentMetadata: MediaMetadata? = null
    private var currentState: PlaybackState? = null
    private var currentActions: List<MediaAction> = emptyList()

    /** 通知快照, 用于跳过无变化的 rebuild(对齐现有 Service 行为)。 */
    private data class Snapshot(
        val title: String?,
        val subtitle: String?,
        val subText: String?,
        val status: PlaybackState.Status,
        val artworkUrl: String?,
    )

    private var lastSnapshot: Snapshot? = null

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** 当前已加载的封面 Bitmap(由 [artworkLoader] 加载, 用于通知与媒体会话)。 */
    @Volatile
    private var artworkBitmap: Bitmap? = null

    /** 上一次加载封面的 URL, 用于判断是否需要重新加载。 */
    private var lastArtworkUrl: String? = null

    // ===================== 接口实现 =====================

    override fun startForeground(
        notificationId: Int,
        channelId: String,
        metadata: MediaMetadata,
        state: PlaybackState,
        actions: List<MediaAction>,
    ) {
        this.notificationId = notificationId
        this.channelId = channelId
        ensureMediaSession()
        applyMetadataToSession(metadata)
        applyStateToSession(state)
        currentMetadata = metadata
        currentState = state
        currentActions = actions
        refreshArtwork(metadata.artworkUrl)
        val notification = buildNotification()
        val service = foregroundService
        if (service != null) {
            // 走前台服务: 系统会绑定通知, 服务存活期间通知不可滑除
            service.startForeground(notificationId, notification)
        } else {
            notificationManager.notify(notificationId, notification)
        }
    }

    override fun updateNotification(
        metadata: MediaMetadata,
        state: PlaybackState,
        actions: List<MediaAction>,
    ) {
        applyMetadataToSession(metadata)
        applyStateToSession(state)
        currentMetadata = metadata
        currentState = state
        currentActions = actions
        refreshArtwork(metadata.artworkUrl)
        val snapshot = snapshotOf(metadata, state)
        if (snapshot == lastSnapshot && artworkBitmap != null) return
        lastSnapshot = snapshot
        try {
            notificationManager.notify(notificationId, buildNotification())
        } catch (e: Exception) {
            // 跟随现有 Service 行为: 通知构建失败时记录, 不崩溃
            AppLog.put("刷新媒体通知出错, ${e.localizedMessage}", e)
        }
    }

    override fun updateMetadata(metadata: MediaMetadata) {
        applyMetadataToSession(metadata)
        currentMetadata = metadata
        refreshArtwork(metadata.artworkUrl)
        val state = currentState ?: return
        val snapshot = snapshotOf(metadata, state)
        if (snapshot == lastSnapshot && artworkBitmap != null) return
        lastSnapshot = snapshot
        try {
            notificationManager.notify(notificationId, buildNotification())
        } catch (e: Exception) {
            AppLog.put("刷新媒体通知元数据出错, ${e.localizedMessage}", e)
        }
    }

    override fun updatePlaybackState(state: PlaybackState) {
        applyStateToSession(state)
        currentState = state
        val metadata = currentMetadata ?: return
        val snapshot = snapshotOf(metadata, state)
        if (snapshot == lastSnapshot && artworkBitmap != null) return
        lastSnapshot = snapshot
        try {
            notificationManager.notify(notificationId, buildNotification())
        } catch (e: Exception) {
            AppLog.put("刷新媒体通知状态出错, ${e.localizedMessage}", e)
        }
    }

    override fun setSessionActive(active: Boolean) {
        mediaSession?.isActive = active
    }

    override fun cancelNotification() {
        if (notificationId != 0) {
            notificationManager.cancel(notificationId)
        }
    }

    override fun release() {
        // 跟随现有 Service 行为: 释放前推一个 STOPPED 状态
        try {
            applyStateToSession(PlaybackState(PlaybackState.Status.Stopped))
        } catch (_: Exception) {
            // 释放阶段不再报错
        }
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        if (notificationId != 0) {
            notificationManager.cancel(notificationId)
        }
        currentMetadata = null
        currentState = null
        currentActions = emptyList()
        lastSnapshot = null
        artworkBitmap = null
        lastArtworkUrl = null
    }

    // ===================== 内部辅助 =====================

    private fun ensureMediaSession() {
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(context, sessionTag).apply {
                mediaButtonReceiverIntent?.let { setMediaButtonReceiver(it) }
                isActive = true
            }
        }
    }

    /** 把跨平台 [MediaMetadata] 翻译成 [MediaMetadataCompat] 并推到媒体会话。 */
    private fun applyMetadataToSession(metadata: MediaMetadata) {
        val session = mediaSession ?: return
        val builder = MediaMetadataCompat.Builder()
        metadata.title?.let { builder.putText(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
        // 注意: MediaMetadataCompat 没有 SUBTITLE key, subtitle 仅写入通知, 不进媒体会话
        metadata.artist?.let { builder.putText(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
        metadata.album?.let { builder.putText(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
        builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.durationMs)
        artworkBitmap?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        session.setMetadata(builder.build())
    }

    /** 把跨平台 [PlaybackState] 翻译成 [PlaybackStateCompat] 并推到媒体会话。 */
    private fun applyStateToSession(state: PlaybackState) {
        val session = mediaSession ?: return
        val builder = PlaybackStateCompat.Builder()
            .setActions(state.actions.toCompatActions())
            .setState(state.status.toCompatState(), state.positionMs, state.speed)
            .setBufferedPosition(state.bufferedPositionMs)
        session.setPlaybackState(builder.build())
    }

    /** URL 变化时通过注入的 [artworkLoader] 加载封面, 否则保持现状。 */
    private fun refreshArtwork(url: String?) {
        if (url == lastArtworkUrl) return
        lastArtworkUrl = url
        artworkBitmap = artworkLoader?.invoke(url)
    }

    private fun snapshotOf(metadata: MediaMetadata, state: PlaybackState): Snapshot =
        Snapshot(
            title = metadata.title,
            subtitle = metadata.subtitle,
            subText = metadata.subText,
            status = state.status,
            artworkUrl = metadata.artworkUrl,
        )

    /** 构建当前通知(委托给现有 [MediaPlaybackNotification.build] 保持视觉一致)。 */
    private fun buildNotification(): android.app.Notification {
        val metadata = currentMetadata ?: error("metadata not set before buildNotification")
        val state = currentState ?: error("state not set before buildNotification")
        val actions = currentActions.map { action ->
            MediaPlaybackNotification.Action(
                icon = iconResolver?.invoke(action.action) ?: 0,
                title = action.title,
                intent = actionIntentFactory?.invoke(action.action),
            )
        }
        // 紧凑视图最多展示 3 个按钮(对齐 MediaStyle 限制)
        val compactIndices = actions.indices.take(3).toIntArray()
        return MediaPlaybackNotification.build(
            context = context,
            channelId = channelId,
            title = metadata.title ?: "",
            subtitle = metadata.subtitle ?: "",
            cover = artworkBitmap,
            contentIntent = contentIntent,
            actions = actions,
            compactActionIndices = compactIndices,
            sessionToken = mediaSession?.sessionToken,
            subText = metadata.subText,
            category = NotificationCompat.CATEGORY_TRANSPORT,
            foregroundBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
        ).build()
    }

    /** [PlaybackState.Status] -> PlaybackStateCompat 状态码。 */
    private fun PlaybackState.Status.toCompatState(): Int = when (this) {
        // PlaybackStateCompat 没有 STATE_IDLE, 用 STATE_NONE 表达"未开始/空闲"
        PlaybackState.Status.Idle -> PlaybackStateCompat.STATE_NONE
        PlaybackState.Status.Buffering -> PlaybackStateCompat.STATE_BUFFERING
        PlaybackState.Status.Playing -> PlaybackStateCompat.STATE_PLAYING
        PlaybackState.Status.Paused -> PlaybackStateCompat.STATE_PAUSED
        PlaybackState.Status.Stopped -> PlaybackStateCompat.STATE_STOPPED
    }

    /** [PlaybackState.Action] 集合 -> PlaybackStateCompat.ACTION_* 位标志。 */
    private fun Set<PlaybackState.Action>.toCompatActions(): Long {
        var v = 0L
        if (contains(PlaybackState.Action.Play)) v = v or PlaybackStateCompat.ACTION_PLAY
        if (contains(PlaybackState.Action.Pause)) v = v or PlaybackStateCompat.ACTION_PAUSE
        if (contains(PlaybackState.Action.PlayPause)) v = v or PlaybackStateCompat.ACTION_PLAY_PAUSE
        if (contains(PlaybackState.Action.SeekTo)) v = v or PlaybackStateCompat.ACTION_SEEK_TO
        if (contains(PlaybackState.Action.SkipToNext)) v = v or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (contains(PlaybackState.Action.SkipToPrevious)) v = v or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (contains(PlaybackState.Action.Stop)) v = v or PlaybackStateCompat.ACTION_STOP
        return v
    }
}
