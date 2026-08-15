package io.legado.desktop.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.video.ErrorOverlay
import io.legado.app.ui.book.video.LoadingOverlay
import io.legado.app.ui.book.video.PlatformPlayer
import io.legado.app.ui.book.video.VideoBufferingIndicator
import io.legado.app.ui.book.video.VideoCenterControls
import io.legado.app.ui.book.video.VideoControlsOverlay
import io.legado.app.ui.book.video.VideoGestureController
import io.legado.app.ui.book.video.VideoGestureFeedbackText
import io.legado.app.ui.book.video.VideoGestureOverlay
import io.legado.app.ui.book.video.VideoLockToggle
import io.legado.app.ui.book.video.VideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlaybackPoller
import io.legado.app.ui.book.video.VideoPlayerController
import io.legado.app.ui.compose.platform.rememberString
import io.legado.desktop.audio.DesktopScreenBrightness
import io.legado.desktop.audio.DesktopSystemVolume
import io.legado.desktop.ui.DesktopFullscreenController
import io.legado.desktop.ui.DesktopWindowChrome
import io.legado.desktop.ui.DesktopWindowHandle
import io.legado.desktop.ui.applyWindowCornerPreference
import io.legado.desktop.ui.shouldRoundWindowCorner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.togglePlayWhenReady
import kotlin.concurrent.Volatile

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
 * - **状态**: PlayerState v2 (mediaStatus×playWhenReady×isBuffering, 替代废弃的 PlaybackState)
 *   + currentPositionMillis + mediaProperties.durationMillis 驱动控制层。
 * - **交互** (对标 app 端 VideoGestureHandler): 鼠标单击切控制层 / 双击播放暂停 /
 *   长按 2x 倍速 (松手恢复) / 横滑进度 (松手 seek) / 左半竖滑亮度 (WMI 系统亮度) /
 *   右半竖滑音量 (WASAPI 系统音量) (shared [VideoGestureController]); 键盘统一由共享
 *   VideoPlayRoute 的 AppShortcutHandler 快捷键栈分发 (Space 播放/暂停, ←/→=seek ∓10s,
 *   长按 →=2x 倍速, ↑/↓=上/下一章; 桌面 Window 层无条件收键, 不依赖焦点),
 *   本类不再挂独立键盘处理器。
 *
 * # 生命周期
 *
 * [createController] 同步创建 [MediampPlayer] (SPI 工厂, 首次含 native 库解包加载;
 * 已由启动期异步预解包, 见 Main.kt);
 * 创建失败返回 [FailedMediampController] 走错误占位, 不崩 UI。
 * 切章/切分辨率 = videoUrl 变化 → 重新 setMediaData (同一 player, 无进程重启)。
 *
 * player 的寿命跟 ScreenModel (路由栈) 走, 不跟渲染面的组合走: 渲染槽会随布局分支
 * (全屏切换/窗口横竖比变化) 反复进出组合, 若在 onDispose 里 close, 再次进组合就是
 * "MPVHandle has already been closed"。见 [MediampVideoPlayerController.release]。
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

    // 系统级全屏 (对照原版 window 全屏语义): Windows 真全屏 (无边框独占窗口覆盖任务栏,
    // 经 DesktopFullscreenController); 非 Windows 平台显式日志暂不支持 (不做 AWT fallback)
    // 与 applyFullscreen (右上角三点菜单"全屏"项) 区分
    override fun applySystemFullScreen(enabled: Boolean) {
        val window = windowHandle.window ?: return
        // 与 F11/控制栏菜单同一状态源: 成功才翻转 DesktopWindowChrome.fullscreen,
        // 自绘控制栏在视频全屏时同样隐藏 (跨行为一致)
        val ok = DesktopFullscreenController.setFullscreen(window, enabled)
        if (ok) DesktopWindowChrome.fullscreen = enabled
    }

    // 右上角三点菜单"全屏"项 (对照原版 Activity applyFullscreen → toggleSystemBar 沉浸式):
    // shared isFullScreen 只隐藏顶栏/选集网格 (窗口内沉浸模式), 不涉及窗口全屏——
    // 圆角保持普通窗口状态 (undecorated 窗口圆角声明随重绘失效时重新声明);
    // 真全屏 (F11/无边框) 走 applySystemFullScreen
    override fun applyFullscreen(enabled: Boolean) {
        val window = windowHandle.window ?: return
        applyWindowCornerPreference(window, round = shouldRoundWindowCorner(window))
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
 * - 播完 (MediaStatus.Ended) → onPlaybackEnded; 播放错误 (MediaStatus.Error/异常) → onError (UI 占位)
 * - startPlayback: setMediaData(UriMediaData(url, headers)) 后 resume + seek 恢复进度
 */
class MediampVideoPlayerController(
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 独立于 [scope] 的关闭协程 (release 后 scope 已取消, close 需要自己的调度器) */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** mediamp player (SPI 工厂经 classpath 上的 mediamp-mpv 创建; 构造抛异常由调用方兜底) */
    val player: MediampPlayer = MediampPlayer(Unit, scope.coroutineContext)

    /** 已起播的 url: 渲染槽重建时 LaunchedEffect(url) 会重跑, 守卫避免重复 setMediaData */
    @Volatile
    private var startedUrl: String? = null

    /** release 已执行标记 (close 只调度一次) */
    @Volatile
    private var released = false

    /** 播放/初始化失败回传 UI (由 Render 注入), 让失败可见而不是只落日志 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /** 缓冲中 (v2: PlayerState.isBuffering, 数据未就绪无法前进) */
    val isBuffering: Boolean
        get() = player.state.value.isBuffering

    /** 播放中 (v2: PlayerState.isPlaying, 时钟实际在前进) */
    val isPlaying: Boolean
        get() = player.state.value.isPlaying

    init {
        scope.launch {
            player.state.collect { state ->
                when (state.mediaStatus) {
                    MediaStatus.Ended -> onPlaybackEnded()
                    is MediaStatus.Error -> onError?.invoke("播放失败")
                    else -> Unit
                }
            }
        }
    }

    /** 加载播放 (切章/切分辨率统一入口); setMediaData 完成后自动播放 + 恢复进度 */
    fun startPlayback(url: String, headers: Map<String, String>, startMs: Long) {
        // 同一 url 只起播一次: 渲染槽随布局分支 (全屏切换/窗口横竖比变化) 反复进出组合,
        // 重建后 LaunchedEffect(url) 会重跑, 若重复 setMediaData 视频会跳回章节起点。
        // 重试 (onRetry) 先 resetStartGuard 再走这里
        if (startedUrl == url) return
        startedUrl = url
        scope.launch {
            runCatching {
                player.setMediaData(UriMediaData(url, headers))
                player.play()
                if (startMs > 0) player.seekTo(startMs)
            }.onFailure { e ->
                AppLog.put("mediamp 加载失败: ${e.message}", e)
                onError?.invoke("加载失败: ${e.message}")
            }
        }
    }

    /** 允许对同一 url 重新起播 (错误重试时由 UI 先调用, 见 [startPlayback] 的去重守卫) */
    fun resetStartGuard() {
        startedUrl = null
    }

    override val positionMs: Long get() = player.currentPositionMillis.value
    override val durationMs: Long get() = player.mediaProperties.value?.durationMillis ?: 0L
    override val bufferedMs: Long get() = durationMs

    override fun playPause() = player.togglePlayWhenReady()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekBy(deltaMs: Long) = player.skip(deltaMs)
    override fun setSpeed(speed: Float) {
        runCatching { player.features[PlaybackSpeed.Key]?.set(speed) }
            .onFailure { AppLog.putDebug("mediamp 倍速设置失败: ${it.message}") }
    }

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        scope.cancel()
        if (released) return
        released = true
        // player 寿命跟 ScreenModel (路由栈) 走, 不随渲染槽组合: 渲染槽会随布局分支反复进出
        // 组合, 若在组合 onDispose 里 close, 再次进组合就是 "MPVHandle has already been closed"
        // (见 MediampRender DisposableEffect)。close 移到这里 (onCleared 释放 ScreenModel),
        // 但 surface 的 onDispose (setRenderUpdateListener/releaseSurface) 仍要访问 MPVHandle.ptr,
        // 必须先 detach 再 close: 出栈页在 pop 动画期间还在组合中, release 立即 close 会撞上
        // surface, 故用「延迟关闭」代替「dispose 即关闭」—— 等 dispose 传递完再关。
        closeScope.launch {
            delay(300)
            runCatching { player.close() }
                .onFailure { AppLog.put("mediamp 播放器关闭失败", it) }
        }
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
@OptIn(ExperimentalMediampApi::class)
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

    // 播放状态订阅 (mediamp v2 PlayerState, 驱动播放态/缓冲圈/错误)
    val playerState by controller.player.state.collectAsState()
    val isPlaying = playerState.isPlaying
    val isBuffering = playerState.isBuffering
    // 缓冲进度 (mpv cache-buffering-state, 0-100): <100 表示播放器在等数据。起播首帧前的
    // 网络缓冲窗口里 isBuffering 可能尚未置位, 靠它兜底
    val bufferingFeature = controller.player.features[Buffering.Key]
    val bufferedPercent by bufferingFeature
        ?.bufferedPercentage
        ?.collectAsState(initial = 100) ?: remember { mutableIntStateOf(100) }

    // 播放/初始化失败回传 → 错误占位 (回调在任意线程, 经 scope 切回 composition 调度器)。
    // 播放器不在这里 close: player 寿命跟 ScreenModel 走 (渲染槽会反复进出组合),
    // 由 ScreenModel.onCleared → [MediampVideoPlayerController.release] 延迟关闭
    DisposableEffect(controller) {
        controller.onError = { msg -> scope.launch { playError = msg } }
        onDispose {
            controller.onError = null
        }
    }

    // 起播/重试/切章: 链接就绪且无错误 → setMediaData (retryKey 变化同样经此重起)
    LaunchedEffect(url, retryKey) {
        if (url != null && playError == null) {
            controller.startPlayback(url, headers, startMs)
        }
    }

    // 播放中进入 ERROR 状态 (非显式错误回调) → 错误占位
    LaunchedEffect(playerState) {
        if (playerState.mediaStatus is MediaStatus.Error && playError == null) {
            playError = "播放失败"
        }
    }

    // ---- 控制层回显轮询 + 自动隐藏 (shared 统一; 缓冲中也计时, 用户拍板 2026-08) ----
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    VideoPlaybackPoller(
        controlsVisible = uiState.controlsVisible,
        autoHideActive = isPlaying || isBuffering,
        seeking = seeking,
        locked = locked,
        onAutoHide = screenModel::onToggleControls,
        poll = {
            positionMs = controller.positionMs
            durationMs = controller.durationMs
            // 回显到共享 UiState (缓冲圈/自动隐藏依赖)
            screenModel.onPlayerState(
                isPlaying = isPlaying,
                // v2 直接暴露用户意图轴 (v1 需用 PLAYING||PAUSED_BUFFERING 反推)
                playWhenReady = playerState.playWhenReady,
                playbackState = if (isBuffering) {
                    PlatformPlayer.STATE_BUFFERING
                } else {
                    PlatformPlayer.STATE_READY
                },
            )
        },
    )

    val error = uiState.error
    val showLoading = error == null && playError == null &&
        // 章节内容加载中 或 播放器起播前 (引擎初始化 Idle / 正在打开 Opening, 首次加载转圈)
        (uiState.loading || (url != null &&
            (playerState.mediaStatus == MediaStatus.Idle ||
                playerState.mediaStatus == MediaStatus.Opening)))
    // 缓冲圈: 播放器在等数据 (cache-buffering-state < 100, 覆盖"链接就绪→首帧"的起播缓冲窗口)
    // 或 paused-for-cache (Ready + isBuffering, 播放中卡顿); 用户暂停/结束/错误态不叠
    val showBuffering = error == null && !showLoading && playError == null &&
        playerState.mediaStatus == MediaStatus.Ready &&
        (bufferedPercent < 100 || isBuffering)

    // 手势反馈文字 (键盘长按倍速与鼠标手势共用 ScreenModel 级 flow, null 时隐藏)
    val gestureText by screenModel.gestureText.collectAsState()

    // 鼠标手势 (shared VideoGestureController): 单击切控制层/双击播放暂停/
    // 长按 2x 倍速/横滑进度/左半竖滑亮度/右半竖滑音量 (仅平台读写槽注入)
    val gestureController = remember(controller) {
        VideoGestureController(
            isPlaying = { controller.isPlaying },
            positionMs = { controller.positionMs },
            durationMs = { controller.durationMs },
            speed = {
                runCatching {
                    controller.player.features[PlaybackSpeed.Key]?.value ?: 1f
                }.getOrDefault(1f)
            },
            setSpeed = { controller.setSpeed(it) },
            onPlayPause = { controller.playPause() },
            seekTo = { controller.seekTo(it) },
            readBrightness = {
                DesktopScreenBrightness.get()?.let { it / 100f } ?: 0.5f
            },
            writeBrightness = { DesktopScreenBrightness.set((it * 100).toInt()) },
            readVolume = {
                DesktopSystemVolume.getVolume() ?: mediampVolumeNormalized(controller)
            },
            writeVolume = { level ->
                // WASAPI 不可用 (无音频设备/COM 失败) 时回落写 mediamp 音量 (mpv volume),
                // 避免"手势百分比在动、实际无声" (与亮度读失败回落同思路)
                val wroteSystem = DesktopSystemVolume.setVolume(level)
                if (!wroteSystem) {
                    controller.player.features[AudioLevelController.Key]?.let { vc ->
                        runCatching { vc.setVolume(level * vc.maxVolume) }
                    }
                }
            },
            onToggleControls = screenModel::onToggleControls,
            onGestureText = screenModel::onGestureText,
        )
    }
    // 进屏即抢焦点 (键盘事件统一由共享快捷键栈在 Window 层无条件分发, 不依赖本焦点;
    // 保留抢焦仅维持原有焦点导航观感, 本层不挂独立键盘处理器)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        // 视频渲染面 (mediamp 内部等比适配 letterbox)
        MediampPlayerSurface(
            mediampPlayer = controller.player,
            modifier = Modifier.fillMaxSize(),
        )
        // 手势层 (shared 统一: 单击/双击/长按 + 滑动/抬手, 锁定态旁路)
        VideoGestureOverlay(
            handler = gestureController,
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
                    controller.resetStartGuard()
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
                isSystemFullScreen = uiState.isSystemFullScreen,
                onToggleSystemFullScreen = screenModel::onToggleSystemFullScreen,
                showSystemFullScreenButton = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 锁定态: 仅留半透明小锁钮 (shared 统一组件)
        if (locked && playError == null) {
            VideoLockToggle(
                locked = true,
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
            )
        }
        // 缓冲圈 (叠于控制层之上; shared 统一组件, 颜色统一主题强调色)
        if (showBuffering) {
            VideoBufferingIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 手势反馈文字 (对照 app tv_video_speed, 叠于所有层之上; shared 统一组件)
        gestureText?.let {
            VideoGestureFeedbackText(
                text = it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
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

/** mediamp 播放器音量归一化到 0..1 (mpv maxVolume=2; 仅系统音量读取失败时兜底参照, 原 DesktopVideoGestureHandler 私有方法)。 */
private fun mediampVolumeNormalized(controller: MediampVideoPlayerController): Float = runCatching {
    val vc = controller.player.features[AudioLevelController.Key] ?: return 0.5f
    (vc.volume.value / vc.maxVolume.coerceAtLeast(1f)).coerceIn(0f, 1f)
}.getOrDefault(0.5f)


/** 锁定/解锁钮已收拢为 shared [VideoLockToggle] (见 VideoPlayerScreenContent.kt)。 */

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
