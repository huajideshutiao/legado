package io.legado.app.help.media

/**
 * 跨平台媒体元数据。
 *
 * 字段命名对齐 Android `MediaMetadataCompat` 的常用 key, 但全部用平台无关类型:
 * - 封面统一用 [artworkUrl] (URL 字符串), app 端 actual 自行下载转 Bitmap;
 * - 时长用 [durationMs] (Long 毫秒), 与 ExoPlayer.duration / MediaMetadataCompat.DURATION 一致。
 *
 * 调用方一般是 ReadBook / AudioPlay 模型, 在切歌/换章时构造本类后丢给
 * [MediaNotificationController.updateMetadata]。
 *
 * @property title       主标题, 一般是 "朗读: 书名" / "音频播放: 书名"
 * @property subtitle    副标题, 一般是章节标题
 * @property artist      作者/播音, 对应 MediaMetadataCompat.METADATA_KEY_ARTIST
 * @property album       专辑/书名, 对应 MediaMetadataCompat.METADATA_KEY_ALBUM
 * @property artworkUrl  封面 URL, app 端 actual 自行下载转 Bitmap
 * @property durationMs  总时长(ms), 朗读场景可为 0
 * @property subText     通知 subText, 如 "朗读" / "音频"
 */
data class MediaMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val subText: String? = null,
)

/**
 * 跨平台播放状态。
 *
 * 与 Android `PlaybackStateCompat` 一一映射:
 * - [Status.Idle]      -> STATE_IDLE
 * - [Status.Buffering] -> STATE_BUFFERING
 * - [Status.Playing]   -> STATE_PLAYING
 * - [Status.Paused]    -> STATE_PAUSED
 * - [Status.Stopped]   -> STATE_STOPPED
 *
 * @property status             当前状态
 * @property positionMs         当前播放位置(ms), 朗读场景是段落索引(为了与现有 Service
 *                              行为一致, app 端 actual 会把 nowSpeak.toLong() 传进来)
 * @property bufferedPositionMs 已缓冲位置(ms)
 * @property speed              播放速度(1f = 正常)
 * @property actions            当前可用的动作集合(决定锁屏控件按钮是否可点)
 */
data class PlaybackState(
    val status: Status,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val actions: Set<Action> = emptySet(),
) {

    /** 播放状态枚举。 */
    enum class Status {
        Idle,
        Buffering,
        Playing,
        Paused,
        Stopped,
    }

    /**
     * 播放控制动作枚举。
     *
     * 与 `PlaybackStateCompat.ACTION_*` 一一映射, app 端 actual 内部转成位标志。
     * actual 实现负责在锁屏控件上展示对应按钮。
     */
    enum class Action {
        Play,
        Pause,
        PlayPause,
        SeekTo,
        SkipToNext,
        SkipToPrevious,
        Stop,
    }
}

/**
 * 通知栏动作描述。
 *
 * 跨平台用枚举 + 标题表达; app 端 actual 翻译成 `NotificationCompat.Action`
 * (icon + title + PendingIntent), PendingIntent 由 actual 实现根据 [action]
 * 反查 Service + IntentAction 构造。
 *
 * @property action 动作类型
 * @property title  按钮展示文案
 * @property iconKey 图标 key(跨平台用字符串表达, app 端 actual 自行映射到 R.drawable.*)
 */
data class MediaAction(
    val action: PlaybackState.Action,
    val title: String,
    val iconKey: String,
)
