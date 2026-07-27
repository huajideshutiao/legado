package io.legado.desktop.ui.book.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.VideoResolution
import io.legado.app.ui.book.video.ResolutionButton
import io.legado.app.ui.book.video.VideoPlayViewModelShared
import io.legado.app.ui.book.video.VideoPlayerScreenContent
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.desktop.help.video.MpvDetector
import io.legado.desktop.help.video.MpvDownloader
import io.legado.desktop.help.video.MpvPlayer
import java.awt.Canvas
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端视频播放 Screen 入口 (对照 app 端 [io.legado.app.ui.book.video.VideoPlayActivity]
 * + [io.legado.app.ui.book.video.VideoPlayScreen])。
 *
 * # 职责
 *
 * - 包装 [VideoPlayViewModelShared] 持有章节视频 URL 列表状态
 * - 注入 desktop 平台 Provider (与 [io.legado.desktop.ui.about.AboutScreen] 一致 4 个 Provider)
 * - 骨架 (标题栏 / 章节控制栏 / 加载错误条) 复用 sharedUiMain [VideoPlayerScreenContent],
 *   本文件只提供 mpv 渲染层与状态接线
 *
 * # 播放方案 (all-in mpv)
 *
 * - **渲染/控制**: mpv 外部进程经 `--wid` 嵌入 AWT Canvas (SwingPanel), 控制层用 mpv
 *   内建 OSC (播放/暂停/进度/音量); SwingPanel 是重量级 AWT, Compose 无法叠加其上,
 *   故加载/错误状态以细条形式显示在底部章节栏上方而非覆盖层
 * - **IPC 桥**: [MpvPlayer] 经 JSON IPC 观察 time-pos/duration 做进度回写, 监听
 *   end-file(eof) 自动切下一章, 并把 Compose 侧键盘快捷键 (空格/←/→) 转发为 mpv 命令;
 *   IPC 失败仅丢这些增强, 播放本身不受影响
 * - **防盗链**: [io.legado.app.model.analyzeRule.AnalyzeUrlCore.headerMap] 全量透传
 *   (含 Cookie), 由 [MpvPlayer.start] 拼装 mpv 选项 (UA/Referer 专属选项达 HLS 分片)
 * - **未安装 mpv**: 显示引导安装占位 (官网/包管理器 + 自定义路径 [MpvDetector.PREF_KEY_MPV_PATH]
 *   + 重新检测 + 浏览器直接打开流的兜底)
 * - **macOS**: AWT 拿不到可 --wid 嵌入的 NSView 句柄, 降级为独立 mpv 窗口播放
 * - **进度持久化**: 退出/进程结束时把 IPC 最后观测位置经
 *   [VideoPlayViewModelShared.saveVideoProgressOnExit] 落存, 打开时 `--start=` 恢复
 *
 * # 路由接入
 *
 * 由 [io.legado.desktop.ui.DesktopApp] 的 `DesktopRoute.VIDEO_PLAYER` 分支消费,
 * 触发点: 书架/详情/目录点击 `book.isVideo` 类型书籍。
 *
 * @param book 待播放的视频书 (book.type 含 [io.legado.app.constant.BookType.video] 位)
 * @param chapterIndex 初始章节序号 (默认 0, 取 book.durChapterIndex)
 * @param onBack 返回回调 (切回调用方路由)
 * @param onOpenToc 打开目录回调 (切到 TOC 路由, 携带 Book)
 * @param onOpenChangeSource 打开换源回调 (切到 CHANGE_SOURCE 路由, 携带 Book)
 */
@Composable
fun VideoPlayerScreen(
    book: Book,
    chapterIndex: Int = 0,
    onBack: () -> Unit,
    onOpenToc: (Book) -> Unit = {},
    onOpenChangeSource: (Book) -> Unit = {},
) {
    // 注入 desktop 平台 Provider (参照 MangaReaderScreen CompositionLocalProvider 模式)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    val prefStore = remember { DesktopPreferenceStoreProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
        LocalPreferenceStoreProvider provides prefStore,
    ) {
        AppTheme {
            VideoPlayerContent(
                book = book,
                chapterIndex = chapterIndex,
                prefStore = prefStore,
                onBack = onBack,
                onOpenToc = onOpenToc,
                onOpenChangeSource = onOpenChangeSource,
            )
        }
    }
}

/**
 * 视频播放主体内容: 持有 VM + mpv 进程状态, 骨架委托 [VideoPlayerScreenContent]。
 *
 * 注: 拆出顶层 Composable 是为在 [CompositionLocalProvider] + [AppTheme] 包裹后消费
 * LocalXxx, 与 MangaReaderScreen 一致。
 */
@Composable
private fun VideoPlayerContent(
    book: Book,
    chapterIndex: Int,
    prefStore: PreferenceStoreProvider,
    onBack: () -> Unit,
    onOpenToc: (Book) -> Unit,
    onOpenChangeSource: (Book) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { VideoPlayViewModelShared(scope, prefStore) }

    // 收集 VM 状态
    val videoUrl by viewModel.videoUrl.collectAsState()
    val resolutions by viewModel.resolutions.collectAsState()
    val curChapterIndex by viewModel.curChapterIndex.collectAsState()
    val chapterSize by viewModel.chapterSize.collectAsState()
    val curChapterTitle by viewModel.curChapterTitle.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // 上次播放位置 (对照 app 端 initData: position = curBook.durChapterPos)
    val initialPositionMs = remember(book.bookUrl) { viewModel.getSavedVideoProgress(book.bookUrl) }

    // 初始化数据 (装载书 + 章节列表 + 加载首章)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book, chapterIndex)
    }

    // ---- mpv 检测 (进程试探属阻塞 IO, 移出组合线程) ----

    var mpvPath by remember { mutableStateOf<String?>(null) }
    var mpvDetectDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        mpvPath = withContext(Dispatchers.IO) { MpvDetector.detect() }
        mpvDetectDone = true
    }
    // macOS 拿不到 NSView 句柄, 降级独立窗口
    val embeddable = !MpvDetector.isMac

    // ---- mpv 进程状态 ----

    // 嵌入句柄 (SwingPanel Canvas addNotify 后回填; 独立窗口模式恒 null)
    var canvasWid by remember { mutableStateOf<Long?>(null) }
    var player by remember { mutableStateOf<MpvPlayer?>(null) }
    // mpv 自身错误 (启动失败/播放失败重试后仍失败), 与 VM error 一起走底部状态条
    var mpvError by remember { mutableStateOf<String?>(null) }
    // 首次加载是否已恢复播放位置 (切章不再恢复)
    var hasRestoredInitialPosition by remember { mutableStateOf(false) }
    // 内存 m3u8 临时文件 (mpv 播放本地 .m3u8; cache 目录 + shutdown hook 兜底清理)
    val tempCacheDir = remember {
        File(System.getProperty("java.io.tmpdir"), "legado_video").apply { if (!exists()) mkdirs() }
    }
    var tempFile by remember { mutableStateOf<File?>(null) }
    // 键盘事件焦点: 共享件 onPreviewKeyEvent 挂根 Box, 渲染槽 (其后代) 持有焦点才触发
    val focusRequester = remember { FocusRequester() }

    // URL / 句柄 / mpv 路径就绪后 (重新) 启动 mpv 进程
    LaunchedEffect(videoUrl, canvasWid, mpvPath) {
        val mpv = mpvPath ?: return@LaunchedEffect
        val url = videoUrl
        if (url == null) {
            // 章节切换清场 (loadChapter 先置 null): 旧进程退出且不落存
            // (切章进度重置为 0 已由 VM moveToNext/PrevChapter 落存)
            player?.quit(discardProgress = true)
            player = null
            return@LaunchedEffect
        }
        if (embeddable && canvasWid == null) return@LaunchedEffect // 等 Canvas 句柄
        // 分辨率切换 (videoUrl 直接换新值, 无 null 间隔): 记住旧位置续播
        val resumeMs = player?.lastPosMs ?: 0L
        player?.quit(discardProgress = true)
        player = null
        mpvError = null
        // 内存 m3u8 (url.url 非 http 开头) 落地临时文件 (mpv 需要 URL 或文件路径)
        val isMemoryM3u8 = !url.url.startsWith("http")
        val playPath = if (isMemoryM3u8) {
            tempFile?.delete()
            val file = File(tempCacheDir, "legado_video_${UUID.randomUUID()}.m3u8")
            file.writeText(url.url, Charsets.UTF_8)
            tempFile = file
            // shutdown hook 兜底清理 (防进程异常退出残留)
            Runtime.getRuntime().addShutdownHook(Thread { file.delete() })
            file.absolutePath
        } else {
            url.url
        }
        val startMs = when {
            resumeMs > 0 -> resumeMs
            !hasRestoredInitialPosition && initialPositionMs > 0 -> {
                hasRestoredInitialPosition = true
                initialPositionMs
            }

            else -> 0L
        }
        // header 全量透传 (含 Cookie); 内存 m3u8 场景同样透传 (分片请求仍需 Referer 等)
        val headers = url.headerMap.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
        val p = MpvPlayer(
            mpvPath = mpv,
            onEof = {
                // 自然播完 → 切下一章 (对照 app 端 STATE_ENDED; IPC 线程回调, VM 内部线程安全)
                viewModel.moveToNextChapter()
            },
            onPlayError = { msg ->
                // 首次错误重试一次 (重拉章节内容), 重试过则状态条 + toast
                if (!viewModel.retryOnPlayError()) {
                    val text = jvmGetString("video_play_error_mpv", msg)
                    mpvError = text
                    AppLog.put(text, toast = true)
                }
            },
            onProcessExit = { posMs, durMs ->
                // 片尾归零 + 落存委托 shared VM (仅播放过才触发, MpvPlayer 内部已挡未播先退)
                viewModel.saveVideoProgressOnExit(posMs, durMs)
            },
        )
        runCatching {
            p.start(
                playPath = playPath,
                headers = headers,
                startMs = startMs,
                wid = canvasWid.takeIf { embeddable },
                windowTitle = book.name,
                mediaTitle = "${book.name} $curChapterTitle".trim(),
                unsafeLocalPlaylist = isMemoryM3u8,
            )
            player = p
        }.onFailure {
            val text = jvmGetString("video_mpv_launch_error_log", it.message)
            mpvError = text
            AppLog.put(text, it, toast = true)
        }
    }

    // Screen 退出: 退出 mpv (waiter 线程落存最后位置) + 清临时文件
    DisposableEffect(Unit) {
        onDispose {
            player?.quit(discardProgress = false)
            tempFile?.delete()
        }
    }

    // 渲染槽就绪后取焦点, 让共享件根 Box 的 onPreviewKeyEvent 位于焦点路径上
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // 骨架 (标题栏 + 视频区 + 状态条/章节控制栏 + 键盘快捷键) 走 sharedUiMain 共享件
    VideoPlayerScreenContent(
        bookName = book.name,
        chapterTitle = curChapterTitle,
        curChapterIndex = curChapterIndex,
        chapterSize = chapterSize,
        onBack = onBack,
        onOpenToc = { onOpenToc(book) },
        onOpenChangeSource = { onOpenChangeSource(book) },
        onPrevChapter = { viewModel.moveToPrevChapter() },
        onNextChapter = { viewModel.moveToNextChapter() },
        videoRenderSlot = { slotModifier ->
            MpvVideoRenderSlot(
                modifier = slotModifier
                    .focusRequester(focusRequester)
                    .focusable(),
                detectDone = mpvDetectDone,
                mpvPath = mpvPath,
                embeddable = embeddable,
                streamUrl = videoUrl?.url?.takeIf { it.startsWith("http") },
                onWid = { canvasWid = it },
                onRedetect = {
                    scope.launch {
                        mpvPath = withContext(Dispatchers.IO) { MpvDetector.detect(forceRefresh = true) }
                    }
                },
                // 应用内下载完成: 强制刷新探测 (下载器已落存 mpvPath 设置项), 直接进入播放
                onInstalled = {
                    scope.launch {
                        mpvPath = withContext(Dispatchers.IO) { MpvDetector.detect(forceRefresh = true) }
                    }
                },
            )
        },
        // 键盘快捷键 → mpv IPC 命令 (空格播放暂停 / ←→ seek ±10s / 长按 → 2X)
        onPlayPause = { player?.command("cycle", "pause") },
        onSeekDelta = { deltaMs -> player?.command("seek", deltaMs / 1000.0, "relative") },
        onSpeedChange = { speed -> player?.command("set_property", "speed", speed) },
        // 视觉控制层由 mpv OSC 接管, Compose 侧无控制层可收:
        // controlsVisible 常开让 ←/→ 快捷键直达, Escape 的"收控制层"分支重定向为返回
        controlsVisible = true,
        onToggleControls = onBack,
        bottomBarSlot = {
            // SwingPanel 为重量级 AWT, Compose 无法在视频区上叠加载/错误覆盖层,
            // 改为细状态条显示在章节栏上方
            MpvStatusStrip(
                error = error ?: mpvError,
                loading = loading,
                onRetry = {
                    mpvError = null
                    viewModel.refreshChapter()
                },
            )
            MpvChapterBar(
                curIndex = curChapterIndex,
                size = chapterSize,
                onPrev = { viewModel.moveToPrevChapter() },
                onNext = { viewModel.moveToNextChapter() },
                resolutions = resolutions,
                currentResolutionIndex = viewModel.currentResolutionIndex,
                onSwitchResolution = { idx -> viewModel.switchResolution(idx) },
            )
        },
    )
}

/**
 * mpv 渲染槽:
 * - 已找到 mpv + 可嵌入 → [SwingPanel] 挂 AWT [Canvas], addNotify 后经 JNA 取原生句柄
 *   ([Native.getComponentID], Windows HWND / X11 XID) 回填给调用方供 `--wid` 嵌入
 * - 已找到 mpv + 不可嵌入 (macOS) → 提示独立窗口播放中
 * - 未找到 mpv → [MpvInstallGuide] 引导安装占位
 */
@Composable
private fun MpvVideoRenderSlot(
    modifier: Modifier,
    detectDone: Boolean,
    mpvPath: String?,
    embeddable: Boolean,
    streamUrl: String?,
    onWid: (Long?) -> Unit,
    onRedetect: () -> Unit,
    onInstalled: (String) -> Unit,
) {
    Box(
        modifier.background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !detectDone -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )

            mpvPath == null -> MpvInstallGuide(
                streamUrl = streamUrl,
                onRedetect = onRedetect,
                onInstalled = onInstalled,
            )

            embeddable -> SwingPanel(
                factory = {
                    object : Canvas() {
                        override fun addNotify() {
                            super.addNotify()
                            onWid(runCatching { Native.getComponentID(this) }.getOrNull())
                        }

                        override fun removeNotify() {
                            onWid(null)
                            super.removeNotify()
                        }
                    }.apply { background = java.awt.Color.BLACK }
                },
                modifier = Modifier.fillMaxSize(),
            )

            else -> Text(
                text = rememberString("mpv_detached_playing"),
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 引导安装占位: mpv 未检测到时的安装指引 + 自动下载 + 重新检测 + 浏览器直开兜底。
 *
 * Windows 上额外给"下载便携版"按钮 (见 [MpvDownloader]), 下载中显示进度条并禁用其他动作,
 * 装好后经 [onInstalled] 让上层刷新检测直接进入播放; 非 Windows 只显示包管理器文案。
 */
@Composable
private fun MpvInstallGuide(
    streamUrl: String?,
    onRedetect: () -> Unit,
    onInstalled: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // null = 未在下载; -1f = 进度未知 (解压/无 Content-Length); 0f..1f = 下载百分比
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val downloading = progress != null

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = rememberString("mpv_not_found"),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = rememberString("mpv_install_guide"),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        )
        if (downloading) {
            MpvDownloadProgress(progress = progress ?: -1f)
        } else {
            downloadError?.let {
                Text(
                    text = it,
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp).padding(bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (MpvDownloader.isSupported) {
                    GuideActionText(rememberString("mpv_auto_download")) {
                        downloadError = null
                        progress = -1f
                        scope.launch {
                            runCatching {
                                MpvDownloader.downloadAndInstall { p -> progress = p }
                            }.onSuccess { path ->
                                progress = null
                                onInstalled(path)
                            }.onFailure {
                                progress = null
                                // 取消 (用户离开 Screen) 不算错误, 无需提示
                                if (it is CancellationException) return@onFailure
                                downloadError = jvmGetString("mpv_download_failed", it.message)
                                AppLog.put(jvmGetString("mpv_download_failed", it.message), it)
                            }
                        }
                    }
                }
                GuideActionText(rememberString("mpv_open_website")) {
                    browse("https://mpv.io/installation/")
                }
                GuideActionText(rememberString("mpv_redetect"), onClick = onRedetect)
                if (streamUrl != null) {
                    // 兜底: 系统默认程序直开流 URL (无防盗链请求头, 部分源可能拒绝)
                    GuideActionText(rememberString("mpv_open_in_browser")) {
                        browse(streamUrl)
                        AppLog.put(jvmGetString("mpv_open_in_browser_warn"), toast = true)
                    }
                }
            }
        }
    }
}

/** 下载进度条 (确定进度显示百分比; [progress] < 0 时走不确定动画, 用于解压等无长度阶段) */
@Composable
private fun MpvDownloadProgress(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        if (progress < 0f) {
            LinearProgressIndicator(
                color = Color(0xFF165DFF),
                backgroundColor = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.width(240.dp),
            )
        } else {
            LinearProgressIndicator(
                progress = progress,
                color = Color(0xFF165DFF),
                backgroundColor = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.width(240.dp),
            )
        }
        Text(
            text = if (progress < 0f) {
                rememberString("mpv_downloading")
            } else {
                jvmGetString("mpv_downloading_percent", (progress * 100).toInt().toString())
            },
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 引导占位的动作按钮 (蓝字 + 圆角点击域, 与 ErrorOverlay reload 按钮同风格) */
@Composable
private fun GuideActionText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color(0xFF165DFF),
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 加载/错误细状态条 (黑底, 置于章节控制栏上方; 错误优先于加载显示) */
@Composable
private fun MpvStatusStrip(
    error: String?,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    when {
        error != null -> Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF000000))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error,
                color = Color(0xFFFF6B6B),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = rememberString("reload"),
                color = Color(0xFF165DFF),
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        loading -> Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF000000))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = rememberString("loading"),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 底部章节控制栏: 上一章 / 进度 / [ResolutionButton] (多分辨率时) / 下一章。
 *
 * 对照 shared [io.legado.app.ui.book.video.VideoControlBar] 布局, 因 mpv OSC 接管了
 * 原 Compose 控制层, 分辨率切换入口从控制层迁到本栏。
 */
@Composable
private fun MpvChapterBar(
    curIndex: Int,
    size: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    resolutions: List<VideoResolution>,
    currentResolutionIndex: Int,
    onSwitchResolution: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF000000))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = curIndex > 0) {
            Icon(
                painter = rememberPainter("ic_skip_previous"),
                contentDescription = rememberString("previous_chapter"),
                tint = if (curIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        Text(
            text = "${curIndex + 1}/$size",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        if (resolutions.size > 1) {
            ResolutionButton(
                resolutions = resolutions,
                currentResolutionIndex = currentResolutionIndex,
                onSwitchResolution = onSwitchResolution,
            )
        }
        IconButton(onClick = onNext, enabled = curIndex < size - 1) {
            Icon(
                painter = rememberPainter("ic_skip_next"),
                contentDescription = rememberString("next_chapter"),
                tint = if (curIndex < size - 1) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

/** 系统默认程序打开 URL (Desktop.browse; 无图形环境时回退控制台打印) */
private fun browse(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            return
        }
        println("[OpenUrl] $url")
    }.onFailure {
        AppLog.put("打开链接失败: $url\n${it.message}", it)
    }
}
