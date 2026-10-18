@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.media.AudioFocusController
import io.legado.app.help.media.BecomingNoisyReceiver
import io.legado.app.help.media.MediaPlaybackLock
import io.legado.app.help.media.MediaNotificationHost
import io.legado.app.help.media.MediaPlaybackNotification
import io.legado.app.model.AudioPlay
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactory
import io.legado.app.model.audio.AudioPlayAnalyzeRuleFactoryImpl
import io.legado.app.model.audio.AudioPlayController
import io.legado.app.model.audio.AudioPlaySession
import io.legado.app.model.audio.AudioPlaySessionHost
import io.legado.app.model.audio.ExoPlayerAudioPlayController
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.service.AudioPlayService.Companion.MAX_LRC_TEXT
import io.legado.app.service.AudioPlayService.Companion.publishLyricLine
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 音频播放服务 (播放会话的 Android 宿主)。
 *
 * 会话状态机 (play/pause/resume/切章/倍速/定时/引擎状态回调) 在 commonMain
 * [AudioPlaySession], 章节资源加载与进度上报在 [io.legado.app.model.audio.AudioPlayManager],
 * 两者四端共用。本类只保留 Android 平台编排: ExoPlayer setMediaItem / MediaSession /
 * Notification / AudioFocus / WakeLock / 通知封面位图。
 *
 * 会话寿命归 OS: [onCreate] 开会话, [onDestroy] 终结; stop 命令走
 * `AudioPlayProvidersImpl` → [IntentAction.stop] → `stopSelf`。
 */
class AudioPlayService : BaseService(), AudioPlaySessionHost {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        /** 运行中的实例 (onCreate 置, onDestroy 清)。 */
        @JvmStatic
        private var instance: AudioPlayService? = null

        /** 是否暂停 ([MediaButtonReceiver] 据此决定播放/暂停切换)。 */
        @JvmStatic
        val pause: Boolean
            get() = instance?.session?.isPaused != false

        /**
         * Service 未启动时 setTimer 暂存目标分钟数, 启动后由 [AudioPlaySession.ensureRunning] 装入。
         *
         * 不加 `@JvmStatic`: 与本类实现 [AudioPlaySessionHost] 的同名实例属性会生成同签名的
         * 静态/实例访问器, JVM 不允许。
         */
        var pendingTimerMinute: Int = 0

        /**
         * 引擎实时播放位置 (毫秒), 经 `AudioPlayProvidersImpl.positionMs` 上抛给歌词界面按帧读取。
         *
         * Service 未运行时没有引擎, 最后已知位置就是正确答案。
         */
        @JvmStatic
        val positionMs: Int
            get() = instance?.session?.positionMs ?: AudioPlay.durChapterPos

        /**
         * 车载/锁屏 now-playing 标题改用歌词行 ([line] = null 回落章节名)。
         *
         * AVRCP 没有歌词字段, 车机屏幕那行字来自 MediaSession metadata 的 TITLE, 所以"车载歌词"
         * 就是让歌词顶掉标题 —— 也正因为会顶掉, 它必须由用户开关控制。
         */
        /**
         * 按两个辉光开关同步 offload 策略 (开关切换后调用, 无需重起播)。
         *
         * 采样旁路常挂, offload 是运行时参数: 都关时省电, 任一开启时关掉以拿到 PCM。
         */
        private fun syncAudioOffload() {
            instance?.exoPlayer?.applyOffloadPolicy()
        }

        @JvmStatic
        fun publishLyricLine(line: String?) {
            val service = instance ?: return
            if (service.publishedLyric == line) return
            service.publishedLyric = line
            service.upMediaMetadata()
        }

        private const val MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_PLAY
            or PlaybackStateCompat.ACTION_PAUSE
            or PlaybackStateCompat.ACTION_PLAY_PAUSE
            or PlaybackStateCompat.ACTION_SEEK_TO
            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            or PlaybackStateCompat.ACTION_STOP)

        private const val APP_ACTION_STOP = "Stop"
        private const val APP_ACTION_TIMER = "Timer"

        /**
         * 整段 LRC 文本的 metadata 键: 小米 MIUI 与华为 EMUI 的锁屏歌词读它自己滚动。
         *
         * 两家框架都私自把此键注册进了 `MediaMetadata.METADATA_KEYS_TYPE` (AOSP 无此键),
         * 所以它在小米上能随 metadata 更新触发回调; 其它系统与车机不认, 不影响标准字段。
         */
        private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

        /** 整段 LRC 要过 Binder, 超限按整行截断 (与 HarmonyOS AVSession 官方 lyric 上限 40960B 同量级)。 */
        private const val MAX_LRC_TEXT = 40_000
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
            onPause = { abandon -> pauseSession(abandon) },
            onResume = { resumeSession() }
        )
    }
    private val noisyReceiver = BecomingNoisyReceiver { pauseSession() }
    private val exoPlayer: ExoPlayer by lazy {
        // 采样旁路在构建期固定挂上 (与 offload 互斥), offload 由 applyOffloadPolicy 按开关决定
        ExoPlayerHelper.createHttpExoPlayer(this, audioOnly = true)
            .also { player -> player.applyOffloadPolicy() }
    }

    override val controller: AudioPlayController by lazy { ExoPlayerAudioPlayController(exoPlayer) }

    override val scope: CoroutineScope get() = lifecycleScope

    override val analyzeRuleFactory: AudioPlayAnalyzeRuleFactory
        get() = AudioPlayAnalyzeRuleFactoryImpl

    override var pendingTimerMinute: Int
        get() = Companion.pendingTimerMinute
        set(value) {
            Companion.pendingTimerMinute = value
        }

    /** 会话状态机 (四端共用), 命令由 [onStartCommand] 派进来。 */
    val session by lazy { AudioPlaySession(this) }

    private var mediaSessionCompat: MediaSessionCompat? = null
    private var cover: Bitmap = BookCover.notificationDefaultCover

    /** 前台通知宿主 (去重 + 刷新 + 前台启动, 与朗读共用同一实现)。 */
    private val notificationHost by lazy {
        MediaNotificationHost(
            context = this,
            notificationId = NotificationId.AudioPlayService,
            async = { block -> execute { block() } },
            build = ::createNotification,
            dedupKey = {
                listOf(pause, session.timerMinute, AudioPlay.book?.name, AudioPlay.durChapter?.title, cover)
            },
            errorLog = { message, e -> AppLog.put("创建音频播放通知出错,$message", e, true) },
            onForegroundFailure = { stopSelf() },
        )
    }

    /** 上次成功加载封面的 URL,用于避免同 URL 重复触发加载 + 通知 rebuild */
    private var lastCoverUrl: String? = null

    /** 对外发布中的歌词行 (null = now-playing 标题用章节名); 见 [publishLyricLine]。 */
    private var publishedLyric: String? = null

    /** 盯 [AudioPlay.durLrc]: 歌词异步就绪后补刷一次 metadata, 见 [armLrcWatch]。 */
    private var lrcWatchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRun = true
        instance = this
        // 媒体会话与耳机监听随宿主建立: 会话可在宿主存活期内重启(起播失败后重新起播),
        // 建在会话开始处会重复创建
        initMediaSession()
        noisyReceiver.register(this)
        session.ensureRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统杀进程后重启服务 (无启动意图) 时无播放书目: 直接退出, 不挂假"音频播放"通知
        if (intent == null && AudioPlay.book == null) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        intent?.action?.let { action ->
            when (action) {
                IntentAction.play -> session.play()
                IntentAction.loadPlayUrl -> session.loadPlayUrl()
                IntentAction.stopPlay -> session.stopPlay()
                IntentAction.syncAudioOffload -> syncAudioOffload()
                IntentAction.pause -> pauseSession()
                IntentAction.resume -> resumeSession()
                IntentAction.prev -> AudioPlay.prev()
                IntentAction.next -> AudioPlay.next()
                IntentAction.adjustSpeed -> session.adjustSpeed(intent.getFloatExtra("adjust", 1f))
                IntentAction.addTimer -> session.addTimer()
                IntentAction.setTimer -> session.setTimer(intent.getIntExtra("minute", 0))
                IntentAction.adjustProgress -> session.adjustProgress(
                    intent.getIntExtra("position", AudioPlay.durChapterPos)
                )

                IntentAction.playData -> loadCover(AudioPlay.durCoverUrl)
                IntentAction.stop -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        session.endSession()
        // 运行态随宿主销毁清理: 会话终结而宿主仍存活时它必须保持, 否则复用实例上的
        // 暂停/恢复/进度/媒体键与对外播控态全部失效
        isRun = false
        instance = null
        // 引擎与媒体会话随宿主销毁释放: 会话内部终结(起播链路失败)只停服务, 不动它们 ——
        // 平台取消这次停止而复用同一实例时, 后续命令必须还能落在活着的引擎上
        noisyReceiver.unregister(this)
        exoPlayer.release()
        mediaSessionCompat?.release()
        notificationHost.cancelOnDestroy()
    }

    /** 暂停: 先放掉唤醒锁与音频焦点, 再交会话落状态。 */
    private fun pauseSession(abandonFocus: Boolean = true) {
        playbackLock.release()
        if (abandonFocus) audioFocus.abandon()
        session.pause()
    }

    /** 恢复: 先拿唤醒锁并申请音频焦点, 抢到焦点再交会话起播。 */
    @SuppressLint("WakelockTimeout")
    private fun resumeSession() {
        playbackLock.acquire()
        if (!audioFocus.request()) {
            playbackLock.release()
            return
        }
        session.resume()
    }

    // ---------- AudioPlaySessionHost (平台接入面) ----------

    /**
     * 直链交给 ExoPlayer。
     *
     * 必须经 [AnalyzeUrl] 才能拆掉 legado 的 `url,{options}` 后缀并拿到 UA/Referer/Cookie
     * (见 [getMediaItem]); playWhenReady 已由会话置好。
     */
    override suspend fun startPlayback(url: String, positionMs: Int) {
        val analyzeUrl = AnalyzeUrl(
            url,
            source = AudioPlay.bookSource,
            ruleData = AudioPlay.book,
            chapter = AudioPlay.durChapter,
            coroutineContext = currentCoroutineContext()
        )
        exoPlayer.setMediaItem(analyzeUrl.getMediaItem())
        exoPlayer.seekTo(positionMs.toLong())
        exoPlayer.prepare()
    }

    /** 起播前拿唤醒锁并申请音频焦点; 抢不到焦点即刻释放唤醒锁并回滚。 */
    @SuppressLint("WakelockTimeout")
    override fun onBeforeStart(): Boolean {
        playbackLock.acquire()
        val granted = audioFocus.request()
        if (!granted) {
            playbackLock.release()
            return false
        }
        upAudioPlayNotification()
        return true
    }

    override fun playerErrorMessage(error: Throwable): String {
        val playbackError = error as? PlaybackException
        return "音频播放出错\n${playbackError?.errorCodeName} ${playbackError?.errorCode}"
    }

    override fun onSessionStart() {
        armLrcWatch()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        loadCover(AudioPlay.durCoverUrl)
    }

    override fun onSessionEnd() {
        playbackLock.release()
        lrcWatchJob?.cancel()
        lrcWatchJob = null
        audioFocus.abandon()
        // 先上报最终播放态再收尾: 位置与媒体会话都还在, 置 STOPPED 才落得下去
        // (引擎位置已由 AudioPlaySession.endSession 先存)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopSelf()
    }

    /** 起播失败: 释放这次起播拿到的唤醒锁与音频焦点并停掉服务; 会话级资源随宿主销毁收尾。 */
    override fun onHostStopRequest() {
        playbackLock.release()
        audioFocus.abandon()
        stopSelf()
    }

    /** 播放态变化后刷 MediaSession 与通知 (位置由 MediaSession 自己从引擎读, 不用入参)。 */
    override fun onSessionSync(positionMs: Long?) {
        upMediaSessionPlaybackState(
            if (pause) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        )
        upMediaMetadata()
        upAudioPlayNotification()
    }

    override fun onCoverUrl(url: String?) = loadCover(url)

    override fun onResetCoverCache() {
        lastCoverUrl = null
    }

    override fun toast(message: String) = toastOnUi(message)

    // ---------- MediaSession ----------

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat = MediaSessionCompat(this, "AudioPlayService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // 经会话 adjustProgress: 位置真源要吃下这次 seek, 否则车机拖动后歌词不重锚
                override fun onSeekTo(pos: Long) = session.adjustProgress(pos.toInt())

                override fun onPlay() = resumeSession()
                override fun onPause() = pauseSession()
                override fun onSkipToNext() {
                    AudioPlay.next()
                }

                override fun onSkipToPrevious() {
                    AudioPlay.prev()
                }
                override fun onStop() {
                    stopSelf()
                }

                override fun onCustomAction(action: String?, actionExtras: Bundle?) {
                    when (action) {
                        APP_ACTION_STOP -> stopSelf()
                        APP_ACTION_TIMER -> session.addTimer()
                    }
                }
            })
            setMediaButtonReceiver(
                broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
            )
            isActive = true
        }
    }

    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(state, exoPlayer.currentPosition, 1f)
                .setBufferedPosition(exoPlayer.bufferedPosition)
                // 媒体卡片 (Android 13+) 的按钮顺序 = 自定义动作的添加顺序 (先添加的排最左):
                // 定时在左、停止在右, 与通知栏动作顺序 (见 createNotification) 同一口径
                .addCustomAction(
                    APP_ACTION_TIMER,
                    androidAppString("set_timer"),
                    R.drawable.ic_time_add_24dp
                )
                .addCustomAction(
                    APP_ACTION_STOP,
                    androidAppString("stop"),
                    R.drawable.ic_stop_black_24dp
                )
                .build()
        )
    }

    private fun upMediaMetadata() {
        val lyric = publishedLyric
        val chapterTitle = AudioPlay.durChapter?.title
        val builder = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            // 开了车载歌词时标题就是当前歌词行 (AVRCP/Android Auto 只有这一个文本通道)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, lyric ?: chapterTitle ?: "null")
            // 歌词占了标题位, 章节名就提到书名位 (车机第二行), 否则照常显示书名
            .putText(
                MediaMetadataCompat.METADATA_KEY_ARTIST,
                (if (lyric != null) chapterTitle else AudioPlay.book?.name) ?: "null"
            )
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, AudioPlay.book?.author ?: "null")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, exoPlayer.duration)
        // 整段 LRC 只在有歌词时写; 没歌词就不写键, 上一份歌词随 metadata 重建一起消失
        wholeLrcText()?.let { builder.putText(METADATA_KEY_LYRIC, it) }
        mediaSessionCompat?.setMetadata(builder.build())
    }

    /**
     * 本章歌词的整段 LRC 原文; 无歌词、全篇无时间轴、归一后空白都返回 null。
     *
     * 超 [MAX_LRC_TEXT] 按整行截断 (锁屏能滚多少算多少, 胜过没有)。
     */
    private fun wholeLrcText(): String? {
        val lrc = AudioPlay.durLrc.value ?: return null
        if (!lrc.hasTimeline) return null
        val text = lrc.systemLrcText?.takeIf { it.isNotBlank() } ?: return null
        if (text.length <= MAX_LRC_TEXT) return text
        val cutIndex = text.lastIndexOf("\r\n", MAX_LRC_TEXT).takeIf { it > 0 }
            ?: text.lastIndexOf('\n', MAX_LRC_TEXT).takeIf { it > 0 }
            ?: MAX_LRC_TEXT
        return text.substring(0, cutIndex).takeIf { it.isNotBlank() }
    }

    /**
     * 盯歌词 StateFlow: 整段 LRC 由 `AudioPlayManager.loadLrcData` 异步算出, 到达时没有任何
     * 会话回调, 不补刷 metadata 就得等下次切章才上锁屏。
     *
     * 模式照 `BaseReadAloudService.armChapterWatch`: 自己持 Job, 重入先 cancel。
     */
    private fun armLrcWatch() {
        lrcWatchJob?.cancel()
        lrcWatchJob = lifecycleScope.launch {
            AudioPlay.durLrc.collect { upMediaMetadata() }
        }
    }

    /**
     * 加载封面图片(经 Coil 缓存)。同一 URL 短路掉,避免封面/通知重复刷新。
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

    // ---------- 通知 ----------

    private fun createNotification(): NotificationCompat.Builder {
        val current = session.timerMinute
        val title = when {
            pause -> androidAppString("audio_pause")
            current in 1..60 -> androidAppString("playing_timer", current)
            else -> androidAppString("audio_play_t")
        } + ": ${AudioPlay.book?.name}"
        val subtitle = AudioPlay.durChapter?.title?.takeUnless { it.isEmpty() }
            ?: androidAppString("audio_play_s")
        val playPause = if (pause) {
            MediaPlaybackNotification.Action(
                R.drawable.ic_play_24dp,
                androidAppString("resume"),
                servicePendingIntent<AudioPlayService>(IntentAction.resume)
            )
        } else {
            MediaPlaybackNotification.Action(
                R.drawable.ic_pause_24dp,
                androidAppString("pause"),
                servicePendingIntent<AudioPlayService>(IntentAction.pause)
            )
        }
        return MediaPlaybackNotification.build(
            context = this,
            spec = MediaPlaybackNotification.Spec(
                title = title,
                subtitle = subtitle,
                subText = androidAppString("audio"),
                cover = cover,
                contentIntent = activityPendingIntent<MainActivity>(IntentAction.activityAudioPlay) {
                    // 点击通知 → 打开当前播放书籍的音频界面 (对齐 origin activityPendingIntent<AudioPlayActivity>:
                    // 直接用内存 AudioPlay.book 进音频页, 不依赖 DB 解析; bookUrl 仅作冷启动兜底)
                    putExtra("route", "audio_play")
                    putExtra("bookUrl", AudioPlay.book?.bookUrl ?: "")
                    putExtra("chapterIndex", AudioPlay.durChapterIndex)
                },
                sessionToken = mediaSessionCompat?.sessionToken,
                actions = listOf(
                    MediaPlaybackNotification.Action(
                        R.drawable.ic_time_add_24dp,
                        androidAppString("set_timer"),
                        servicePendingIntent<AudioPlayService>(IntentAction.addTimer)
                    ),
                    MediaPlaybackNotification.Action(
                        R.drawable.ic_skip_previous,
                        androidAppString("pref_media_button_per_next"),
                        servicePendingIntent<AudioPlayService>(IntentAction.prev)
                    ),
                    playPause,
                    MediaPlaybackNotification.Action(
                        R.drawable.ic_skip_next,
                        androidAppString("pref_media_button_per_next_summary"),
                        servicePendingIntent<AudioPlayService>(IntentAction.next)
                    ),
                    MediaPlaybackNotification.Action(
                        R.drawable.ic_stop_black_24dp,
                        androidAppString("stop"),
                        servicePendingIntent<AudioPlayService>(IntentAction.stop)
                    ),
                ),
            ),
        )
    }

    private fun upAudioPlayNotification() {
        notificationHost.refresh()
    }

    override fun startForegroundNotification() {
        notificationHost.startForeground(this)
    }
}

/**
 * 按“是否需要 PCM 采样”设定解码省电策略: 需要时关 offload, 不需要时开 offload 省电。
 *
 * 旁路与 offload 官方互斥 (offload 模式不参与音频处理), 所以两者状态必须一致。
 * 另外还要求设备支持“变速场景下的 offload”(速度切换是常规操作), 不支持时该请求被
 * 采媒库静默忽略 —— 即“都关才省电”只在这种设备上真实生效。
 */
private fun ExoPlayer.applyOffloadPolicy() {
    val mode = if (AudioPlayShared.needsAudioPcm()) {
        AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
    } else {
        AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
    }
    val current = trackSelectionParameters
    if (current.audioOffloadPreferences.audioOffloadMode == mode) return
    trackSelectionParameters = current.buildUpon()
        .setAudioOffloadPreferences(
            AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(mode)
                .setIsSpeedChangeSupportRequired(true)
                .build()
        )
        .build()
}
