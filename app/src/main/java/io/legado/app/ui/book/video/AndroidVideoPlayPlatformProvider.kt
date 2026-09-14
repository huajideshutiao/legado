package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.hasPlayableScheme
import io.legado.app.utils.toggleSystemBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidVideoPlayPlatformProvider(
    private val activity: MainActivity,
) : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = AndroidVideoPlayerController(activity, screenModel, onPlaybackEnded)

    // media3 UnstableApi: PlayerView 控制接口 (setShowBuffering/resizeMode 等)。
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @Composable
    override fun RenderSurface(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val androidController = controller as AndroidVideoPlayerController

        // 横屏自动进入全屏 (对照原版 onConfigurationChanged: 只在方向真翻转的那一次动)。
        // 键不能带 isPhone: 它由容器尺寸算出, 分屏拖窗 / 折叠屏展开都会让它变, 原写法会在这
        // 些与方向无关的时机重跑, 把用户手动开的窗口内全屏静默踢掉。
        val config = LocalConfiguration.current
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val windowSize = LocalWindowInfo.current.containerSize
        val isPhone = with(LocalDensity.current) {
            minOf(windowSize.width, windowSize.height).toDp() < DesignTokens.wideScreenMinWidth
        }
        var lastLandscape by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(isLandscape) {
            val previous = lastLandscape
            lastLandscape = isLandscape
            // 首次组合只立基线: 进页时设备本来就横持着也不该替用户开全屏 (原版
            // onConfigurationChanged 本来就是"变化才回调", 不在创建时跑)
            if (previous == null || previous == isLandscape) return@LaunchedEffect
            // 右下角钮的系统级全屏会强制转横屏, 那一次翻转由它自己负责; 原写法会再叠一层
            // 窗口内全屏, 使两种全屏的互斥在安卓失效 (退出系统级全屏后卡在窗口内全屏)
            if (screenModel.state.value.isSystemFullScreen) return@LaunchedEffect
            screenModel.setFullScreen(isLandscape && isPhone)
        }

        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    player = androidController.player
                }
            },
            update = { it.player = androidController.player },
            modifier = modifier.fillMaxSize(),
        )

        // videoUrl: 非 null = 装载新源, null = 章节重新加载中 (loadChapter 先清空旧源)
        LaunchedEffect(androidController, screenModel) {
            screenModel.shared.videoUrl.collect { source ->
                // 清空 url 只是「没有新源」的数据状态, 不是停止命令: 不显式 stop 的话上一章
                // 画面与声音会一直播到新章解析完 (对照原版 menu_refresh 先 player.pause()
                // 再 refreshChapter, 切章路径上原版换新 player 天然停了旧的)
                if (source != null) androidController.updateSource(source)
                else androidController.stop()
            }
        }
        DisposableEffect(androidController, screenModel) {
            androidController.bind(screenModel)
            onDispose { androidController.unbind() }
        }
    }

    @Composable
    override fun rememberGestureController(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
    ): VideoGestureController {
        val androidController = controller as AndroidVideoPlayerController
        val audioManager = remember {
            activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        val maxVolume = remember(audioManager) {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }
        return remember(androidController, audioManager, maxVolume) {
            VideoGestureController(
                isPlaying = { androidController.player.isPlaying },
                positionMs = { androidController.player.currentPosition },
                durationMs = { androidController.player.duration },
                speed = { androidController.player.playbackParameters.speed },
                setSpeed = { speed ->
                    androidController.player.playbackParameters = PlaybackParameters(
                        speed,
                        androidController.player.playbackParameters.pitch,
                    )
                },
                onPlayPause = {
                    val p = androidController.player
                    if (p.isPlaying) p.pause() else p.play()
                },
                seekTo = { androidController.player.seekTo(it) },
                readBrightness = {
                    val a = activity.window.attributes
                    if (a.screenBrightness <= 0f) 0f else a.screenBrightness
                },
                writeBrightness = { value ->
                    activity.window.attributes = activity.window.attributes.apply {
                        screenBrightness = value
                    }
                },
                readVolume = { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() },
                writeVolume = { value ->
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
                },
                onToggleControls = screenModel::onToggleControls,
                onGestureText = screenModel::onGestureText,
                volumeMax = maxVolume.toFloat(),
                volumeStep = 1f,
            )
        }
    }

    override fun applyFullscreen(enabled: Boolean) {
        activity.toggleSystemBar(!enabled)
    }

    // 横屏全屏 (对照原版全屏钮 requestedOrientation 切换): 退出写 UNSPECIFIED 而非 PORTRAIT,
    // 单 Activity 下方向锁挂在 MainActivity 上, 留锁会随视频页出栈带到全 app
    override fun applySystemFullScreen(enabled: Boolean) {
        activity.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private class AndroidVideoPlayerController(
    activity: MainActivity,
    private var screenModel: VideoPlayScreenModel,
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {
    val player: ExoPlayer = ExoPlayerHelper.createHttpExoPlayer(activity)
    private var bound = false

    /** 播放态快照流 (回显唯一数据源, 见共享层 [PlaybackSnapshot]): 共享层现取,
     *  本端不再拿 onPlayerState 回灌页面状态。 */
    private val _playback = MutableStateFlow(PlaybackSnapshot())
    override val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()

    /** 已加载的 url: 页面转场会重建 RenderSurface 组合, LaunchedEffect(videoUrl.collect)
     *  随之重启并被 StateFlow 补发同值, 守卫避免重复 setMediaItem+prepare 从头重播
     *  (对齐桌面 startedUrl / iOS / ohos loadedUrl 模式)。[stop] 必须同步清掉它,
     *  否则同链接重试 (refreshChapter 重新 emit 同一 url) 会被守卫拦掉。 */
    private var loadedUrl: String? = null

    /** 从播放器现值映射一份快照并发射 (media3 各回调都在状态变更之后触发, 直读 player 即可)。 */
    private fun publishPlayback(p: Player = player) {
        _playback.value = PlaybackSnapshot(
            playWhenReady = p.playWhenReady,
            isPlaying = p.isPlaying,
            // 折叠「媒体尚未就绪 / 在等数据」: 共享层不再读 playbackState 兜底,
            // IDLE + playWhenReady = 起播前 (已 prepare 未出帧) 也算缓冲,
            // 否则原版的起播转圈会丢 (桌面/iOS/鸿蒙同口径)
            isBuffering = p.playbackState == Player.STATE_BUFFERING ||
                (p.playbackState == Player.STATE_IDLE && p.playWhenReady),
            speed = p.playbackParameters.speed,
            ended = p.playbackState == Player.STATE_ENDED,
            idle = p.playbackState == Player.STATE_IDLE,
        )
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishPlayback()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishPlayback()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishPlayback()
            // 播放真正成功 (READY) 才重置错误重试标记 (对齐原版 VideoPlayActivity
            // onPlaybackStateChanged: STATE_READY → hasRefreshedOnPlayError = false;
            // 链接不可用时永不 READY, 同章节只自动重试一次, 不再无限循环)
            if (playbackState == Player.STATE_READY) {
                screenModel.shared.resetRetryOnPlayError()
            }
            if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            publishPlayback()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 对齐 iOS handlePlayError: 先清 loadedUrl 守卫, 自动重试 (refreshChapter 重新
            // emit 同 URL) 才能放行重载
            loadedUrl = null
            val retried = screenModel.shared.retryOnPlayError(
                seekPositionMs = player.currentPosition.coerceAtLeast(0L),
            )
            if (retried) return
            val sourceType = error is ExoPlaybackException &&
                error.type == ExoPlaybackException.TYPE_SOURCE
            // 由书进入的口径逐字不变: 只把源类错误摆到页面上 (非源错误多为可自愈抖动,
            // 原版只写日志)。直投必须全报: retryOnPlayError 在直投恒 false, 什么错都到不了
            // “重新装载”, 不报就是一圈永不落地的转圈 + 连「重新加载」入口都没有
            if (!sourceType && !screenModel.shared.isDirect) return
            val message = if (!sourceType) {
                "视频播放出错"
            } else {
                when ((error as ExoPlaybackException).sourceException) {
                    is UnrecognizedInputFormatException -> "不是视频链接"
                    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> "视频地址不可用"
                    else -> "视频播放出错"
                }
            }
            AppLog.put(message, error, true)
            // 重试后仍失败: 上报 UI 显示错误 (原版只写日志, 用户要求"及时表现出来")
            screenModel.dispatch(VideoPlayUiEvent.ShowError(message))
        }
    }

    fun bind(model: VideoPlayScreenModel) {
        screenModel = model
        if (!bound) {
            bound = true
            player.addListener(listener)
            // 绑定时回放一次初始态 (下沉前原版 setCurrentPlayer 加完监听就直接写了四值,
            // 下沉时只留下了「变化沿」): 监听器只报变更, 转场窗口里播放器若已在播/已 ENDED,
            // 新快照流会停在初值 —— UI 表现为缓冲圈永转 / 丢 ENDED 不自动下一章
            publishPlayback()
        }
    }

    fun unbind() {
        if (bound) {
            player.removeListener(listener)
            bound = false
        }
    }

    fun updateSource(analyzeUrl: AnalyzeUrlCore) {
        // 同 URL 已在播则跳过: 转场结束 RenderSurface 重新组合后 collect 补发同值,
        // 不重复 setMediaItem (会清播放列表并把位置重置为 0)
        if (analyzeUrl.url == loadedUrl) return
        // 换源前先记用户意图 (必须在写 loadedUrl 之前取): 对照原版 switchResolution 的
        // `if (oldPlayer?.isPlaying ?: false) newPlayer.play()` —— 用户手动暂停后换清晰度,
        // 不得被强制播回去; 原版读 isPlaying 会把「暂停中/缓冲中」误判成非播放意图, 这里读
        // playWhenReady (意图位)。无媒体 (= 首次装载 / 刚 stop()) 则照旧自动起播。
        val keepPaused = loadedUrl != null
                && player.playbackState != Player.STATE_IDLE
                && !player.playWhenReady
        loadedUrl = analyzeUrl.url
        // 直链判定用 shared 的同一份判据 (http/https/file/content): 上一版只判 http 开头,
        // 外部投来的本地视频 (file:// 与 content://) 会掉进下面的内存 m3u8 清单分支 ——
        // 拿一条文件 URI 去建 HlsMediaSource, 观感就是进页黑屏报错。
        if (analyzeUrl.url.hasPlayableScheme()) {
            player.setMediaItem(
                ExoPlayerHelper.createMediaItem(
                    analyzeUrl.url,
                    analyzeUrl.headerMap
                )
            )
        } else {
            val fakeUrl = analyzeUrl.headerMap["Referer"]
            val dataSourceFactory = DataSource.Factory {
                object : DataSource {
                    private val http = ExoPlayerHelper.okhttpDataFactory.createDataSource()
                    private val memory =
                        ByteArrayDataSource(analyzeUrl.url.toByteArray(Charsets.UTF_8))
                    private var memoryData = false
                    private val active get() = if (memoryData) memory else http
                    override fun addTransferListener(transferListener: TransferListener) {
                        http.addTransferListener(transferListener)
                        memory.addTransferListener(transferListener)
                    }

                    override fun open(dataSpec: DataSpec): Long {
                        memoryData = dataSpec.uri == fakeUrl?.toUri()
                        return active.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        active.read(buffer, offset, length)

                    override fun getUri(): Uri? = active.uri
                    override fun close() = active.close()
                }
            }
            player.setMediaSource(
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                    MediaItem.Builder().setUri(fakeUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                )
            )
        }
        player.prepare()
        // 本次装载的起播位置: 与 videoUrl 同拍下发 (见 VideoPlayViewModelShared.startPositionMs),
        // 首次进页恢复已存进度 / 书签定位 / 刷新续播 / 切清晰度都靠它 (对照原版 refreshPlayer 的
        // `if (viewModel.position != 0L) seekTo(viewModel.position)`); >0 时也照常起播
        val startPositionMs = screenModel.shared.startPositionMs.value
        if (startPositionMs > 0L) player.seekTo(startPositionMs)
        if (!keepPaused) player.play()
        publishPlayback()
    }

    /**
     * 停止并卸载当前媒体 (共享层 videoUrl 置 null = 章节重新加载中/刷新, 见
     * [VideoPlayerController.stop]): 不显式停的话切章时上一章画面与声音会一直响到新章解析完。
     * 必须同步清 [loadedUrl] 守卫, 否则同链接重试会被当成「已在播」跳过。
     */
    override fun stop() {
        loadedUrl = null
        player.pause()
        player.clearMediaItems()
        publishPlayback()
    }

    /**
     * 按当前地址与请求头重装一次 (外部直投错误遮罩的「重新加载」, 见
     * [VideoPlayerController.reload])。
     *
     * 不靠重发 StateFlow: 渲染层订阅的就是这条地址, 同值不发射、collect 也不会重跑。
     * 这里复用 [stop] (清 [loadedUrl] 守卫 + 卸媒体), 再用**同一个** AnalyzeUrlCore 重走
     * [updateSource]: 直链与内存 m3u8 两条分支按原地址形态自然分流, 起播位置仍读
     * startPositionMs (ScreenModel 在 reload 前已写好续播点)。
     * 守卫不清的话 [updateSource] 第一行的同址判定会把重试直接吃成空操作。
     */
    override fun reload() {
        val source = screenModel.shared.videoUrl.value
        stop()
        if (source == null) return
        updateSource(source)
    }

    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    override val durationMs: Long get() = player.duration.coerceAtLeast(0L)
    override val bufferedMs: Long get() = player.bufferedPosition.coerceAtLeast(0L)
    override fun playPause() {
        if (player.isPlaying) player.pause()
        else if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition()
            player.play()
        } else player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = player.seekTo((positionMs + deltaMs).coerceAtLeast(0L))
    override fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed, player.playbackParameters.pitch)
    }

    override fun seekBack() = player.seekBack()
    override fun seekForward() = player.seekForward()
    override fun release() {
        unbind()
        player.release()
    }
}
