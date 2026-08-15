package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import io.legado.app.utils.toggleSystemBar

class AndroidVideoPlayPlatformProvider(
    private val activity: MainActivity,
) : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = AndroidVideoPlayerController(activity, screenModel, onPlaybackEnded)

    // media3 UnstableApi: PlayerView 控制接口 (setShowBuffering/resizeMode 等)。
    // 用 androidx.annotation.OptIn (lint UnsafeOptInUsageError 只认此形式, kotlin.OptIn 不被识别)
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
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
        // 竖屏恢复; 初次组合即按当前方向同步一次 (含直接以横屏进入的场景)。
        // 仅"手机"(最短边 < DesignTokens.wideScreenMinWidth) 生效: 平板横屏不自动全屏,
        // 交给 shared 宽边判定显示 视频 + 选集网格 (平板横屏原先被无条件拽进全屏, 列表消失)
        val config = LocalConfiguration.current
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 用 LocalWindowInfo.containerSize (px→dp) 替代 config.screenWidthDp/HeightDp:
        // 后者在不同 targetSdk 下 insets 行为不一致且取整, 不适合做宽边判定
        val windowSize = LocalWindowInfo.current.containerSize
        val isPhone = with(LocalDensity.current) {
            minOf(windowSize.width, windowSize.height).toDp() < DesignTokens.wideScreenMinWidth
        }
        LaunchedEffect(isLandscape, isPhone) {
            screenModel.setFullScreen(isLandscape && isPhone)
        }
        // 手势反馈文字(原 tv_video_speed, 键盘长按倍速与触摸手势共用 ScreenModel flow), null 时隐藏
        val gestureText by screenModel.gestureText.collectAsState()
        // 锁定态: 旁路全部手势并隐藏控制层, 仅留解锁钮 (对照 app 端 isLocked)
        var locked by remember { mutableStateOf(false) }
        // 进度回显: 控制层可见时 500ms 轮询 + 5s 自动隐藏 (shared 统一, 缓冲中也计时)
        var positionMs by remember { mutableLongStateOf(0L) }
        var bufferedMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var seeking by remember { mutableStateOf(false) }
        // 自动隐藏条件: 播放/缓冲中计时 (用户拍板: 缓冲中也自动隐藏), 拖动 seek 时暂停
        val playingOrBuffering = uiState.isPlaying ||
            (uiState.playWhenReady && uiState.playbackState == Player.STATE_BUFFERING)
        VideoPlaybackPoller(
            controlsVisible = uiState.controlsVisible,
            autoHideActive = playingOrBuffering,
            seeking = seeking,
            locked = locked,
            onAutoHide = screenModel::onToggleControls,
            poll = {
                positionMs = androidController.positionMs
                bufferedMs = androidController.bufferedMs
                durationMs = androidController.durationMs
            },
        )
        // 播放器层错误 (重试后仍失败): 显示错误占位 + 重试 (对齐 desktop playError/MediampFailedHint)
        var playError by remember { mutableStateOf<String?>(null) }
        DisposableEffect(androidController) {
            androidController.onError = { playError = it }
            onDispose { androidController.onError = null }
        }
        val error = uiState.error
        // 加载中 (章节内容拉取/解析): 整层 LoadingOverlay, 控制层不叠
        val showLoading = error == null && playError == null && uiState.loading
        // 播放缓冲中 (URL 就绪后): 小缓冲圈 (原 show_buffering=when_playing), 控制层可叠
        val showBuffering = error == null && playError == null && !showLoading &&
            uiState.playWhenReady && uiState.playbackState == Player.STATE_BUFFERING
        // 手势处理 (shared VideoGestureController): 单击切控制层/双击播放暂停/长按 2x 倍速/
        // 左半竖滑亮度/右半竖滑音量/横滑进度 (仅平台读写槽注入)
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val gestureController = remember(androidController, audioManager, maxVolume) {
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
            // 手势层 (shared 统一: 单击/双击/长按 + 滑动/抬手, 锁定态旁路)
            VideoGestureOverlay(
                handler = gestureController,
                locked = locked,
                modifier = Modifier.fillMaxSize(),
            )
            // 加载/错误占位: 播放器是否就绪只有平台层知道, 由本方法自出 (对照 provider 契约)
            if (error != null) {
                ErrorOverlay(error = error, onRetry = screenModel::onRefreshChapter)
            } else if (playError != null) {
                ErrorOverlay(
                    error = playError ?: "",
                    onRetry = {
                        playError = null
                        screenModel.shared.videoUrl.value?.let {
                            androidController.updateSource(it)
                        }
                    },
                )
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
                    // 锁定钮 (对照 app VideoLockToggle): 锁定后隐藏控制层 (shared 统一组件)
                    leadingContent = {
                        VideoLockToggle(
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
            // 锁定态: 仅留半透明小锁钮, 点击解锁 (对照 app VideoLockToggle; shared 统一组件)
            if (locked) {
                VideoLockToggle(
                    locked = true,
                    onClick = { locked = false },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                )
            }
            // 缓冲圈 (原 show_buffering=when_playing, 叠于控制层之上; shared 统一组件)
            if (showBuffering) {
                VideoBufferingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // 手势反馈文字(原 tv_video_speed, 叠于控制层之上; shared 统一组件)
            gestureText?.let {
                VideoGestureFeedbackText(
                    text = it,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
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


/** 锁定/解锁钮已收拢为 shared [VideoLockToggle] (见 VideoPlayerScreenContent.kt)。 */

@SuppressLint("UnsafeOptInUsageError")
private class AndroidVideoPlayerController(
    activity: MainActivity,
    private var screenModel: VideoPlayScreenModel,
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {
    val player: ExoPlayer = ExoPlayerHelper.createHttpExoPlayer(activity)
    private var bound = false

    /** 播放器层错误回调 (重试后仍失败时上报 UI, 对齐 desktop MediampVideoPlayerController.onError) */
    var onError: ((String) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            screenModel.onPlayerState(isPlaying = isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            screenModel.onPlayerState(playWhenReady = playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            screenModel.onPlayerState(playbackState = playbackState)
            // 播放真正成功 (READY) 才重置错误重试标记 (对齐原版 VideoPlayActivity
            // onPlaybackStateChanged: STATE_READY → hasRefreshedOnPlayError = false;
            // 链接不可用时永不 READY, 同章节只自动重试一次, 不再无限循环)
            if (playbackState == Player.STATE_READY) {
                screenModel.shared.resetRetryOnPlayError()
            }
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
                // 重试后仍失败: 上报 UI 显示错误 (原版只写日志, 用户要求"及时表现出来")
                onError?.invoke(message)
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
