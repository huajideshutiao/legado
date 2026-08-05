package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.SimpleDateFormat
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
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
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toggleSystemBar
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

class AndroidVideoPlayPlatformProvider(
    private val activity: MainActivity,
) : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = AndroidVideoPlayerController(activity, screenModel, onPlaybackEnded)

    @Composable
    override fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val androidController = controller as AndroidVideoPlayerController
        val uiState by screenModel.state.collectAsState()
        // 横屏自动进入全屏 (对照原版 VideoPlayActivity.onConfigurationChanged → setFullScreen(isFull)):
        // 横屏隐藏系统栏/标题栏/选集网格 (setFullScreen → applyFullscreen → toggleSystemBar(!enabled)),
        // 竖屏恢复; 初次组合即按当前方向同步一次 (含直接以横屏进入的场景)
        val isLandscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        LaunchedEffect(isLandscape) {
            screenModel.setFullScreen(isLandscape)
        }
        // 手势反馈文字(原 tv_video_speed), null 时隐藏
        var gestureText by remember { mutableStateOf<String?>(null) }
        // 锁定态: 旁路全部手势并隐藏控制层, 仅留解锁钮 (对照 app 端 isLocked)
        var locked by remember { mutableStateOf(false) }
        // 进度回显: 控制层可见时 500ms 轮询 (对照 app 原 AppVideoControlsHost + desktop 轮询)
        var positionMs by remember { mutableLongStateOf(0L) }
        var bufferedMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var seeking by remember { mutableStateOf(false) }
        LaunchedEffect(androidController, uiState.controlsVisible) {
            while (uiState.controlsVisible) {
                positionMs = androidController.positionMs
                bufferedMs = androidController.bufferedMs
                durationMs = androidController.durationMs
                delay(500)
            }
        }
        // 自动隐藏 (原 controllerShowTimeoutMs=5s, 仅播放/缓冲中计时, 拖动 seek 时暂停)
        val playingOrBuffering = uiState.isPlaying ||
            (uiState.playWhenReady && uiState.playbackState == Player.STATE_BUFFERING)
        LaunchedEffect(uiState.controlsVisible, playingOrBuffering, seeking, locked) {
            if (uiState.controlsVisible && playingOrBuffering && !seeking && !locked) {
                delay(5000)
                screenModel.onToggleControls()
            }
        }
        val error = uiState.error
        // 加载中 (章节内容拉取/解析): 整层 LoadingOverlay, 控制层不叠
        val showLoading = error == null && uiState.loading
        // 播放缓冲中 (URL 就绪后): 小缓冲圈 (原 show_buffering=when_playing), 控制层可叠
        val showBuffering = error == null && !showLoading &&
            uiState.playWhenReady && uiState.playbackState == Player.STATE_BUFFERING
        // 手势处理(对照 app VideoGestureHandler): 单击切控制层/双击播放暂停/长按 2x 倍速/
        // 左半竖滑亮度/右半竖滑音量/横滑进度
        val gestureHandler = remember(androidController) {
            AndroidVideoGestureHandler(
                activity = activity,
                player = androidController.player,
                onToggleControls = screenModel::onToggleControls,
                onGestureText = { gestureText = it },
            )
        }
        Box(modifier.fillMaxSize()) {
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
                modifier = Modifier.fillMaxSize(),
            )
            // 手势层 (对照 app VideoGestureOverlay): 锁定态旁路
            AndroidVideoGestureOverlay(
                handler = gestureHandler,
                locked = locked,
                modifier = Modifier.fillMaxSize(),
            )
            // 加载/错误占位: 播放器是否就绪只有平台层知道, 由本方法自出 (对照 provider 契约)
            if (error != null) {
                ErrorOverlay(error = error, onRetry = screenModel::onRefreshChapter)
            } else if (showLoading) {
                LoadingOverlay()
            }
            // 控制层 (加载/错误态不叠; 锁定态隐藏, 仅留解锁钮)
            if (!locked) {
                VideoControlsOverlay(
                    visible = uiState.controlsVisible && error == null && !showLoading,
                    isPlaying = uiState.isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    playbackSpeed = uiState.playbackSpeed,
                    hasMultiResolution = uiState.hasMultiResolution,
                    resolutions = uiState.resolutions,
                    currentResolutionIndex = uiState.currentResolutionIndex,
                    onPlayPause = screenModel::onPlayPause,
                    onSeek = screenModel::onSeekTo,
                    onSpeedChange = screenModel::onSpeedChange,
                    onSwitchResolution = screenModel::onSwitchResolution,
                    onSeekDragStateChange = { seeking = it },
                    // 中央控制行 (对照 app CenterControls): 上一集/后退/播放暂停/前进/下一集
                    centerControls = {
                        VideoCenterControls(
                            isPlaying = uiState.isPlaying,
                            onPrev = screenModel::onPrevChapter,
                            onSeekBack = screenModel::onSeekBack,
                            onPlayPause = screenModel::onPlayPause,
                            onSeekForward = screenModel::onSeekForward,
                            onNext = screenModel::onNextChapter,
                            rewindPainter = painterResource(androidx.media3.ui.R.drawable.exo_ic_rewind),
                            forwardPainter = painterResource(androidx.media3.ui.R.drawable.exo_ic_forward),
                            rewindDesc = stringResource(androidx.media3.ui.R.string.exo_controls_rewind_description),
                            forwardDesc = stringResource(androidx.media3.ui.R.string.exo_controls_fastforward_description),
                            enabledPrev = uiState.curChapterIndex > 0,
                            enabledNext = uiState.curChapterIndex < uiState.chapterSize - 1,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    },
                    // 锁定钮 (对照 app VideoLockToggle): 锁定后隐藏控制层
                    leadingContent = {
                        AndroidVideoLockToggle(
                            locked = false,
                            onClick = {
                                locked = true
                                screenModel.onToggleControls()
                            },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp),
                        )
                    },
                    // 全屏钮 (对照 app 端 toggleOrientationFullscreen)
                    trailingBottomContent = {
                        val isLandscape = LocalConfiguration.current.orientation ==
                            Configuration.ORIENTATION_LANDSCAPE
                        IconButton(onClick = { screenModel.onToggleOrientationFullscreen() }) {
                            Icon(
                                painter = painterResource(
                                    if (isLandscape) {
                                        androidx.media3.ui.R.drawable.exo_ic_fullscreen_exit
                                    } else {
                                        androidx.media3.ui.R.drawable.exo_ic_fullscreen_enter
                                    }
                                ),
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 锁定态: 仅留半透明小锁钮, 点击解锁 (对照 app VideoLockToggle)
            if (locked) {
                AndroidVideoLockToggle(
                    locked = true,
                    onClick = { locked = false },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                )
            }
            // 缓冲圈 (原 show_buffering=when_playing, 叠于控制层之上)
            if (showBuffering) {
                CircularProgressIndicator(
                    color = AppTheme.colors.accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                )
            }
            // 手势反馈文字(原 tv_video_speed, 叠于控制层之上)
            gestureText?.let {
                Text(
                    text = it,
                    color = AppTheme.colors.primaryText,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(
                            colorResource(io.legado.app.R.color.arco_fill_3),
                            DesignTokens.shapeDefault
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        LaunchedEffect(androidController, screenModel) {
            screenModel.shared.videoUrl.collect { source ->
                source?.let(androidController::updateSource)
            }
        }
        DisposableEffect(androidController, screenModel) {
            androidController.bind(screenModel)
            onDispose { androidController.unbind() }
        }
    }

    override fun applyFullscreen(enabled: Boolean) {
        activity.toggleSystemBar(!enabled)
    }

    override fun toggleOrientation() {
        activity.requestedOrientation =
            if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }
}

private enum class AndroidGestureMode { NONE, PROGRESS, BRIGHTNESS, VOLUME }

/**
 * 手势逻辑 (逐项对照 app VideoPlayActivity.VideoGestureHandler / origin VideoGestureListener)。
 *
 * 单击切控制层 / 双击播放暂停 / 长按 2x 倍速 (松手恢复) /
 * 左半屏竖滑亮度 / 右半屏竖滑音量 / 横滑进度 (松手 seek)。
 *
 * 竖滑相对调节复用共享状态机 [VideoGestureAdjuster] (进模式读当前值 + 增量 +
 * 碰顶/底重置, 与原版逐行等价): 音量走整数步进 (step=1f, 对照原版 .toInt() 截断
 * 语义), 亮度走连续浮点。
 */
private class AndroidVideoGestureHandler(
    private val activity: MainActivity,
    private val player: ExoPlayer,
    private val onToggleControls: () -> Unit,
    private val onGestureText: (String?) -> Unit,
) {
    private var originalSpeed = 1f
    var speedBoosted = false
        private set
    private var position = 0L
    private val screenWidth get() = Resources.getSystem().displayMetrics.widthPixels
    private val screenHeight = 350.dpToPx()
    private var gestureMode = AndroidGestureMode.NONE
    private var startX = 0f
    private var startY = 0f
    private val deadZoneSize by lazy { 15f.dpToPx() }
    private val audioManager by lazy { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val maxVolume by lazy { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    private val brightnessAdjuster by lazy { VideoGestureAdjuster() }
    private val volumeAdjuster by lazy { VideoGestureAdjuster(max = maxVolume.toFloat()) }
    private var lastScrollTime = 0L
    private val scrollThrottleInterval = 32L //ms
    private val progressTimeFormat by lazy {
        SimpleDateFormat("mm:ss", Locale.getDefault())
    }

    fun onDoubleTap() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun onSingleTap() {
        onToggleControls()
    }

    fun onDown(x: Float, y: Float) {
        startX = x
        startY = y
    }

    fun onLongPress() {
        originalSpeed = player.playbackParameters.speed
        speedBoosted = true
        val targetSpeed = originalSpeed * 2f
        player.playbackParameters = PlaybackParameters(targetSpeed, player.playbackParameters.pitch)
        onGestureText(String.format(Locale.getDefault(), "%.1fX", targetSpeed))
    }

    fun onScroll(x: Float, y: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScrollTime < scrollThrottleInterval) {
            return
        }
        lastScrollTime = currentTime
        if (gestureMode == AndroidGestureMode.NONE) {
            val deltaX = abs(x - startX)
            val deltaY = abs(y - startY)

            if (deltaX < deadZoneSize && deltaY < deadZoneSize) return

            gestureMode = when {
                deltaX > deltaY -> AndroidGestureMode.PROGRESS
                startX < screenWidth / 2 -> {
                    // 进模式读当前亮度 (系统默认 <=0 按 0, 对照原版)
                    brightnessAdjuster.onGestureStart(startY) {
                        if (activity.window.attributes.screenBrightness <= 0f) 0f
                        else activity.window.attributes.screenBrightness
                    }
                    AndroidGestureMode.BRIGHTNESS
                }

                else -> {
                    // 进模式读当前音量 (0..maxVolume, 对照原版 getStreamVolume)
                    volumeAdjuster.onGestureStart(startY) {
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    }
                    AndroidGestureMode.VOLUME
                }
            }
        }
        when (gestureMode) {
            AndroidGestureMode.PROGRESS -> {
                position = (player.currentPosition + (x - startX) / screenWidth * 180000).toLong()
                    .coerceIn(0, player.duration)
                onGestureText(
                    String.format(
                        "%s/%s",
                        progressTimeFormat.format(position),
                        progressTimeFormat.format(player.duration)
                    )
                )
            }

            AndroidGestureMode.BRIGHTNESS -> {
                // 相对调节 + 碰顶/底重置 (共享状态机, 逐行对照原版亮度段)
                val deltaBrightness = brightnessAdjuster.onGestureMove(y, screenHeight)
                activity.window.attributes = activity.window.attributes.apply {
                    screenBrightness = deltaBrightness
                }
                onGestureText(
                    String.format(
                        Locale.getDefault(),
                        "亮度: %d%%",
                        (deltaBrightness * 100).toInt()
                    )
                )
            }

            AndroidGestureMode.VOLUME -> {
                // step=1f: AudioManager 整数步进, 对照原版 .toInt().coerceIn(0, maxVolume)
                // 的截断 + 边界判定语义 (状态机内部碰顶/底重置, 逐行等价)
                val deltaVolume = volumeAdjuster.onGestureMove(y, screenHeight, step = 1f).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, deltaVolume, 0)
                onGestureText(
                    String.format(Locale.getDefault(), "音量: %d%%", deltaVolume * 100 / maxVolume)
                )
            }

            AndroidGestureMode.NONE -> {}
        }
    }

    fun onUp() {
        if (speedBoosted) {
            player.playbackParameters =
                PlaybackParameters(originalSpeed, player.playbackParameters.pitch)
            speedBoosted = false
        }
        when (gestureMode) {
            AndroidGestureMode.PROGRESS -> {
                player.seekTo(position)
                player.play()
            }

            else -> {}
        }
        gestureMode = AndroidGestureMode.NONE
        onGestureText(null)
    }
}

/** 手势层 (对照 app VideoGestureOverlay): 单击/双击/长按 + 滑动/抬手, 锁定态旁路。 */
@Composable
private fun AndroidVideoGestureOverlay(
    handler: AndroidVideoGestureHandler,
    locked: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier
            .pointerInput(handler, locked) {
                if (locked) return@pointerInput
                detectTapGestures(
                    onTap = { handler.onSingleTap() },
                    onDoubleTap = { handler.onDoubleTap() },
                    onLongPress = { handler.onLongPress() },
                )
            }
            .pointerInput(handler, locked) {
                if (locked) return@pointerInput
                // 滑动/抬手: 越过 slop 才消费(同时打断 tap 层); 长按倍速期间不响应滑动
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    handler.onDown(down.position.x, down.position.y)
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break
                            if (handler.speedBoosted) continue
                            if (!dragging) {
                                val slop = viewConfiguration.touchSlop
                                val delta = change.position - down.position
                                dragging = abs(delta.x) > slop || abs(delta.y) > slop
                            }
                            if (dragging) {
                                change.consume()
                                handler.onScroll(change.position.x, change.position.y)
                            }
                        }
                    } finally {
                        handler.onUp()
                    }
                }
            }
    )
}

/** 锁定/解锁钮 (对照 app VideoLockToggle): 复用锁矢量, 白 tint 同其余控制钮。 */
@Composable
private fun AndroidVideoLockToggle(
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter("ic_lock_outline"),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (locked) 0.5f else 1f),
            modifier = Modifier.size(32.dp),
        )
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

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            screenModel.onPlayerState(isPlaying = isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            screenModel.onPlayerState(playWhenReady = playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            screenModel.onPlayerState(playbackState = playbackState)
            if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            screenModel.onPlayerState(playbackSpeed = playbackParameters.speed)
        }

        override fun onPlayerError(error: PlaybackException) {
            val retried = screenModel.shared.retryOnPlayError()
            if (!retried && error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_SOURCE) {
                val message = when (error.sourceException) {
                    is UnrecognizedInputFormatException -> "不是视频链接"
                    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> "视频地址不可用"
                    else -> "视频播放出错"
                }
                AppLog.put(message, error, true)
            }
        }
    }

    fun bind(model: VideoPlayScreenModel) {
        screenModel = model
        if (!bound) {
            bound = true
            player.addListener(listener)
        }
    }

    fun unbind() {
        if (bound) {
            player.removeListener(listener)
            bound = false
        }
    }

    fun updateSource(analyzeUrl: AnalyzeUrlCore) {
        if (analyzeUrl.url.startsWith("http")) {
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
        player.play()
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
