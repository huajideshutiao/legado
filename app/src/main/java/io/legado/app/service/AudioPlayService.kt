@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.media.AudioFocusController
import io.legado.app.help.media.BecomingNoisyReceiver
import io.legado.app.help.media.MediaPlaybackLock
import io.legado.app.help.media.MediaPlaybackNotification
import io.legado.app.help.media.SleepTimer
import io.legado.app.model.AudioPlay
import io.legado.app.model.AudioPlay.durLrcData
import io.legado.app.model.BookCover
import io.legado.app.model.LrcParser
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.webBook.WebBook.getContentAwait
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.utils.FlowBus
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splitties.systemservices.notificationManager

/**
 * 音频播放服务
 */
class AudioPlayService : BaseService(), Player.Listener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var playSpeed: Float = 1f

        /** Service 未启动时,setTimer 暂存目标分钟数,启动后由 onCreate 装入 SleepTimer */
        @JvmStatic
        var pendingTimerMinute: Int = 0

        @JvmStatic
        val timeMinute: Int
            get() = sleepTimer?.minutes ?: pendingTimerMinute

        @JvmStatic
        private var sleepTimer: SleepTimer? = null

        var url: String = ""
            private set

        private const val MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_PLAY
            or PlaybackStateCompat.ACTION_PAUSE
            or PlaybackStateCompat.ACTION_PLAY_PAUSE
            or PlaybackStateCompat.ACTION_SEEK_TO
            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            or PlaybackStateCompat.ACTION_STOP)

        private const val APP_ACTION_STOP = "Stop"
        private const val APP_ACTION_TIMER = "Timer"

        /** 歌词同步补偿偏移量 (毫秒) */
        private const val LRC_OFFSET_MS = 60
    }

    private val playbackLock by lazy {
        MediaPlaybackLock(
            tag = "legado:AudioPlayService",
            enabled = AppConfig.audioPlayUseWakeLock
        )
    }
    private val audioFocus by lazy {
        AudioFocusController(
            logTag = "Audio",
            isPaused = { pause },
            onPause = { abandon -> pause(abandon) },
            onResume = { resume() }
        )
    }
    private val noisyReceiver = BecomingNoisyReceiver { pause() }
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayerHelper.createHttpExoPlayer(this, audioOnly = true)
    }
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var position = AudioPlay.book?.durChapterPos ?: 0
    private var upNotificationJob: Coroutine<*>? = null
    private var upPlayProgressJob: Job? = null
    private var upPlayProgressForLrcJob: Job? = null
    private var cover: Bitmap = BookCover.notificationDefaultCover

    private var hasRefreshedOnPlayError = false

    private val loadingChapters = arrayListOf<Int>()

    /** 上次发出的歌词 position;切章 / seek 时由调用方置回 -1 */
    private var lastLrcPosition = -1

    /** 上次成功加载封面的 URL,用于避免同 URL 重复触发 Glide + 通知 rebuild */
    private var lastCoverUrl: String? = null

    /** 上一次发出的通知快照,用于跳过无变化的 rebuild。 */
    private data class NotificationSnapshot(
        val pause: Boolean,
        val sleepMin: Int,
        val bookName: String?,
        val chapterTitle: String?,
    )

    private var lastNotificationSnapshot: NotificationSnapshot? = null
    private var lastNotificationCover: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        isRun = true
        sleepTimer = SleepTimer(
            scope = lifecycleScope,
            eventKey = EventBus.AUDIO_DS,
            isPaused = { pause },
            onTimeout = { AudioPlay.stop() },
            onTick = { upAudioPlayNotification() }
        )
        exoPlayer.addListener(this)
        initMediaSession()
        noisyReceiver.register(this)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlay.book?.name ?: "")
        if (pendingTimerMinute > 0) {
            sleepTimer?.set(pendingTimerMinute)
            pendingTimerMinute = 0
        } else {
            // 通过事件汇报当前定时为 0,并启动通知刷新
            postEvent(EventBus.AUDIO_DS, 0)
            upAudioPlayNotification()
        }
        loadCover(AudioPlay.durCoverUrl ?: AudioPlay.book?.getDisplayCover())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.play -> triggerPlay(playNew = false)
                IntentAction.playNew -> triggerPlay(playNew = true)

                IntentAction.loadPlayUrl -> loadPlayUrl()

                IntentAction.stopPlay -> {
                    exoPlayer.stop()
                    upPlayProgressJob?.cancel()
                    upPlayProgressForLrcJob?.cancel()
                    AudioPlay.status = Status.STOP
                    AudioPlay.book?.save()
                    postEvent(EventBus.AUDIO_STATE, Status.STOP)
                }

                IntentAction.pause -> pause()
                IntentAction.resume -> resume()
                IntentAction.prev -> AudioPlay.prev()
                IntentAction.next -> AudioPlay.next()
                IntentAction.adjustSpeed -> upSpeed(intent.getFloatExtra("adjust", 1f))
                IntentAction.addTimer -> sleepTimer?.add()
                IntentAction.setTimer -> sleepTimer?.set(intent.getIntExtra("minute", 0))
                IntentAction.adjustProgress -> adjustProgress(
                    intent.getIntExtra("position", position)
                )

                IntentAction.playData -> loadCover(AudioPlay.durCoverUrl)
                IntentAction.stop -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 已经有 [AudioPlay.durPlayUrl] 时启动播放,做好状态/资源同步。
     */
    private fun triggerPlay(playNew: Boolean) {
        if (url == AudioPlay.durPlayUrl && !playNew && exoPlayer.playbackState != Player.STATE_IDLE) {
            return
        }
        exoPlayer.stop()
        upPlayProgressJob?.cancel()
        upPlayProgressForLrcJob?.cancel()
        pause = false
        position = if (playNew) 0 else AudioPlay.book?.durChapterPos ?: 0
        url = AudioPlay.durPlayUrl
        loadCover(AudioPlay.durCoverUrl ?: AudioPlay.book?.getDisplayCover())
        play()
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackLock.release()
        isRun = false
        audioFocus.abandon()
        noisyReceiver.unregister(this)
        sleepTimer?.cancel()
        sleepTimer = null
        ReadTimeRecorder.endImmediately(ReadTimeRecorder.Source.AUDIO)
        AudioPlay.durChapterPos = exoPlayer.currentPosition.toInt()
        AudioPlay.saveRead()
        exoPlayer.release()
        mediaSessionCompat?.release()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        AudioPlay.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.AudioPlayService)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun play() {
        playbackLock.acquire()
        upAudioPlayNotification()
        if (!requestFocus()) return
        execute(context = Main) {
            AudioPlay.status = Status.STOP
            postEvent(EventBus.AUDIO_STATE, Status.STOP)
            upPlayProgressJob?.cancel()
            upPlayProgressForLrcJob?.cancel()
            val analyzeUrl = AnalyzeUrl(
                url,
                source = AudioPlay.bookSource,
                ruleData = AudioPlay.book,
                chapter = AudioPlay.durChapter,
                coroutineContext = coroutineContext
            )
            exoPlayer.setMediaItem(analyzeUrl.getMediaItem())
            exoPlayer.playWhenReady = true
            exoPlayer.seekTo(position.toLong())
            exoPlayer.prepare()
        }.onError {
            AppLog.put("播放出错\n${it.localizedMessage}", it)
            toastOnUi("$url ${it.localizedMessage}")
            stopSelf()
        }
    }

    private fun pause(abandonFocus: Boolean = true) {
        playbackLock.release()
        try {
            pause = true
            ReadTimeRecorder.end(ReadTimeRecorder.Source.AUDIO)
            if (abandonFocus) audioFocus.abandon()
            upPlayProgressJob?.cancel()
            upPlayProgressForLrcJob?.cancel()
            position = exoPlayer.currentPosition.toInt()
            if (exoPlayer.isPlaying) exoPlayer.pause()
            upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
            AudioPlay.status = Status.PAUSE
            postEvent(EventBus.AUDIO_STATE, Status.PAUSE)
            upAudioPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun resume() {
        playbackLock.acquire()
        try {
            pause = false
            ReadTimeRecorder.start(ReadTimeRecorder.Source.AUDIO, AudioPlay.book?.name ?: "")
            if (url.isEmpty()) {
                AudioPlay.loadOrUpPlayUrl()
                return
            }
            if (!exoPlayer.isPlaying) exoPlayer.play()
            upPlayProgress()
            upPlayProgressForLrc()
            upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
            AudioPlay.status = Status.PLAY
            postEvent(EventBus.AUDIO_STATE, Status.PLAY)
            upAudioPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
            stopSelf()
        }
    }

    private fun adjustProgress(position: Int) {
        this.position = position
        exoPlayer.seekTo(position.toLong())
        upMediaSessionPlaybackState(
            if (pause) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        )
        lastLrcPosition = -1
        upPlayProgressForLrc()
    }

    @SuppressLint(value = ["ObsoleteSdkInt"])
    private fun upSpeed(adjust: Float) {
        kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                playSpeed = adjust
                exoPlayer.setPlaybackSpeed(playSpeed)
                postEvent(EventBus.AUDIO_SPEED, playSpeed)
                // 事件驱动版 lrc 推进的 delay 时长按 playSpeed 缩放,变速时需要重启重算
                upPlayProgressForLrc()
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE,
            Player.STATE_BUFFERING -> Unit

            Player.STATE_READY -> {
                hasRefreshedOnPlayError = false
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlay.status = if (exoPlayer.playWhenReady) Status.PLAY else Status.PAUSE
                postEvent(EventBus.AUDIO_STATE, AudioPlay.status)
                postEvent(EventBus.AUDIO_SIZE, exoPlayer.duration.toInt())
                upMediaMetadata()
                upMediaSessionPlaybackState(
                    if (pause) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
                )
                upPlayProgress()
                upPlayProgressForLrc()
                AudioPlay.saveDurChapter(exoPlayer.duration)
            }

            Player.STATE_ENDED -> {
                upPlayProgressJob?.cancel()
                upPlayProgressForLrcJob?.cancel()
                AudioPlay.playPositionChanged(exoPlayer.duration.toInt())
                AudioPlay.next()
            }
        }
        upAudioPlayNotification()
    }

    private fun upMediaMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, AudioPlay.durChapter?.title ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, AudioPlay.book?.name ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, AudioPlay.book?.author ?: "null")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, exoPlayer.duration)
            .build()
        mediaSessionCompat?.setMetadata(metadata)
    }

    override fun onPlayerError(error: PlaybackException) {
        if (!hasRefreshedOnPlayError) {
            hasRefreshedOnPlayError = true
            refreshChapter()
            return
        }
        super.onPlayerError(error)
        AudioPlay.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
        val errorMsg = "音频播放出错\n${error.errorCodeName} ${error.errorCode}"
        AppLog.put(errorMsg, error)
        toastOnUi(errorMsg)
    }

    /**
     * 每隔 1 秒发送播放进度
     */
    private fun upPlayProgress() {
        upPlayProgressJob?.cancel()
        upPlayProgressJob = lifecycleScope.launch {
            while (isActive) {
                AudioPlay.durChapterPos = exoPlayer.currentPosition.toInt()
                postEvent(EventBus.AUDIO_BUFFER_PROGRESS, exoPlayer.bufferedPosition.toInt())
                postEvent(EventBus.AUDIO_PROGRESS, AudioPlay.durChapterPos)
                delay(1000)
            }
        }
    }

    /**
     * 推进当前章节歌词高亮位置。
     *
     * 节能策略两层:
     * 1. 事件驱动: 按下一句歌词时间戳精确 delay,而非高频轮询;
     *    一首歌只唤醒 N 行次,而非 时长(s)*20 次。
     * 2. 订阅门控: 通过 FlowBus 的 subscriptionCount 感知是否有 Activity 在收事件,
     *    没有时直接挂起整个循环,后台/锁屏场景零空转。
     *
     * 调用方在以下场景触发重启: STATE_READY / adjustProgress / 调整播放速度。
     */
    private fun upPlayProgressForLrc() {
        upPlayProgressForLrcJob?.cancel()
        val lrc = durLrcData ?: return
        if (lrc.isEmpty() || lrc.last().first == -1) return
        // 切歌中(stop 后未 prepare 新 mediaItem)player 处于 IDLE,
        // exoPlayer.currentPosition 仍是上一首的残留位置,据此推进会把新章节歌词跳到旧位置;
        // 新媒体 prepare 完成后 STATE_READY 会再次触发本方法,这里直接退出。
        if (exoPlayer.playbackState == Player.STATE_IDLE) return
        val subCount = FlowBus.withSticky(EventBus.AUDIO_LRCPROGRESS).subscriptionCount

        // 注意: 此协程绑定主线程(lifecycleScope 默认 Main),因为 ExoPlayer 默认绑定主 looper,
        // 从其它线程访问 currentPosition 会抛 IllegalStateException。
        // 内层循环需要保证每条路径都至少经过一个挂起点(withTimeoutOrNull / return),否则当
        // currentPosition 停滞时,逐行 ++position 会变成无挂起的紧密循环,配合 SharedFlow.tryEmit
        // 内部 synchronized 累积出 ANR。
        upPlayProgressForLrcJob = lifecycleScope.launch {
            while (isActive) {
                subCount.first { it > 0 }
                val curLrc = durLrcData ?: break
                val curMs = exoPlayer.currentPosition + LRC_OFFSET_MS
                // 续推: 上次位置仍在范围且没被新 lrc 失效就直接接上,否则从 0 起重新单向扫
                var position = lastLrcPosition.takeIf {
                    it in 0 until curLrc.size && curLrc[it].first <= curMs
                } ?: 0
                while (position + 1 < curLrc.size && curLrc[position + 1].first <= curMs) {
                    position++
                }
                if (position != lastLrcPosition) {
                    lastLrcPosition = position
                    postEvent(EventBus.AUDIO_LRCPROGRESS, position)
                }
                // 已停在末行: 直接结束协程。线性播放不会再前进,需要重启的事件
                // (seek/换章/换速/新 lrc 到货) 都已在对应入口显式调用 upPlayProgressForLrc。
                // 不退出会导致外层 subCount.first { it > 0 } 在已订阅时同步返回,变成无挂起紧密循环。
                if (position >= curLrc.size - 1) return@launch

                while (isActive && position < curLrc.size - 1) {
                    val remain =
                        ((curLrc[position + 1].first - exoPlayer.currentPosition - LRC_OFFSET_MS)
                        / playSpeed).toLong()
                    if (remain > 0) {
                        val dropped = withTimeoutOrNull(remain) {
                            subCount.first { it == 0 }
                            true
                        } == true
                        if (dropped) break
                        position++
                        lastLrcPosition = position
                        postEvent(EventBus.AUDIO_LRCPROGRESS, position)
                        continue
                    }
                    // 时间戳已过期: 可能是 seek 跳跃,也可能是 currentPosition 停滞。
                    // 单向向前扫到真实位置;若仍未前进,直接退出协程,由 upPlayProgress 的 1s 心跳
                    // 在 player 真正推进后重启,避免无挂起点的忙等。
                    val before = position
                    val nowMs = exoPlayer.currentPosition + LRC_OFFSET_MS
                    while (position + 1 < curLrc.size && curLrc[position + 1].first <= nowMs) {
                        position++
                    }
                    if (position == before) return@launch
                    lastLrcPosition = position
                    postEvent(EventBus.AUDIO_LRCPROGRESS, position)
                }
            }
        }
    }

    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(state, exoPlayer.currentPosition, 1f)
                .setBufferedPosition(exoPlayer.bufferedPosition)
                .addCustomAction(
                    APP_ACTION_STOP,
                    getString(R.string.stop),
                    R.drawable.ic_stop_black_24dp
                )
                .addCustomAction(
                    APP_ACTION_TIMER,
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat = MediaSessionCompat(this, "AudioPlayService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSeekTo(pos: Long) {
                    position = pos.toInt()
                    exoPlayer.seekTo(pos)
                }

                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = AudioPlay.next()
                override fun onSkipToPrevious() = AudioPlay.prev()
                override fun onStop() {
                    stopSelf()
                }

                override fun onCustomAction(action: String?, actionExtras: Bundle?) {
                    when (action) {
                        APP_ACTION_STOP -> stopSelf()
                        APP_ACTION_TIMER -> sleepTimer?.add()
                    }
                }
            })
            setMediaButtonReceiver(
                broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
            )
            isActive = true
        }
    }

    private fun requestFocus(): Boolean = audioFocus.request()

    private fun createNotification(): NotificationCompat.Builder {
        val current = sleepTimer?.minutes ?: 0
        val title = when {
            pause -> getString(R.string.audio_pause)
            current in 1..60 -> getString(R.string.playing_timer, current)
            else -> getString(R.string.audio_play_t)
        } + ": ${AudioPlay.book?.name}"
        val subtitle = AudioPlay.durChapter?.title?.takeUnless { it.isEmpty() }
            ?: getString(R.string.audio_play_s)
        val playPause = if (pause) {
            MediaPlaybackNotification.Action(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                servicePendingIntent<AudioPlayService>(IntentAction.resume)
            )
        } else {
            MediaPlaybackNotification.Action(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                servicePendingIntent<AudioPlayService>(IntentAction.pause)
            )
        }
        return MediaPlaybackNotification.build(
            context = this,
            channelId = AppConst.channelIdReadAloud,
            title = title,
            subtitle = subtitle,
            cover = cover,
            contentIntent = activityPendingIntent<AudioPlayActivity>("activity"),
            actions = listOf(
                MediaPlaybackNotification.Action(
                    R.drawable.ic_time_add_24dp,
                    getString(R.string.set_timer),
                    servicePendingIntent<AudioPlayService>(IntentAction.addTimer)
                ),
                MediaPlaybackNotification.Action(
                    R.drawable.ic_skip_previous,
                    getString(R.string.pref_media_button_per_next),
                    servicePendingIntent<AudioPlayService>(IntentAction.prev)
                ),
                playPause,
                MediaPlaybackNotification.Action(
                    R.drawable.ic_skip_next,
                    getString(R.string.pref_media_button_per_next_summary),
                    servicePendingIntent<AudioPlayService>(IntentAction.next)
                ),
                MediaPlaybackNotification.Action(
                    R.drawable.ic_stop_black_24dp,
                    getString(R.string.stop),
                    servicePendingIntent<AudioPlayService>(IntentAction.stop)
                ),
            ),
            compactActionIndices = intArrayOf(1, 2, 3),
            sessionToken = mediaSessionCompat?.sessionToken,
            subText = getString(R.string.audio),
        )
    }

    private fun upAudioPlayNotification() {
        val snapshot = NotificationSnapshot(
            pause = pause,
            sleepMin = sleepTimer?.minutes ?: 0,
            bookName = AudioPlay.book?.name,
            chapterTitle = AudioPlay.durChapter?.title,
        )
        if (snapshot == lastNotificationSnapshot && lastNotificationCover === cover) return
        lastNotificationSnapshot = snapshot
        lastNotificationCover = cover
        upNotificationJob?.cancel()
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.AudioPlayService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建音频播放通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    override fun startForegroundNotification() {
        execute {
            try {
                val notification = createNotification()
                startForeground(NotificationId.AudioPlayService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建音频播放通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    /**
     * 加载封面图片(使用 Glide 缓存)。同一 URL 短路掉,避免封面/通知重复刷新。
     */
    private fun loadCover(url: String?) {
        val finalUrl = url?.takeIf { it.isNotBlank() } ?: AudioPlay.book?.getDisplayCover()
        if (finalUrl.isNullOrBlank()) {
            if (lastCoverUrl == null && cover === BookCover.notificationDefaultCover) return
            lastCoverUrl = null
            cover = BookCover.notificationDefaultCover
            upMediaMetadata()
            upAudioPlayNotification()
            return
        }
        if (finalUrl == lastCoverUrl) return
        lastCoverUrl = finalUrl
        BookCover.loadNotificationCover(this, finalUrl, lifecycleScope) {
            cover = it
            upMediaMetadata()
            upAudioPlayNotification()
        }
    }

    // ---------- 章节数据加载 ----------

    /**
     * 同一章节不允许并发加载,失败时也要 remove。
     */
    private fun addLoading(index: Int): Boolean = synchronized(loadingChapters) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    private fun removeLoading(index: Int) = synchronized(loadingChapters) {
        loadingChapters.remove(index)
    }

    /**
     * 清掉当前章节 URL 并重新加载,用于播放器报错后的自动重试。
     */
    private fun refreshChapter() {
        val chapter = AudioPlay.durChapter ?: return
        chapter.resourceUrl = null
        AudioPlay.durPlayUrl = ""
        loadPlayUrl()
    }

    /**
     * 加载当前章节的播放 URL,并联动拉取封面 + 歌词。
     *
     * 流程:
     * 1. 取 chapter.resourceUrl,没有则 fetch 章节内容
     * 2. 内容回来后写回章节并触发 [triggerPlay]
     * 3. 同时并行启动 [loadCoverUrl] 与 [loadLrcData]
     */
    private fun loadPlayUrl() {
        val index = AudioPlay.durChapterIndex
        if (!addLoading(index)) return
        val book = AudioPlay.book
        val bookSource = AudioPlay.bookSource
        if (book == null || bookSource == null) {
            removeLoading(index)
            toastOnUi("book or source is null")
            return
        }
        lifecycleScope.launch {
            AudioPlay.upDurChapter()
            val chapter = AudioPlay.durChapter
            if (chapter == null) {
                removeLoading(index)
                return@launch
            }
            if (chapter.isVolume) {
                AudioPlay.skipTo(index + 1)
                removeLoading(index)
                return@launch
            }
            postEvent(EventBus.AUDIO_LOADING, true)
            AudioPlay.durCoverUrl = null
            AudioPlay.durLrcData = null
            lastLrcPosition = -1
            lastCoverUrl = null
            loadCoverUrl(bookSource, book, chapter)
            loadLrcData(bookSource, book, chapter)
            execute {
                chapter.resourceUrl
                    ?: getContentAwait(bookSource, book, chapter, needSave = false)
            }.onSuccess { content ->
                if (content.isEmpty()) {
                    toastOnUi("未获取到资源链接")
                } else {
                    if (chapter.resourceUrl != content) {
                        chapter.resourceUrl = content
                        if (AudioPlay.inBookshelf) appDb.bookChapterDao.update(chapter)
                    }
                    contentLoadFinish(chapter, content)
                }
            }.onError {
                AppLog.put("获取资源链接出错\n$it", it, true)
                postEvent(EventBus.AUDIO_LOADING, false)
            }.onCancel {
                removeLoading(index)
            }.onFinally {
                removeLoading(index)
            }
        }
    }

    /**
     * 用书源的 musicCover 规则计算封面 URL,空规则就用书的默认 cover。
     */
    private fun loadCoverUrl(bookSource: BookSource, book: Book, chapter: BookChapter) {
        execute {
            val musicCover = bookSource.contentRule.musicCover
            AudioPlay.durCoverUrl = if (!musicCover.isNullOrBlank()) {
                val rule = AnalyzeRule(book, bookSource).apply {
                    coroutineContext = currentCoroutineContext()
                    setBaseUrl(chapter.url)
                    this.chapter = chapter
                }
                rule.evalJS(musicCover).toString()
            } else book.getDisplayCover()
        }.onSuccess {
            val coverUrl = AudioPlay.durCoverUrl ?: return@onSuccess
            postEvent(EventBus.AUDIO_COVER, coverUrl)
            loadCover(coverUrl)
        }
    }

    /**
     * 用书源的 lrcRule 规则计算歌词数据。
     */
    private fun loadLrcData(
        bookSource: BookSource,
        book: Book,
        chapter: BookChapter
    ): Coroutine<List<Pair<Int, String>>> {
        return execute {
            val lrcRule = bookSource.contentRule.lrcRule
            if (lrcRule.isNullOrBlank()) return@execute emptyList()
            val rule = AnalyzeRule(book, bookSource).apply {
                coroutineContext = currentCoroutineContext()
                setBaseUrl(chapter.url)
                this.chapter = chapter
            }
            val raw = rule.evalJS(lrcRule) as? List<*> ?: return@execute emptyList()
            LrcParser.parse(raw)
        }.onSuccess {
            if (it.isEmpty()) return@onSuccess
            AudioPlay.durLrcData = it
            lastLrcPosition = -1
            postEvent(EventBus.AUDIO_LRC, it)
            postEvent(EventBus.AUDIO_LRCPROGRESS, 0)
            // 歌词到货后显式启动推进协程 (STATE_READY 可能早于歌词加载完成)
            upPlayProgressForLrc()
        }.onError {
            AppLog.put("获取歌词出错\n$it", it, true)
        }
    }

    private fun contentLoadFinish(chapter: BookChapter, content: String) {
        if (chapter.index != AudioPlay.book?.durChapterIndex) return
        AudioPlay.durPlayUrl = content
        val isPlayToEnd =
            AudioPlay.durChapterIndex + 1 == AudioPlay.simulatedChapterSize &&
                AudioPlay.durChapterPos == AudioPlay.durAudioSize
        triggerPlay(playNew = isPlayToEnd)
    }

}
