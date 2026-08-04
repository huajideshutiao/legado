package io.legado.desktop.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.video.ErrorOverlay
import io.legado.app.ui.book.video.LoadingOverlay
import io.legado.app.ui.book.video.PlatformPlayer
import io.legado.app.ui.book.video.VideoCenterControls
import io.legado.app.ui.book.video.VideoControlsOverlay
import io.legado.app.ui.book.video.VideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayerController
import io.legado.app.ui.book.video.toDurationTime
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.desktop.ui.DesktopWindowHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackState
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePause
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * desktop 端 [VideoPlayPlatformProvider] 实现: open-ani/mediamp (mediamp-mpv 后端)。
 *
 * # 架构 (替代自研 libmpv 直通渲染 / mpv.exe 外部进程, 2026-08 迁移)
 *
 * - **渲染**: mediamp 内部 libmpv render API → 独立 producer GL/D3D11 上下文 →
 *   共享纹理环 → Skia 零拷贝引用 (Windows: D3D11→Skia D3D12 共享纹理; Linux: GLX
 *   share group; macOS: Metal)。视频区是普通 Compose 层 ([MediampPlayerSurface]),
 *   控制层/弹层自由叠加, 无 airspace 问题; mpv 的 GL 调用发生在独立 producer
 *   上下文, 不污染 Skia 状态缓存 (自研直通方案的 Intel 驱动 flush 崩溃根因消除)。
 * - **runtime**: mpv natives 由显式声明的 runtimeOnly 依赖提供 (mediamp-mpv-runtime 聚合
 *   工件, 见 desktop/build.gradle.kts), 首次创建 MPVHandle 时自动从 classpath jar 解包到
 *   临时目录加载, 无需用户安装 mpv, 无探测/下载/进程管理代码。
 *   (启动期已由 Main.kt 后台预解包, 避免首播时同步解包 20MB+ DLL 硬卡顿)
 * - **防盗链**: [UriMediaData] 原生携带 headers (User-Agent/Referer/http-header-fields)。
 * - **状态**: playbackState (READY/PLAYING/PAUSED/PAUSED_BUFFERING/ERROR/FINISHED)
 *   + currentPositionMillis + mediaProperties.durationMillis 驱动控制层。
 * - **交互** (对标 app 端 VideoGestureHandler): 鼠标单击切控制层 / 双击播放暂停 /
 *   长按 2x 倍速 (松手恢复) / 横滑进度 (松手 seek) / 竖滑音量; 键盘 Space 播放暂停,
 *   ←/→ 点按调进度 10s, 长按 → (仅右方向键) 2x 倍速
 *   (见 [DesktopVideoGestureHandler]/[DesktopVideoKeyHandler])。
 *
 * # 生命周期
 *
 * [createController] 同步创建 [MediampPlayer] (SPI 工厂, 首次含 native 库解包加载;
 * 已由启动期异步预解包, 见 Main.kt);
 * 创建失败返回 [FailedMediampController] 走错误占位, 不崩 UI。
 * 切章/切分辨率 = videoUrl 变化 → 重新 setMediaData (同一 player, 无进程重启)。
 */
class MediampVideoPlayPlatformProvider(
    private val windowHandle: DesktopWindowHandle = DesktopWindowHandle(),
) : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController {
        return try {
            MediampVideoPlayerController(onPlaybackEnded).also {
                AppLog.put("视频播放: mediamp-mpv 后端")
            }
        } catch (e: Throwable) {
            AppLog.put("mediamp 初始化失败: ${e.message}", e)
            FailedMediampController("mediamp 初始化失败: ${e.message}")
        }
    }

    // 系统级全屏: 真正的完全全屏, 隐藏系统底栏/窗口装饰 (GraphicsDevice.setFullScreenWindow)
    // 与 applyFullscreen (右上角菜单的窗口内全屏) 区分
    override fun applySystemFullScreen(enabled: Boolean) {
        val window = windowHandle.window ?: return
        val device = window.graphicsConfiguration?.device ?: return
        device.setFullScreenWindow(if (enabled) window else null)
    }

    // mediamp 是 Compose 层渲染 (无原生子窗口), 无 airspace 遮挡问题, 直接跳过
    override fun setOverlayVisible(visible: Boolean) = Unit

    @Composable
    override fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        when (controller) {
            is MediampVideoPlayerController -> MediampRender(controller, screenModel, modifier)
            is FailedMediampController -> FailedMediampRender(controller, screenModel, modifier)
            else -> Unit
        }
    }
}

/**
 * mediamp 播放控制器: 包装 [MediampPlayer], 桥接 [VideoPlayerController] 接口。
 *
 * - playPause → togglePause; seekTo/skip 直调; setSpeed → features[PlaybackSpeed]
 * - positionMs/durationMs 读 mediamp StateFlow 最新值
 * - 播完 (FINISHED) → onPlaybackEnded; 播放错误 (ERROR/异常) → onError (UI 占位)
 * - startPlayback: setMediaData(UriMediaData(url, headers)) 后 resume + seek 恢复进度
 */
class MediampVideoPlayerController(
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** mediamp player (SPI 工厂经 classpath 上的 mediamp-mpv 创建; 构造抛异常由调用方兜底) */
    val player: MediampPlayer = MediampPlayer(Unit, scope.coroutineContext)

    /** 播放/初始化失败回传 UI (由 Render 注入), 让失败可见而不是只落日志 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /** 缓冲中 (playbackState == PAUSED_BUFFERING) */
    val isBuffering: Boolean
        get() = player.playbackState.value == PlaybackState.PAUSED_BUFFERING

    /** 播放中 (playbackState == PLAYING) */
    val isPlaying: Boolean
        get() = player.playbackState.value == PlaybackState.PLAYING

    init {
        scope.launch {
            player.playbackState.collect { state ->
                when (state) {
                    PlaybackState.FINISHED -> onPlaybackEnded()
                    PlaybackState.ERROR -> onError?.invoke("播放失败")
                    else -> Unit
                }
            }
        }
    }

    /** 加载播放 (切章/切分辨率统一入口); setMediaData 完成后自动播放 + 恢复进度 */
    fun startPlayback(url: String, headers: Map<String, String>, startMs: Long) {
        scope.launch {
            runCatching {
                player.setMediaData(UriMediaData(url, headers))
                player.resume()
                if (startMs > 0) player.seekTo(startMs)
            }.onFailure { e ->
                AppLog.put("mediamp 加载失败: ${e.message}", e)
                onError?.invoke("加载失败: ${e.message}")
            }
        }
    }

    override val positionMs: Long get() = player.currentPositionMillis.value
    override val durationMs: Long get() = player.mediaProperties.value?.durationMillis ?: 0L
    override val bufferedMs: Long get() = durationMs

    override fun playPause() = player.togglePause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = player.skip(deltaMs)
    override fun setSpeed(speed: Float) {
        runCatching { player.features[PlaybackSpeed.Key]?.set(speed) }
            .onFailure { AppLog.putDebug("mediamp 倍速设置失败: ${it.message}") }
    }

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        runCatching { player.close() }
        scope.cancel()
    }
}

/** mediamp 创建失败时的占位控制器 (不崩 UI, Render 出错误占位) */
class FailedMediampController(
    val message: String,
) : VideoPlayerController {
    override val positionMs: Long get() = 0L
    override val durationMs: Long get() = 0L
    override val bufferedMs: Long get() = 0L
    override fun playPause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(deltaMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun seekBack() = Unit
    override fun seekForward() = Unit
    override fun release() = Unit
}

/**
 * mediamp 渲染 + 控制层: [MediampPlayerSurface] 渲染视频区, 控制层/占位全部
 * Compose 叠加 (mediamp 零拷贝纹理环, 无原生子窗口)。
 */
@Composable
private fun MediampRender(
    controller: MediampVideoPlayerController,
    screenModel: VideoPlayScreenModel,
    modifier: Modifier,
) {
    // 必须以 State 订阅: 直读 StateFlow.value 不会随链接就绪重组 (对照进程路径 64711ebf22)
    val videoUrl by screenModel.shared.videoUrl.collectAsState()
    val uiState by screenModel.state.collectAsState()
    val url = videoUrl?.url
    val headers = videoUrl?.headerMap ?: emptyMap()
    val startMs = screenModel.shared.curBook?.durChapterPos?.toLong() ?: 0L

    val scope = rememberCoroutineScope()
    var playError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    // 播放状态订阅 (mediamp StateFlow, 驱动播放态/缓冲圈/错误)
    val playbackState by controller.player.playbackState.collectAsState()
    val isPlaying = playbackState == PlaybackState.PLAYING
    val isBuffering = playbackState == PlaybackState.PAUSED_BUFFERING

    // 播放/初始化失败回传 → 错误占位 (回调在任意线程, 经 scope 切回 composition 调度器)
    DisposableEffect(controller) {
        controller.onError = { msg -> scope.launch { playError = msg } }
        onDispose { controller.onError = null }
    }

    // 起播/重试/切章: 链接就绪且无错误 → setMediaData (retryKey 变化同样经此重起)
    LaunchedEffect(url, retryKey) {
        if (url != null && playError == null) {
            controller.startPlayback(url, headers, startMs)
        }
    }

    // 播放中进入 ERROR 状态 (非显式错误回调) → 错误占位
    LaunchedEffect(playbackState) {
        if (playbackState == PlaybackState.ERROR && playError == null) {
            playError = "播放失败"
        }
    }

    // ---- 控制层回显轮询 (控制层可见时 500ms; 对照 app 端 AppVideoControlsHost) ----
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.controlsVisible) {
        while (uiState.controlsVisible) {
            positionMs = controller.positionMs
            durationMs = controller.durationMs
            // 回显到共享 UiState (缓冲圈/自动隐藏依赖)
            screenModel.onPlayerState(
                isPlaying = isPlaying,
                playWhenReady = isPlaying || isBuffering,
                playbackState = if (isBuffering) {
                    PlatformPlayer.STATE_BUFFERING
                } else {
                    PlatformPlayer.STATE_READY
                },
            )
            delay(500)
        }
    }

    // 自动隐藏 (5s, 仅播放中计时; 拖动 seek 暂停; 锁定态不隐藏)
    var seeking by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.controlsVisible, isPlaying, seeking, locked) {
        if (uiState.controlsVisible && isPlaying && !seeking && !locked) {
            delay(5000)
            screenModel.onToggleControls()
        }
    }

    val error = uiState.error
    val showLoading = error == null && uiState.loading
    // 缓冲圈 (PAUSED_BUFFERING 驱动, 叠于控制层之上)
    val showBuffering = error == null && !showLoading && playError == null &&
        isBuffering && uiState.playWhenReady

    // 手势反馈文字 (对照 app tv_video_speed, null 时隐藏)
    var gestureText by remember { mutableStateOf<String?>(null) }

    // 鼠标手势 (对照 app AndroidVideoGestureHandler): 单击切控制层/双击播放暂停/
    // 长按 2x 倍速/横滑进度/竖滑音量 (桌面无亮度概念, 见类注释)
    val gestureHandler = remember(controller) {
        DesktopVideoGestureHandler(
            controller = controller,
            onToggleControls = screenModel::onToggleControls,
            onGestureText = { gestureText = it },
        )
    }
    // 键盘 (对照 app 手势语义): Space 播放暂停, ←/→ 点按调进度 10s, 长按 2x 倍速
    val keyHandler = remember(controller) {
        DesktopVideoKeyHandler(
            scope = scope,
            controller = controller,
            onSeekDelta = screenModel::onSeekDelta,
            onGestureText = { gestureText = it },
        )
    }
    // 键盘焦点: 进屏即抢焦点, 保证 Space/方向键直达本层
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            // 焦点丢失时清理按键长按态 (否则 KeyUp 收不到, 2x 倍速会卡住)
            .onFocusChanged { if (!it.isFocused) keyHandler.reset() }
            .onPreviewKeyEvent { keyHandler.onKeyEvent(it, enabled = !locked) },
    ) {
        // 视频渲染面 (mediamp 内部等比适配 letterbox)
        MediampPlayerSurface(
            mediampPlayer = controller.player,
            modifier = Modifier.fillMaxSize(),
        )
        // 手势层 (对照 app VideoGestureOverlay): 锁定态旁路
        DesktopVideoGestureOverlay(
            handler = gestureHandler,
            locked = locked,
            modifier = Modifier.fillMaxSize(),
        )

        // 加载/错误占位 (章节内容层面)
        if (error != null) {
            ErrorOverlay(error = error, onRetry = screenModel::onRefreshChapter)
        } else if (showLoading) {
            LoadingOverlay()
        }
        // 播放器层面失败占位
        playError?.let {
            MediampFailedHint(
                message = it,
                playUrl = url,
                onRetry = {
                    playError = null
                    retryKey++
                },
            )
        }
        // 控制层 (Compose 叠加)
        if (!locked && playError == null) {
            VideoControlsOverlay(
                visible = uiState.controlsVisible && error == null && !showLoading,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = durationMs,
                playbackSpeed = uiState.playbackSpeed,
                hasMultiResolution = uiState.hasMultiResolution,
                resolutions = uiState.resolutions,
                currentResolutionIndex = uiState.currentResolutionIndex,
                onPlayPause = screenModel::onPlayPause,
                onSeek = screenModel::onSeekTo,
                onSpeedChange = screenModel::onSpeedChange,
                onSwitchResolution = screenModel::onSwitchResolution,
                onSeekDragStateChange = { seeking = it },
                centerControls = {
                    VideoCenterControls(
                        isPlaying = isPlaying,
                        onPrev = screenModel::onPrevChapter,
                        onSeekBack = screenModel::onSeekBack,
                        onPlayPause = screenModel::onPlayPause,
                        onSeekForward = screenModel::onSeekForward,
                        onNext = screenModel::onNextChapter,
                        rewindDesc = "后退 10 秒",
                        forwardDesc = "前进 10 秒",
                        enabledPrev = uiState.curChapterIndex > 0,
                        enabledNext = uiState.curChapterIndex < uiState.chapterSize - 1,
                        modifier = Modifier.align(Alignment.Center),
                    )
                },
                leadingContent = {
                    DesktopVideoLockToggle(
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
                isSystemFullScreen = uiState.isSystemFullScreen,
                onToggleSystemFullScreen = screenModel::onToggleSystemFullScreen,
                showSystemFullScreenButton = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 锁定态: 仅留半透明小锁钮 (对照 app AndroidVideoLockToggle)
        if (locked && playError == null) {
            DesktopVideoLockToggle(
                locked = true,
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
            )
        }
        // 缓冲圈 (叠于控制层之上)
        if (showBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
        // 手势反馈文字 (对照 app tv_video_speed, 叠于所有层之上)
        gestureText?.let {
            Text(
                text = it,
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0x80000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** mediamp 创建失败占位 (无 player 可渲染, 直接错误提示) */
@Composable
private fun FailedMediampRender(
    controller: FailedMediampController,
    screenModel: VideoPlayScreenModel,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        MediampFailedHint(
            message = controller.message,
            playUrl = screenModel.shared.videoUrl.value?.url,
            onRetry = screenModel::onRefreshChapter,
        )
    }
}

private enum class DesktopGestureMode { NONE, PROGRESS, VOLUME }

/**
 * 鼠标手势 (逐项对照 app AndroidVideoGestureHandler)。
 *
 * 单击切控制层 / 双击播放暂停 / 长按 2x 倍速 (松手恢复) /
 * 横滑进度 (整宽映射 ±3 分钟, 松手 seek + 自动续播) / 竖滑音量 (0~100%)。
 *
 * 注: app 端左半竖滑亮度 / 右半竖滑音量, 桌面端无窗口亮度概念,
 * 竖滑统一映射 mediamp 播放器音量 ([AudioLevelController], mpv volume 属性)。
 */
private class DesktopVideoGestureHandler(
    private val controller: MediampVideoPlayerController,
    private val onToggleControls: () -> Unit,
    private val onGestureText: (String?) -> Unit,
) {
    /** 长按倍速中 (拖动层据此跳过滑动, 对照 app) */
    var speedBoosted = false
        private set
    private var originalSpeed = 1f
    private var position = 0L
    private var gestureMode = DesktopGestureMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var currentVolume = 0f
    private var lastScrollTime = 0L
    private val scrollThrottleInterval = 32L // ms, 对照 app
    private val volumeController by lazy { controller.player.features[AudioLevelController.Key] }

    fun onSingleTap() = onToggleControls()

    fun onDoubleTap() = controller.playPause()

    fun onDown(x: Float, y: Float) {
        startX = x
        startY = y
    }

    /** 长按 → 当前倍速 ×2 (对照 app onLongPress), 松手恢复 */
    fun onLongPress() {
        val current = runCatching {
            controller.player.features[PlaybackSpeed.Key]?.value ?: 1f
        }.getOrDefault(1f)
        originalSpeed = current
        speedBoosted = true
        val target = current * 2f
        controller.setSpeed(target)
        onGestureText("%.1fX".format(target))
    }

    fun onScroll(x: Float, y: Float, width: Float, height: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScrollTime < scrollThrottleInterval) {
            return
        }
        lastScrollTime = currentTime
        if (gestureMode == DesktopGestureMode.NONE) {
            val deltaX = abs(x - startX)
            val deltaY = abs(y - startY)
            // 越过 slop 才判方向 (拖动层已过滤, 这里只选主轴)
            if (deltaX < 4f && deltaY < 4f) return
            gestureMode =
                if (deltaX > deltaY) DesktopGestureMode.PROGRESS else DesktopGestureMode.VOLUME
        }
        when (gestureMode) {
            DesktopGestureMode.PROGRESS -> {
                // 对照 app: 整宽映射 ±3 分钟, 相对当前进度
                position = (controller.positionMs + (x - startX) / width * 180_000).toLong()
                    .coerceIn(0L, controller.durationMs)
                onGestureText(
                    "%s / %s".format(
                        position.toDurationTime(),
                        controller.durationMs.toDurationTime(),
                    )
                )
            }

            DesktopGestureMode.VOLUME -> {
                val volumeCtl = volumeController ?: return
                val level = (currentVolume + (startY - y) / height).coerceIn(0f, 1f)
                volumeCtl.setVolume(level)
                onGestureText("音量: %d%%".format((level * 100).toInt()))
            }

            DesktopGestureMode.NONE -> {}
        }
    }

    fun onUp() {
        if (speedBoosted) {
            controller.setSpeed(originalSpeed)
            speedBoosted = false
        }
        when (gestureMode) {
            DesktopGestureMode.PROGRESS -> {
                controller.seekTo(position)
                // 对照 app: seek 后自动续播 (拖动预览不改播放态)
                if (!controller.isPlaying) controller.playPause()
            }

            else -> {}
        }
        gestureMode = DesktopGestureMode.NONE
        onGestureText(null)
    }
}

/**
 * 手势层 (对照 app AndroidVideoGestureOverlay): 单击/双击/长按 + 滑动/抬手, 锁定态旁路。
 * 拖动越过 touchSlop 才消费事件 (同时打断 tap 层); 长按倍速期间不响应滑动 (对照 app)。
 */
@Composable
private fun DesktopVideoGestureOverlay(
    handler: DesktopVideoGestureHandler,
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
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
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
                                handler.onScroll(
                                    change.position.x,
                                    change.position.y,
                                    width,
                                    height
                                )
                            }
                        }
                    } finally {
                        handler.onUp()
                    }
                }
            }
    )
}

/** 键盘长按判定阈值 (ms), 对齐 app 端长按语义 (ViewConfiguration ~400ms) */
private const val KEY_LONG_PRESS_MILLIS = 400L

/**
 * 键盘控制 (对标 app 端手势语义):
 *
 * - **Space**: 播放/暂停
 * - **←/→ 点按** (长按阈值内松手): 后退/前进 10 秒
 * - **→ 长按** (按住超过 [KEY_LONG_PRESS_MILLIS] 或系统按键重复): 当前倍速 ×2,
 *   显示倍速文字, 松手恢复; ← 长按不触发倍速 (用户裁决), 松手照常后退 10 秒
 *
 * 事件经根层 [Modifier.onPreviewKeyEvent] 捕获 (子控件聚焦时也生效, 不受按钮抢占);
 * 锁定态旁路。
 */
private class DesktopVideoKeyHandler(
    private val scope: CoroutineScope,
    private val controller: MediampVideoPlayerController,
    private val onSeekDelta: (Long) -> Unit,
    private val onGestureText: (String?) -> Unit,
) {
    private var boosted = false
    private var originalSpeed = 1f
    private var heldDirection: Key? = null
    private var holdJob: Job? = null
    private var spaceHeld = false

    /** 返回 true 表示事件已消费 (不再下发给子控件) */
    fun onKeyEvent(event: KeyEvent, enabled: Boolean): Boolean {
        val key = event.key
        if (key != Key.Spacebar && key != Key.DirectionRight && key != Key.DirectionLeft) {
            return false
        }
        if (!enabled) return false // 锁定态旁路 (对照 app 手势旁路)
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (key == Key.Spacebar) {
                    // 桌面无 isRepeat (AWT 自动重复表现为连续 KeyDown), 用自身状态防重
                    if (!spaceHeld) {
                        spaceHeld = true
                        controller.playPause()
                    }
                    return true
                }
                if (heldDirection == key) {
                    // 系统按键重复 (连续 KeyDown 无 KeyUp) → 视为长按 → 仅右方向键触发倍速
                    if (key == Key.DirectionRight && !boosted) startBoost()
                    return true
                }
                heldDirection = key
                // 只有右方向键长按触发倍速 (用户裁决: 左键长按不倍速)
                if (key == Key.DirectionRight) {
                    holdJob = scope.launch {
                        delay(KEY_LONG_PRESS_MILLIS)
                        if (heldDirection == key && !boosted) startBoost()
                    }
                }
                return true
            }

            KeyEventType.KeyUp -> {
                if (key == Key.Spacebar) {
                    spaceHeld = false
                    return true
                }
                // 只处理当前按下的方向键 (多键同按时的其他键松手不触发)
                if (key != heldDirection) return true
                holdJob?.cancel()
                holdJob = null
                when {
                    boosted -> {
                        controller.setSpeed(originalSpeed)
                        boosted = false
                        onGestureText(null)
                    }

                    key == Key.DirectionRight -> onSeekDelta(10_000)
                    key == Key.DirectionLeft -> onSeekDelta(-10_000)
                }
                heldDirection = null
                return true
            }

            else -> return true // 未知类型也消费, 避免残留 KeyUp 触发子控件
        }
    }

    /** 焦点丢失/离开页面时清理长按态 (KeyUp 可能收不到, 防倍速卡住) */
    fun reset() {
        holdJob?.cancel()
        holdJob = null
        heldDirection = null
        spaceHeld = false
        if (boosted) {
            controller.setSpeed(originalSpeed)
            boosted = false
            onGestureText(null)
        }
    }

    /** 进入倍速: 当前倍速 ×2 (对齐 app onLongPress), 松手恢复 */
    private fun startBoost() {
        val current = runCatching {
            controller.player.features[PlaybackSpeed.Key]?.value ?: 1f
        }.getOrDefault(1f)
        originalSpeed = current
        boosted = true
        val target = current * 2f
        controller.setSpeed(target)
        onGestureText("%.1fX".format(target))
    }
}

/** 锁定/解锁钮 (对照 app AndroidVideoLockToggle): 复用锁矢量, 白 tint 同其余控制钮 */
@Composable
private fun DesktopVideoLockToggle(
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

/** 播放失败占位: 重试 + 浏览器打开播放地址 */
@Composable
private fun MediampFailedHint(message: String, playUrl: String?, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        MpvCenterText(rememberString("video_play_error_mpv", message))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(rememberString("reload")) }
            if (playUrl != null) {
                MpvLinkButton(rememberString("mpv_open_in_browser")) {
                    runCatching {
                        java.awt.Desktop.getDesktop().browse(java.net.URI(playUrl))
                    }
                }
            }
        }
    }
}

@Composable
private fun MpvCenterText(text: String) {
    Text(
        text = text,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun MpvLinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = androidx.compose.material.ButtonDefaults.textButtonColors(
            contentColor = Color.White,
        ),
    ) {
        Text(label)
    }
}
