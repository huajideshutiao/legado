package io.legado.desktop.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.video.VideoPlayPlatformProvider
import io.legado.app.ui.book.video.VideoPlayScreenModel
import io.legado.app.ui.book.video.VideoPlayerController
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.desktop.help.video.MpvDetector
import io.legado.desktop.help.video.MpvDownloader
import io.legado.desktop.help.video.MpvPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Canvas
import java.awt.Desktop
import java.net.URI

/**
 * desktop 端 [VideoPlayPlatformProvider] 真实实现: 外部 mpv 进程渲染。
 *
 * # 架构 (对照 desktop/help/video/MpvPlayer.kt)
 *
 * - **一个 URL = 一个 mpv 进程**: 切章/切分辨率时旧进程 quit, 新进程 start
 * - **嵌入**: `SwingPanel(AWT Canvas)` + JNA `Native.getComponentID` 取原生句柄
 *   (Windows HWND / X11 XID) → `--wid` 嵌入 Compose 区域; macOS 无可用句柄走独立窗口
 * - **控制层**: mpv 内建 OSC (--osc=yes), 不叠 Compose 控制层 —— SwingPanel 是重量级 AWT,
 *   Compose 内容画不到它之上 (airspace), 故视频区内一律不放 Compose 控件
 * - **降级链**: 嵌入 → 独立窗口 (句柄拿不到/macOS) → 未装 mpv 显示引导安装占位
 * - **IPC 桥**: 经 MpvPlayer.command 转发 playPause/seekTo/setSpeed; positionMs/durationMs
 *   由 mpv observe_property 事件持续刷新
 *
 * # 状态可见性
 *
 * 检测/下载/启动失败都渲染成 [MpvStage] 对应的占位层, 不留白屏;
 * mpv 未渲染时的底色统一为黑 (Canvas + SwingPanel 背景, 后者默认是白色)。
 */
class DesktopVideoPlayPlatformProvider : VideoPlayPlatformProvider {

    override fun createController(
        screenModel: VideoPlayScreenModel,
        onPlaybackEnded: () -> Unit,
    ): VideoPlayerController = DesktopVideoPlayerController(onPlaybackEnded)

    @Composable
    override fun Render(
        controller: VideoPlayerController,
        screenModel: VideoPlayScreenModel,
        modifier: Modifier,
    ) {
        val desktopController = controller as? DesktopVideoPlayerController ?: return
        val url = screenModel.shared.videoUrl.value?.url
        val headers = screenModel.shared.videoUrl.value?.headerMap ?: emptyMap()
        val startMs = screenModel.shared.curBook?.durChapterPos?.toLong() ?: 0L
        val bookName = screenModel.shared.curBook?.name ?: ""
        val chapterTitle = screenModel.state.value.chapterTitle

        val scope = rememberCoroutineScope()
        // AWT Canvas 供 mpv --wid 嵌入; 背景设黑, 否则 mpv 起窗前是刺眼的系统默认底色
        val canvas = remember { Canvas().apply { background = java.awt.Color.BLACK } }
        var stage by remember { mutableStateOf<MpvStage>(MpvStage.Detecting) }
        var mpvPath by remember { mutableStateOf<String?>(null) }

        // mpv 播放错误是 IPC/进程退出异步上报的, 转成可见失败态而不是只进日志
        DisposableEffect(desktopController) {
            desktopController.onError = { msg -> stage = MpvStage.Failed(msg) }
            onDispose { desktopController.onError = null }
        }

        suspend fun detect(forceRefresh: Boolean) {
            // 已知路径时不回 Detecting: 切章不该卸载 SwingPanel (Canvas peer 一销毁就要重拿句柄)
            if (mpvPath == null || forceRefresh) stage = MpvStage.Detecting
            val path = withContext(Dispatchers.IO) { MpvDetector.detect(forceRefresh) }
            mpvPath = path
            stage = if (path == null) MpvStage.NotFound(null) else MpvStage.Launching
        }

        LaunchedEffect(url) {
            if (!url.isNullOrEmpty()) detect(forceRefresh = false)
        }

        // Launching 阶段 SwingPanel 已挂载, 等 Canvas addNotify 后再取句柄启动 mpv
        LaunchedEffect(stage, url) {
            if (stage !is MpvStage.Launching || url.isNullOrEmpty()) return@LaunchedEffect
            val path = mpvPath ?: return@LaunchedEffect
            var wid: Long? = null
            var attempts = 0
            while (wid == null && attempts < 40) {
                wid = canvas.nativeHandle()
                if (wid == null) {
                    delay(50)
                    attempts++
                }
            }
            val mediaTitle =
                if (chapterTitle.isNotEmpty()) "$bookName - $chapterTitle" else bookName
            // 取句柄要在 EDT (Compose Main dispatcher 即 EDT), 起进程放 IO 不卡界面
            val error = withContext(Dispatchers.IO) {
                desktopController.startPlayback(
                    mpvPath = path,
                    url = url,
                    headers = headers,
                    startMs = startMs,
                    wid = wid,
                    windowTitle = bookName,
                    mediaTitle = mediaTitle,
                )
            }
            stage = when {
                error != null -> MpvStage.Failed(error)
                wid == null -> MpvStage.Detached
                else -> MpvStage.Embedded
            }
        }

        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            when (val current = stage) {
                // SwingPanel 默认背景是白色, 显式压黑 (mpv 起窗前的白屏观感就来自这里)
                MpvStage.Launching, MpvStage.Embedded -> SwingPanel(
                    background = Color.Black,
                    factory = { canvas },
                    modifier = Modifier.fillMaxSize(),
                )

                MpvStage.Detecting -> MpvBusyHint(rememberString("loading"))
                MpvStage.Detached -> MpvCenterText(rememberString("mpv_detached_playing"))

                is MpvStage.Downloading -> MpvDownloadingHint(current.progress)

                is MpvStage.NotFound -> MpvInstallGuide(
                    failure = current.failure,
                    playUrl = url,
                    onDownload = {
                        scope.launch {
                            stage = MpvStage.Downloading(0f)
                            runCatching {
                                MpvDownloader.downloadAndInstall { p ->
                                    stage = MpvStage.Downloading(p)
                                }
                            }.onSuccess {
                                detect(forceRefresh = true)
                            }.onFailure { e ->
                                AppLog.put("mpv 便携版下载失败", e)
                                stage = MpvStage.NotFound(e.message ?: e.toString())
                            }
                        }
                    },
                    onRedetect = { scope.launch { detect(forceRefresh = true) } },
                )

                is MpvStage.Failed -> MpvFailedHint(
                    message = current.message,
                    playUrl = url,
                    onRetry = { scope.launch { detect(forceRefresh = true) } },
                )
            }
        }
    }
}

/** mpv 渲染区状态 (每一态都有对应占位层, 不留白屏) */
private sealed interface MpvStage {
    /** 探测 mpv 可执行中 */
    data object Detecting : MpvStage

    /** 已找到 mpv, SwingPanel 挂载中 (等 Canvas 原生句柄) */
    data object Launching : MpvStage

    /** mpv 已嵌入 Canvas 渲染 */
    data object Embedded : MpvStage

    /** 拿不到原生句柄 (macOS 等), mpv 在独立窗口播放 */
    data object Detached : MpvStage

    /** 未检测到 mpv; [failure] 为上次自动下载的失败原因 (null = 尚未尝试) */
    data class NotFound(val failure: String?) : MpvStage

    /** 便携版下载中; [progress] 0f..1f, -1f = 总长未知 */
    data class Downloading(val progress: Float) : MpvStage

    /** 启动/播放失败 */
    data class Failed(val message: String) : MpvStage
}

// ---- 占位层 (全部画在视频区内, mpv 未渲染时才出现, 不与 SwingPanel 叠放) ----

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
private fun MpvBusyHint(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
        MpvCenterText(text)
    }
}

@Composable
private fun MpvDownloadingHint(progress: Float) {
    val label = if (progress >= 0f) {
        rememberString("mpv_downloading_percent", (progress * 100).toInt().toString())
    } else {
        rememberString("mpv_downloading")
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 48.dp),
    ) {
        // 百分比走文案 (mpv_downloading_percent), 进度条只表示"进行中"
        LinearProgressIndicator(
            backgroundColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        )
        MpvCenterText(label)
    }
}

@Composable
private fun MpvInstallGuide(
    failure: String?,
    playUrl: String?,
    onDownload: () -> Unit,
    onRedetect: () -> Unit,
) {
    var browserOpened by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        MpvCenterText(rememberString("mpv_not_found"))
        MpvCenterText(rememberString("mpv_install_guide"))
        if (failure != null) {
            MpvCenterText(rememberString("mpv_download_failed", failure))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (MpvDownloader.isSupported) {
                Button(onClick = onDownload) { Text(rememberString("mpv_auto_download")) }
            }
            Button(onClick = onRedetect) { Text(rememberString("mpv_redetect")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MpvLinkButton(rememberString("mpv_open_website")) { openInBrowser(MPV_SITE_URL) }
            if (playUrl != null) {
                MpvLinkButton(rememberString("mpv_open_in_browser")) {
                    browserOpened = openInBrowser(playUrl)
                }
            }
        }
        if (browserOpened) MpvCenterText(rememberString("mpv_open_in_browser_warn"))
    }
}

@Composable
private fun MpvFailedHint(message: String, playUrl: String?, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        MpvCenterText(rememberString("video_play_error_mpv", message))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(rememberString("reload")) }
            if (playUrl != null) {
                MpvLinkButton(rememberString("mpv_open_in_browser")) { openInBrowser(playUrl) }
            }
        }
    }
}

@Composable
private fun MpvLinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
    ) {
        Text(label)
    }
}

private const val MPV_SITE_URL = "https://mpv.io"

private fun openInBrowser(url: String): Boolean =
    runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess

/**
 * AWT Canvas 原生窗口句柄 (Windows HWND / X11 XID) 供 mpv `--wid`。
 *
 * 必须等组件 addNotify (SwingPanel 挂进窗口) 之后才有效, 未 realize 时返回 null 由调用方重试;
 * macOS 的 NSView 句柄 mpv 不接受, 直接降级独立窗口。
 */
private fun Canvas.nativeHandle(): Long? {
    if (MpvDetector.isMac || !isDisplayable) return null
    return runCatching { Native.getComponentID(this) }.getOrNull()?.takeIf { it != 0L }
}

/**
 * desktop 视频控制器: 包装 [MpvPlayer], 桥接 [VideoPlayerController] 接口到 mpv IPC 命令。
 *
 * - playPause → `cycle pause`
 * - seekTo → `seek <sec> absolute`
 * - seekBy → `seek <sec> relative`
 * - setSpeed → `set_property speed <val>`
 * - positionMs/durationMs → 读 MpvPlayer.lastPosMs/durationMs (IPC observe_property 持续刷新)
 */
class DesktopVideoPlayerController(
    private val onPlaybackEnded: () -> Unit,
) : VideoPlayerController {

    private var player: MpvPlayer? = null

    /** 播放失败回传 UI (由 Render 注入), 让失败可见而不是只落日志 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /**
     * 启动新播放 (切章/切分辨率时旧进程 quit 再 start, 对齐 MpvPlayer "一实例一播放" 设计)。
     *
     * @return 启动失败原因 (mpv 进程拉不起来); null = 已拉起
     */
    fun startPlayback(
        mpvPath: String,
        url: String,
        headers: Map<String, String>,
        startMs: Long,
        wid: Long?,
        windowTitle: String,
        mediaTitle: String,
    ): String? {
        player?.quit(discardProgress = false)
        val newPlayer = MpvPlayer(
            mpvPath = mpvPath,
            onEof = onPlaybackEnded,
            onPlayError = { msg ->
                AppLog.put(jvmGetString("video_play_error_mpv", msg), null, true)
                onError?.invoke(msg)
            },
        )
        player = newPlayer
        return runCatching {
            newPlayer.start(
                playPath = url,
                headers = headers,
                startMs = startMs,
                wid = wid,
                windowTitle = windowTitle,
                mediaTitle = mediaTitle,
            )
        }.exceptionOrNull()?.let { e ->
            val reason = e.message ?: e.toString()
            AppLog.put(jvmGetString("video_mpv_launch_error_log", reason), e, true)
            reason
        }
    }

    override val positionMs: Long get() = player?.lastPosMs ?: 0L
    override val durationMs: Long get() = player?.durationMs ?: 0L
    override val bufferedMs: Long get() = durationMs

    override fun playPause() = player?.command("cycle", "pause") ?: Unit
    override fun seekTo(positionMs: Long) =
        player?.command("seek", positionMs / 1000.0, "absolute") ?: Unit

    override fun seekBy(deltaMs: Long) =
        player?.command("seek", deltaMs / 1000.0, "relative") ?: Unit

    override fun setSpeed(speed: Float) =
        player?.command("set_property", "speed", speed) ?: Unit

    override fun seekBack() = seekBy(-10000)
    override fun seekForward() = seekBy(10000)

    override fun release() {
        player?.quit(discardProgress = false)
        player = null
    }
}
