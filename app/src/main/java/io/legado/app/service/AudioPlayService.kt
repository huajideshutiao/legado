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
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.Status
import io.legado.app.help.book.save
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.media.AudioFocusController
import io.legado.app.help.media.BecomingNoisyReceiver
import io.legado.app.help.media.MediaPlaybackLock
import io.legado.app.help.media.MediaPlaybackNotification
import io.legado.app.help.media.SleepTimer
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactoryImpl
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlayControllerListener
import io.legado.app.model.audio.AudioPlayManager
import io.legado.app.model.audio.AudioPlayManagerListener
import io.legado.app.model.audio.ExoPlayerAudioPlayController
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import splitties.systemservices.notificationManager

/**
 * 音频播放服务
 *
 * 纯逻辑 (进度上报 / LRC 推进 / 章节加载) 已下沉到 [AudioPlayManager] (commonMain),
 * 本类只保留平台相关编排: ExoPlayer setMediaItem / MediaSession / Notification /
 * AudioFocus / WakeLock / Glide 封面加载。播放器状态回调经 [AudioPlayControllerListener]
 * 从 [ExoPlayerAudioPlayController] 透传, 章节加载副作用经 [AudioPlayManagerListener]
 * 回调本类。
 */
class AudioPlayService : BaseService(), AudioPlayControllerListener, AudioPlayManagerListener {

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
    private val audioController: AudioPlayController by lazy {
        ExoPlayerAudioPlayController(exoPlayer)
    }
    private val audioPlayManager: AudioPlayManager by lazy {
        AudioPlayManager(
            controller = audioController,
            scope = lifecycleScope,
            analyzeRuleFactory = AudioPlayAnalyzeRuleFactoryImpl,
            listener = this,
        )
    }
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var position = AudioPlay.book?.durChapterPos ?: 0
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap = BookCover.notificationDefaultCover

    private var hasRefreshedOnPlayError = false

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
        // controller init 时已 exoPlayer.addListener(controller), 这里挂接状态回调
        audioController.listener = this
        // 同步 companion playSpeed -> manager (LRC 推进 delay 时长按此缩放)
        audioPlayManager.playSpeed = playSpeed
        sleepTimer = SleepTimer(
            scope = lifecycleScope,
            postMinute = { postEvent(EventBus.AUDIO_DS, it) },
            isPaused = { pause },
            onTimeout = { AudioPlay.stop() },
            onTick = { upAudioPlayNotification() }
        )
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

                IntentAction.loadPlayUrl -> audioPlayManager.loadPlayUrl()

                IntentAction.stopPlay -> {
                    exoPlayer.stop()
                    audioPlayManager.cancelProgressJobs()
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
        if (url == AudioPlay.durPlayUrl && !playNew && exoPlayer.playbackState != AudioPlayController.STATE_IDLE) {
            return
        }
        exoPlayer.stop()
        audioPlayManager.cancelProgressJobs()
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
        audioPlayManager.onDestroy()
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
            // 拉链接+缓冲窗口置 LOADING, 不再置 STOP: 后者会让 Activity.onDestroy 误判"没在播"而 stopSelf
            AudioPlay.status = Status.LOADING
            postEvent(EventBus.AUDIO_STATE, Status.LOADING)
            audioPlayManager.cancelProgressJobs()
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
            audioPlayManager.cancelProgressJobs()
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
            audioPlayManager.upPlayProgress()
            audioPlayManager.upPlayProgressForLrc()
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
        // seek 后歌词位置失效, 重算 (对标原版 adjustProgress 的 lastLrcPosition = -1)
        audioPlayManager.resetLrcPosition()
        audioPlayManager.upPlayProgressForLrc()
    }

    @SuppressLint(value = ["ObsoleteSdkInt"])
    private fun upSpeed(adjust: Float) {
        kotlin.runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                playSpeed = adjust
                audioPlayManager.playSpeed = adjust
                exoPlayer.setPlaybackSpeed(playSpeed)
                postEvent(EventBus.AUDIO_SPEED, playSpeed)
                // 事件驱动版 lrc 推进的 delay 时长按 playSpeed 缩放,变速时需要重启重算
                audioPlayManager.upPlayProgressForLrc()
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            AudioPlayController.STATE_IDLE,
            AudioPlayController.STATE_BUFFERING -> Unit

            AudioPlayController.STATE_READY -> {
                hasRefreshedOnPlayError = false
                postEvent(EventBus.AUDIO_LOADING, false)
                AudioPlay.status = if (exoPlayer.playWhenReady) Status.PLAY else Status.PAUSE
                postEvent(EventBus.AUDIO_STATE, AudioPlay.status)
                postEvent(EventBus.AUDIO_SIZE, exoPlayer.duration.toInt())
                upMediaMetadata()
                upMediaSessionPlaybackState(
                    if (pause) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
                )
                audioPlayManager.upPlayProgress()
                audioPlayManager.upPlayProgressForLrc()
                AudioPlay.saveDurChapter(exoPlayer.duration)
            }

            AudioPlayController.STATE_ENDED -> {
                audioPlayManager.cancelProgressJobs()
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

    override fun onPlayerError(error: Throwable) {
        if (!hasRefreshedOnPlayError) {
            hasRefreshedOnPlayError = true
            audioPlayManager.refreshChapter()
            return
        }
        AudioPlay.status = Status.STOP
        postEvent(EventBus.AUDIO_STATE, Status.STOP)
        postEvent(EventBus.AUDIO_LOADING, false)
        val playbackError = error as? PlaybackException
        val errorMsg = "音频播放出错\n${playbackError?.errorCodeName} ${playbackError?.errorCode}"
        AppLog.put(errorMsg, error)
        toastOnUi(errorMsg)
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

    // ---------- AudioPlayManagerListener (commonMain 章节加载逻辑的平台副作用回调) ----------

    override fun onTriggerPlay(playNew: Boolean) {
        triggerPlay(playNew)
    }

    override fun onLoadCover(url: String?) {
        loadCover(url)
    }

    override fun onResetCoverCache() {
        lastCoverUrl = null
    }

    override fun onToast(message: String) {
        toastOnUi(message)
    }

}
