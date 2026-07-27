package io.legado.desktop.ui.book.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.VideoResolution
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.ui.book.video.VideoPlayViewModelShared
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopPreferenceStoreProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.PreferenceStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.theme.AppTheme
import java.io.File
import java.util.UUID
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import javax.swing.JPanel
import kotlin.math.abs

/**
 * 桌面端视频播放 Screen 入口 (对照 app 端 [io.legado.app.ui.book.video.VideoPlayActivity]
 * + [io.legado.app.ui.book.video.VideoPlayScreen])。
 *
 * # 职责
 *
 * - 包装 [VideoPlayViewModelShared] 持有章节视频 URL 列表状态
 * - 注入 desktop 平台 Provider (与 [io.legado.desktop.ui.about.AboutScreen] 一致 4 个 Provider)
 * - 顶部标题栏 (返回 + 书名 + 章节名 + 目录 + 换源, 对照 app 端 VideoPlayActivity 标题栏)
 * - 中间视频播放区 (vlcj [EmbeddedMediaPlayerComponent] 经 [SwingPanel] 嵌入 AWT 渲染视频)
 * - 播放控制层 (播放/暂停 + 进度条 + 倍速 + 分辨率切换 + 单击显隐, 对照 app 端 VideoControlsOverlay)
 * - 底部章节控制栏 (上一章 / 进度 / 下一章, 对照 app 端 VideoPlayActivity 底部控制)
 * - 加载/错误覆盖层 (对照 app 端 ReadVideoActivity LoadingOverlay)
 *
 * # 与 app 端差异
 *
 * - **播放器**: app 端用 ExoPlayer (androidx.media3) 渲染视频 + 控制层 (Player.Listener
 *   回喂 isPlaying/playbackState/playbackSpeed); desktop 端 JVM 用 vlcj (VLC 官方 Java
 *   绑定, JNA → libvlc), 经 [SwingPanel] 嵌入 AWT 渲染视频, 通过
 *   [MediaPlayerEventAdapter] 回喂 isPlaying/position/duration
 * - **手势**: app 端 VideoGestureHandler 处理单击(显隐控制层)/双击(播放暂停)/长按(2X 倍速)/
 *   水平拖动(进度)/左侧垂直拖动(亮度)/右侧垂直拖动(音量); desktop 端用鼠标交互, 仅实现
 *   单击显隐控制层 + 进度条拖动 seek (桌面端无亮度/音量手势概念, 倍速走按钮)
 * - **分辨率切换**: app 端用 TrackSelectionDialogBuilder / singleChoiceItems 弹窗;
 *   desktop 端用 [AlertDialog] + 单选 (对照 app 端 [io.legado.app.ui.book.video.VideoPlayActivity.showResolutionDialog])
 * - **全屏**: app 端按横竖屏互切 + toggleSystemBar; desktop 端窗口模式无横竖屏概念,
 *   全屏由窗口管理控制 (不在本 Screen 职责内)
 * - **进度持久化**: app 端 saveRead(position) 写 book.durChapterPos; desktop 端用
 *   [PreferenceStoreProvider] 存 `video_progress_{bookUrl}` (内存 Map, 进程级),
 *   打开时 vlcj `:start-time=` 恢复, 退出 / 切章时落存 (与 [VideoPlayViewModelShared.saveVideoProgress] 一致)
 * - **VLC native 库**: vlcj 运行时需用户机器已安装 VLC media player, 通过 NativeDiscovery
 *   自动查找 libvlc.{dll|so|dylib}; 不引入 native 库打包配置 (VLC 太大 ~200MB),
 *   后续可走 jpackage appResourcesRootDir 模式纳入本地 VLC 副本 (标 TODO)
 *
 * # 路由接入
 *
 * 由 [io.legado.desktop.ui.DesktopApp] 的 `DesktopRoute.VIDEO_PLAYER` 分支消费,
 * 触发点: 书架/详情/目录点击 `book.isVideo` 类型书籍 (与 `book.isAudio`/`book.isImage`
 * 平级分流)。
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
    // 注入 desktop 平台 Provider (参照 MangaReaderScreen 第 123-134 行 CompositionLocalProvider 模式)
    // 供 AppTheme / rememberString / PreferenceScreen 等通过 LocalXxx 取依赖
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
 * 视频播放主体内容 (对照 app 端 VideoPlayActivity.Content + VideoPlayScreen)。
 *
 * 注: 拆出顶层 Composable 是为在 [CompositionLocalProvider] + [AppTheme] 包裹后消费
 * LocalXxx (如 [LocalPreferenceStoreProvider] 当前实例), 与 MangaReaderScreen 一致。
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
    // VM 记忆 (避免重组重建, 与 MangaReaderScreen 中 MangaReaderViewModelShared remember 一致)
    // scope 由 rememberCoroutineScope 注入, Composable 离开组合时自动取消
    // (对照 MangaReaderScreen 第 163-165 行, 不用 MainScope 避免手动管理生命周期)
    val scope = rememberCoroutineScope()
    val viewModel = remember { VideoPlayViewModelShared(scope, prefStore) }

    // 收集 VM 状态 (Compose 经 collectAsState 订阅, StateFlow 有初始值故无需传默认值)
    val videoUrl by viewModel.videoUrl.collectAsState()
    val videoSource by viewModel.videoSource.collectAsState()
    val resolutions by viewModel.resolutions.collectAsState()
    val curChapterIndex by viewModel.curChapterIndex.collectAsState()
    val chapterSize by viewModel.chapterSize.collectAsState()
    val curChapterTitle by viewModel.curChapterTitle.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // 上次播放位置 (对照 app 端 initData: position = curBook.durChapterPos)
    // remember(book.bookUrl): 换书重读; 进程内切章不重读 (切章已由 VM 重置为 0, 由退出时落存覆盖)
    val initialPositionMs = remember(book.bookUrl) { viewModel.getSavedVideoProgress(book.bookUrl) }

    // 初始化数据 (装载书 + 章节列表 + 加载首章, 对照 app 端 VideoPlayActivity.onActivityCreated -> initData)
    LaunchedEffect(book.bookUrl) {
        viewModel.initData(book, chapterIndex)
    }
    // 注: 不需要 DisposableEffect 取消 scope, rememberCoroutineScope 离开组合时自动取消
    // (VideoPlayViewModelShared 无独立 downloadScope, 所有 launch 派生于注入的 scope)

    Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题栏 (返回 + 书名/章节名 + 目录 + 换源, 对照 app 端 VideoPlayActivity 标题栏)
            VideoTitleBar(
                bookName = book.name,
                chapterTitle = curChapterTitle,
                onBack = onBack,
                onOpenToc = { onOpenToc(book) },
                onOpenChangeSource = { onOpenChangeSource(book) },
            )
            // 中间视频播放区 (vlcj 渲染面 + 控制层, 对照 app 端 VideoPlayScreen Box 渲染区)
            Box(Modifier.fillMaxSize().weight(1f)) {
                VideoRenderArea(
                    videoUrl = videoUrl,
                    hasMultiResolution = videoSource != null && resolutions.size > 1,
                    resolutions = resolutions,
                    currentResolutionIndex = viewModel.currentResolutionIndex,
                    loading = loading,
                    error = error,
                    initialPositionMs = initialPositionMs,
                    onRetry = { viewModel.refreshChapter() },
                    onPlayError = { viewModel.retryOnPlayError() },
                    onSwitchResolution = { idx -> viewModel.switchResolution(idx) },
                    onNextChapter = { viewModel.moveToNextChapter() },
                    onSaveProgressOnExit = { pos, dur -> viewModel.saveVideoProgressOnExit(pos, dur) },
                )
            }
            // 底部章节控制栏 (上一章 / 进度 / 下一章, 对照 app 端 VideoPlayActivity 底部控制)
            VideoControlBar(
                curIndex = curChapterIndex,
                size = chapterSize,
                onPrev = { viewModel.moveToPrevChapter() },
                onNext = { viewModel.moveToNextChapter() },
            )
        }
    }
}

// ---- 顶部标题栏 (对照 app 端 VideoPlayActivity 标题栏 + MangaReaderScreen.MangaTitleBar 风格) ----

/**
 * 视频标题栏 (56dp 高, 返回 + 书名/章节名 + 目录 + 换源)。
 *
 * 黑底白字, 与 [io.legado.desktop.ui.book.manga.MangaReaderScreen] 的 MangaTitleBar
 * 视觉一致 (视频 Screen 全屏黑底, AppTitleBar Surface 风格不匹配)。
 */
@Composable
private fun VideoTitleBar(
    bookName: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = rememberPainter("ic_arrow_back"),
                contentDescription = rememberString("back"),
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = bookName,
                color = Color.White,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapterTitle.isNotEmpty()) {
                Text(
                    text = chapterTitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenToc) {
            Icon(
                painter = rememberPainter("ic_toc"),
                contentDescription = rememberString("chapter_list"),
                tint = Color.White,
            )
        }
        IconButton(onClick = onOpenChangeSource) {
            Icon(
                painter = rememberPainter("ic_exchange"),
                contentDescription = rememberString("change_source"),
                tint = Color.White,
            )
        }
    }
}

// ---- 渲染区 (对照 app 端 VideoPlayScreen 渲染面 + VideoSurface + VideoControlsOverlay) ----

/**
 * 视频渲染区: vlcj [EmbeddedMediaPlayerComponent] 经 [SwingPanel] 嵌入 AWT 渲染视频,
 * 上叠手势层(单击显隐控制层) + 控制层(播放/暂停/进度/倍速/分辨率)。
 *
 * # vlcj 集成
 *
 * - **创建**: [SwingPanel] factory 在 AWT EDT 上 new [EmbeddedMediaPlayerComponent],
 *   成功后写入 `playerComponent` State 触发重组; 失败 (VLC native 库未安装) 写入
 *   `vlcInitError` 显示安装提示
 * - **事件**: [DisposableEffect](playerComponent) 在组件就绪后挂 [MediaPlayerEventAdapter],
 *   回喂 isPlaying/position/duration/finished/error 到 Compose State; onDispose 移除监听
 * - **加载**: [LaunchedEffect](videoUrl, playerComponent) 在 URL 就绪且播放器就绪后调
 *   `mediaPlayer.media().play(url, *options)`, options 包含 HTTP 头部 + network-caching
 *   + 首次加载 `:start-time=<秒>` 恢复上次播放位置 (对照 app 端 `player.seekTo(position)`)
 * - **释放**: [DisposableEffect](Unit).onDispose 先落存当前位置 (对照 app 端 onPause
 *   `viewModel.saveRead(position)`), 再 stop + release (Screen 退出时)
 *
 * # 内存 m3u8 支持
 *
 * vlcj 不能直接播放 m3u8 字符串内容 (需要 URL 或文件路径)。app 端用 ByteArrayDataSource
 * 加载内存 m3u8; desktop 端写临时文件 + vlcj 播放文件路径实现等价效果, 临时文件在
 * onDispose 中清理。
 *
 * @param videoUrl 当前播放视频源 (含 URL + header), null = 加载中或失败
 * @param hasMultiResolution 是否多分辨率源 (控制分辨率按钮显隐)
 * @param resolutions 分辨率列表
 * @param currentResolutionIndex 当前分辨率索引
 * @param loading 加载中标记
 * @param error 加载失败消息
 * @param initialPositionMs 打开视频时恢复的播放位置 (毫秒, 0=从头播; 仅首次加载生效)
 * @param onRetry 重试回调 (ErrorOverlay 用户手动重试用)
 * @param onPlayError 播放器错误重试回调 (返回 true 已重试, false 已重试过)
 * @param onSwitchResolution 切换分辨率回调 (索引)
 * @param onNextChapter 播放结束切下一章回调
 * @param onSaveProgressOnExit 退出时保存进度回调 (片尾归零由 shared VM 处理)
 */
@Composable
private fun VideoRenderArea(
    videoUrl: AnalyzeUrlCore?,
    hasMultiResolution: Boolean,
    resolutions: List<VideoResolution>,
    currentResolutionIndex: Int,
    loading: Boolean,
    error: String?,
    initialPositionMs: Long,
    onRetry: () -> Unit,
    onPlayError: () -> Boolean,
    onSwitchResolution: (Int) -> Unit,
    onNextChapter: () -> Unit,
    onSaveProgressOnExit: (Long, Long) -> Unit,
) {
    // vlcj 播放器实例 (在 AWT EDT 创建, 写入 State 触发重组挂监听)
    var playerComponent by remember { mutableStateOf<EmbeddedMediaPlayerComponent?>(null) }
    // VLC native 库初始化错误 (用户未装 VLC 时显示安装提示)
    var vlcInitError by remember { mutableStateOf<String?>(null) }
    // 内存 m3u8 临时文件 (vlcj 4.x 无 ByteArrayDataSource 等价 API, 临时文件是最简方案)
    // cache 目录 + shutdown hook 兜底清理 (防异常退出残留)
    val tempCacheDir = remember {
        val dir = File(System.getProperty("java.io.tmpdir"), "legado_video")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    var tempFile by remember { mutableStateOf<File?>(null) }

    // ---- 播放控制状态 (vlcj 事件回喂 + UI 订阅, 对照 app 端 Player.Listener 回喂) ----
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    // 控制层显隐 (对照 app 端 controlsVisible, 默认显示)
    var controlsVisible by remember { mutableStateOf(true) }
    // 首次加载是否已恢复播放位置 (避免切章时误 seek 到旧位置; 对照 app 端 initData 一次性读 position)
    var hasRestoredInitialPosition by remember { mutableStateOf(false) }

    // URL 变化时重置播放进度 (对照 app 端 refreshPlayer 切换 media source)
    LaunchedEffect(videoUrl) {
        positionMs = 0L
        durationMs = 0L
        isPlaying = false
    }

    // Screen 退出时落存播放进度 + 释放 vlcj 资源 (对照 app 端 onPause: saveRead(position) + onDestroy: release)
    DisposableEffect(Unit) {
        onDispose {
            // 片尾归零 + 落存进度委托 shared VM
            onSaveProgressOnExit(positionMs, durationMs)
            // 清理内存 m3u8 临时文件
            tempFile?.delete()
            try {
                playerComponent?.mediaPlayer()?.controls()?.stop()
                playerComponent?.release()
            } catch (e: Throwable) {
                AppLog.put(jvmGetString("video_release_vlcj_error_log", e.message), e)
            }
        }
    }

    // 挂 vlcj 事件监听 (playerComponent 创建后挂一次, onDispose 移除; 对照 app 端 uiListener)
    DisposableEffect(playerComponent) {
        val pc = playerComponent
        var listener: MediaPlayerEventAdapter? = null
        if (pc != null) {
            listener = object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    isPlaying = true
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    isPlaying = false
                }

                override fun stopped(mediaPlayer: MediaPlayer) {
                    isPlaying = false
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    // 播放结束: 切下一章 (对照 app 端 onPlaybackStateChanged STATE_ENDED)
                    isPlaying = false
                    onNextChapter()
                }

                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    positionMs = newTime.coerceAtLeast(0L)
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    durationMs = newLength.coerceAtLeast(0L)
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    // 错误重试委托 shared VM (首次重试一次)
                    val retried = onPlayError()
                    if (!retried) {
                        AppLog.put(jvmGetString("video_play_error_vlcj_event"), toast = true)
                    }
                }
            }
            pc.mediaPlayer().events().addMediaPlayerEventListener(listener)
        }
        onDispose {
            listener?.let { pc?.mediaPlayer()?.events()?.removeMediaPlayerEventListener(it) }
        }
    }

    // URL 变化时加载新视频 (对照 app 端 refreshPlayer: setMediaSource + prepare + play)
    LaunchedEffect(videoUrl, playerComponent) {
        val pc = playerComponent ?: return@LaunchedEffect
        val url = videoUrl ?: return@LaunchedEffect
        if (vlcInitError != null) return@LaunchedEffect
        // 构造 VLC media options: HTTP 头部 (User-Agent / Referer / Cookie 等)
        // VLC 选项格式: ":http-header=Key: Value\nKey2: Value2" (\n 分隔多个 header)
        // :network-caching=3000: 网络缓存 3 秒, 避免 HLS 切片加载抖动
        val headerStr = url.headerMap.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("\n") { "${it.key}: ${it.value}" }
        // 首次加载恢复上次播放位置 (对照 app 端 setPlayerMediaSource 后 `if (position != 0L) seekTo`)
        // vlcj 用 `:start-time=<秒>` 选项 (浮点秒), 仅首次加载生效 (切章不恢复)
        val needRestore = !hasRestoredInitialPosition && initialPositionMs > 0
        // 内存 m3u8 (url.url 非 http 开头): 写临时文件供 vlcj 播放 (vlcj 4.x 无 ByteArrayDataSource 等价 API)
        // http(s) 直链: 直接 play url; 本地文件场景 http-header 无意义, 省略
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
        val options = buildList {
            if (!isMemoryM3u8 && headerStr.isNotEmpty()) add(":http-header=$headerStr")
            add(":network-caching=3000")
            if (needRestore) add(":start-time=${initialPositionMs / 1000.0}")
        }
        runCatching {
            pc.mediaPlayer().media().play(playPath, *options.toTypedArray())
            // play 成功后标记已恢复, 避免切章时再次 seek 到旧位置
            if (needRestore) hasRestoredInitialPosition = true
        }.onFailure {
            AppLog.put(jvmGetString("video_load_error_log", it.message), it)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        // vlcj 渲染面 (SwingPanel 嵌入 AWT, factory 在 EDT 创建; 仅在 videoUrl 就绪 + 无错时显示)
        if (videoUrl != null && error == null && vlcInitError == null) {
            SwingPanel(
                factory = {
                    try {
                        EmbeddedMediaPlayerComponent().also { pc ->
                            playerComponent = pc
                        }
                    } catch (e: Throwable) {
                        // VLC native 库未安装 / 加载失败, 显示安装提示 (不抛出避免 Screen 崩溃)
                        vlcInitError = jvmGetString(
                            "vlc_init_failed",
                            e.message ?: e.javaClass.simpleName,
                        )
                        AppLog.put(jvmGetString("video_vlcj_init_failed_log", e.message), e, true)
                        JPanel() // 空面板避免 SwingPanel factory 返回 null
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 手势层: 单击显隐控制层 (透明 Box 盖在 SwingPanel 上方, 拦截鼠标事件)
            // 对照 app 端 VideoGestureOverlay.onSingleTap -> controlsVisible = !controlsVisible
            // 注: vlcj Canvas 不需要鼠标交互 (视频播放控制全在控制层), 故 Compose 拦截无副作用
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                    }
            )

            // 控制层 (播放/暂停/进度/倍速/分辨率, 对照 app 端 VideoControlsOverlay)
            VideoControlsOverlay(
                visible = controlsVisible,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                hasMultiResolution = hasMultiResolution,
                resolutions = resolutions,
                currentResolutionIndex = currentResolutionIndex,
                onPlayPause = {
                    playerComponent?.let { pc ->
                        runCatching {
                            if (pc.mediaPlayer().status().isPlaying()) {
                                pc.mediaPlayer().controls().pause()
                            } else {
                                pc.mediaPlayer().controls().play()
                            }
                        }.onFailure {
                            AppLog.put(jvmGetString("video_play_pause_error_log", it.message), it)
                        }
                    }
                },
                onSeek = { timeMs ->
                    playerComponent?.let { pc ->
                        runCatching { pc.mediaPlayer().controls().setTime(timeMs) }
                            .onFailure {
                                AppLog.put(jvmGetString("video_seek_error_log", it.message), it)
                            }
                    }
                },
                onSpeedChange = { speed ->
                    playerComponent?.let { pc ->
                        runCatching {
                            pc.mediaPlayer().controls().setRate(speed)
                            playbackSpeed = speed
                        }.onFailure {
                            AppLog.put(jvmGetString("video_speed_error_log", it.message), it)
                        }
                    }
                },
                onSwitchResolution = onSwitchResolution,
            )
        }

        // 加载/错误覆盖层 (顶层, 不被控制层遮挡; 对照 app 端 LoadingOverlay + retryVisible)
        when {
            vlcInitError != null -> ErrorOverlay(error = vlcInitError!!, onRetry = onRetry)
            error != null && !loading -> ErrorOverlay(error = error, onRetry = onRetry)
            loading -> LoadingOverlay()
            videoUrl == null -> LoadingOverlay() // 初始态 (initData 未完成)
        }
    }
}

// ---- 播放控制层 (对照 app 端 VideoControlsOverlay) ----

/**
 * 视频控制层: 中央播放/暂停钮 + 底部进度条/倍速/分辨率 (对照 app 端 VideoControlsOverlay)。
 *
 * @param visible 控制层显隐 (单击视频区切换)
 * @param isPlaying 播放中标记 (回显播放/暂停钮图标)
 * @param positionMs 当前位置 ms
 * @param durationMs 总时长 ms
 * @param playbackSpeed 倍速 (回显倍速钮文字)
 * @param hasMultiResolution 是否多分辨率源 (控制分辨率钮显隐)
 * @param resolutions 分辨率列表
 * @param currentResolutionIndex 当前分辨率索引
 * @param onPlayPause 播放/暂停回调
 * @param onSeek 进度跳转回调 (ms)
 * @param onSpeedChange 倍速变更回调
 * @param onSwitchResolution 切换分辨率回调 (索引)
 */
@Composable
private fun VideoControlsOverlay(
    visible: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    hasMultiResolution: Boolean,
    resolutions: List<VideoResolution>,
    currentResolutionIndex: Int,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSwitchResolution: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // 整层压暗 (对照 app 端 exo_controls_background: exo_black_opacity_60 = 0x98000000)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x98000000))
        ) {
            // 中央播放/暂停钮 (对照 app 端 CenterControls.PlayPauseButton)
            PlayPauseButton(
                isPlaying = isPlaying,
                onClick = onPlayPause,
                modifier = Modifier.align(Alignment.Center),
            )
            // 底部进度条 + 倍速 + 分辨率 (对照 app 端 VideoControlsOverlay 底部 Row)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                VideoSeekBar(
                    value = positionMs,
                    max = durationMs,
                    activeColor = MaterialTheme.colorScheme.primary,
                    onSeek = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(25.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 当前位置 / 总时长 (对照 app 端 "%s / %s".format(...))
                    Text(
                        text = "%s / %s".format(
                            positionMs.toDurationTime(),
                            durationMs.toDurationTime(),
                        ),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    // 倍速钮 (对照 app 端 SpeedButton)
                    SpeedButton(
                        currentSpeed = playbackSpeed,
                        onSpeedChange = onSpeedChange,
                    )
                    // 分辨率钮 (对照 app 端 resolutionText, 多分辨率时显示当前分辨率名)
                    if (hasMultiResolution) {
                        ResolutionButton(
                            resolutions = resolutions,
                            currentResolutionIndex = currentResolutionIndex,
                            onSwitchResolution = onSwitchResolution,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 中央播放/暂停钮 (对照 app 端 PlayPauseButton: 64dp 圆形白底 + 黑色图标)。
 *
 * 播放中显示 ic_pause_24dp, 暂停/未播放显示 ic_play_24dp (资源对齐 AudioPlayScreen)。
 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(if (isPlaying) "ic_pause_24dp" else "ic_play_24dp"),
            contentDescription = if (isPlaying) rememberString("pause") else rememberString("play"),
            tint = Color.Black,
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * 自绘视频进度条 (对照 app 端 VideoSeekBar: 背景 + 已播层 + thumb, 支持点击/拖动 seek)。
 *
 * 桌面端简化: 不显示缓冲层 (vlcj 缓冲进度获取需要额外 API, 暂不实现); 鼠标点击/拖动跳转。
 *
 * @param value 当前位置 ms
 * @param max 总时长 ms
 * @param activeColor 已播层 + thumb 颜色
 * @param onSeek seek 回调 (ms), 拖动结束 + 点击时触发
 */
@Composable
private fun VideoSeekBar(
    value: Long,
    max: Long,
    activeColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = max.coerceAtLeast(1L)

    // 拖动中的预览值 (拖动期间不回显 vlcj 事件进度, 对照 app 端 dragMs)
    var dragValue by remember { mutableStateOf<Long?>(null) }
    val displayValue = dragValue ?: value

    fun fractionToValue(fraction: Float): Long =
        (fraction * range).toLong().coerceIn(0L, range)

    Box(
        modifier
            .pointerInput(max) {
                detectTapGestures(onTap = { pos ->
                    val target = fractionToValue(pos.x / size.width)
                    onSeek(target)
                })
            }
            .pointerInput(max) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragValue?.let { onSeek(it) }
                        dragValue = null
                    },
                    onDragCancel = { dragValue = null },
                ) { change, _ ->
                    change.consume()
                    dragValue = fractionToValue(change.position.x / size.width)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val thumbR = 8.dp.toPx()
            val trackH = 2.dp.toPx()
            val cy = size.height / 2
            val startX = thumbR
            val endX = size.width - thumbR
            val playFrac = (displayValue.toFloat() / range).coerceIn(0f, 1f)
            val playX = startX + (endX - startX) * playFrac
            // 进度背景 (对照 app 端 0xB3FFFFFF)
            drawLine(Color(0xB3FFFFFF), Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            // 已播层
            drawLine(activeColor, Offset(startX, cy), Offset(playX, cy), trackH, StrokeCap.Round)
            // thumb
            drawCircle(activeColor, thumbR, Offset(playX, cy))
        }
    }
}

/**
 * 倍速钮 (对照 app 端 SpeedButton: 文字 + DropdownMenu, 0.5x/1x/1.5x/2x 选项)。
 *
 * 注: 桌面端用 [DropdownMenu] 替代 app 端 AlertDialog (桌面交互更轻量); 选项集对照
 * 任务要求 0.5x/1x/1.5x/2x (app 端是 0.25~2x 7 档, 桌面端按任务要求简化为 4 档)。
 */
@Composable
private fun SpeedButton(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = speedLabel(currentSpeed),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(12.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            speedLabel(speed),
                            color = if (abs(speed - currentSpeed) < 0.01f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSpeedChange(speed)
                    },
                )
            }
        }
    }
}

/**
 * 分辨率钮 (对照 app 端 [io.legado.app.ui.book.video.VideoPlayActivity.showResolutionDialog])。
 *
 * 显示当前分辨率名, 点击弹 [AlertDialog] 单选; 切换分辨率后由 VM 更新 [videoUrl] 触发重载。
 */
@Composable
private fun ResolutionButton(
    resolutions: List<VideoResolution>,
    currentResolutionIndex: Int,
    onSwitchResolution: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentName = resolutions.getOrNull(currentResolutionIndex)?.name ?: rememberString("resolution")
    Text(
        text = currentName,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { showDialog = true }
            .padding(12.dp),
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(rememberString("resolution")) },
            text = {
                Column {
                    resolutions.forEachIndexed { index, resolution ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDialog = false
                                    if (index != currentResolutionIndex) {
                                        onSwitchResolution(index)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == currentResolutionIndex,
                                onClick = {
                                    showDialog = false
                                    if (index != currentResolutionIndex) {
                                        onSwitchResolution(index)
                                    }
                                },
                            )
                            Text(
                                text = resolution.name,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(rememberString("cancel"))
                }
            },
        )
    }
}

/** 倍速文字格式 (对照 app 端 speedLabel: 去尾零 + "X", 如 1X / 1.5X / 0.5X)。 */
private fun speedLabel(speed: Float): String =
    speed.toBigDecimal().stripTrailingZeros().toPlainString() + "X"

/** 毫秒 → mm:ss / h:mm:ss 时长格式 (对照 app 端 [io.legado.app.utils.toDurationTime])。 */
private fun Long.toDurationTime(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

// ---- 加载/错误覆盖层 (对照 app 端 VideoPlayActivity LoadingOverlay + MangaReaderScreen) ----

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = rememberString("loading"),
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ErrorOverlay(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = error, color = Color.White, textAlign = TextAlign.Center)
            Text(
                text = rememberString("reload"),
                color = Color(0xFF165DFF),
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- 底部章节控制栏 (对照 app 端 VideoPlayActivity 底部控制 + MangaReaderScreen.MangaControlBar) ----

/**
 * 底部章节控制栏: 上一章 / 进度 / 下一章。
 *
 * 黑底白字, 与标题栏风格一致; 进度文本格式 "当前章/总章" (对照 app 端 exo_prev/next + 章节信息)。
 *
 * 注: 播放控制 (播放/暂停/进度条/倍速/分辨率) 在 [VideoControlsOverlay] (视频区上方叠加),
 * 此处仅章节级控制 (与 app 端 VideoPlayActivity 底部 exo_prev/next 一致)。
 */
@Composable
private fun VideoControlBar(
    curIndex: Int,
    size: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF000000))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上一章 (对照 app 端 exo_prev: playPrevChapter)
        IconButton(onClick = onPrev, enabled = curIndex > 0) {
            Icon(
                painter = rememberPainter("ic_skip_previous"),
                contentDescription = rememberString("previous_chapter"),
                tint = if (curIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        Text(
            text = "${curIndex + 1}/${size}",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // 下一章 (对照 app 端 exo_next: playNextChapter)
        IconButton(onClick = onNext, enabled = curIndex < size - 1) {
            Icon(
                painter = rememberPainter("ic_skip_next"),
                contentDescription = rememberString("next_chapter"),
                tint = if (curIndex < size - 1) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

// ---- 调试用: 捕获未处理异常 (对照 app 端 AppLog.put) ----

/**
 * 记录未处理异常到 AppLog (供调试 vlcj 接入时的异常排查)。
 *
 * 注: 此函数当前未被调用, 预留给 vlcj 接入后的播放器错误回调使用。
 */
@Suppress("unused")
private fun logPlayerError(message: String, throwable: Throwable? = null) {
    AppLog.put(jvmGetString("video_play_error_log", message), throwable, true)
}
