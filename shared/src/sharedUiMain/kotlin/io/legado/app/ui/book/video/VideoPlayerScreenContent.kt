package io.legado.app.ui.book.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.VideoResolution
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.handleMediaKeys
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.format
import kotlinx.coroutines.delay
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.full_screen
import legado.shared.generated.resources.ic_fast_forward
import legado.shared.generated.resources.ic_fast_rewind
import legado.shared.generated.resources.ic_fullscreen_enter
import legado.shared.generated.resources.ic_fullscreen_exit
import legado.shared.generated.resources.ic_skip_next
import legado.shared.generated.resources.ic_skip_previous
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.pause
import legado.shared.generated.resources.play
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.reload
import legado.shared.generated.resources.resolution
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * 手机 vs 宽屏断点: 容器宽 < DesignTokens.wideScreenMinWidth 视为手机 (宽边判定, 对齐音频页
 * maxWidth >= 600dp)。宽屏 (宽 ≥600dp) 无论横竖都带选集列表 (横排 视频+列表 / 竖排 视频+下列表);
 * 窄横屏 (宽 <600 且高更小) 视频全屏不列列表。Android 手机横屏的全屏由平台层单独负责。
 */

/**
 * 视频播放页主体内容 (标题栏 + 渲染槽 + 选集网格), 逐项对照 app 端 VideoPlayScreen。
 *
 * 平台渲染层通过 [videoRenderSlot] 注入: desktop 传 SwingPanel(AWT Canvas, mpv --wid 嵌入,
 * 控制层用 mpv 内建 OSC); app 传 AndroidView(PlayerView) + 自有控件层。槽内自管
 * 渲染面 + 控件叠加 + 加载/错误态, 复用本文件导出的 Composable。
 *
 * 布局对照 app: 非全屏才显示标题栏; 手机横屏视频全屏不列列表; 手机竖屏与平板/桌面 (任意方向)
 * 视频最大高 2/3 + 下方选集网格; 全屏/无列表时视频撑满。
 *
 * 键盘事件: 最外层 Box 消费共享 handleMediaKeys (空格/←/→/↑/↓/Esc), 回调由调用方注入
 * (与 [VideoControlsOverlay] 按钮共用同一 lambda)。
 *
 * @param bookName 书名 (标题栏文字, 对照 Activity titleText)
 * @param curChapterIndex 当前章节索引 (0-based)
 * @param onBack 返回回调
 * @param onPrevChapter 上一章回调
 * @param onNextChapter 下一章回调
 * @param videoRenderSlot 平台渲染层槽 (接收 Modifier, 内部叠加控件/加载/错误)
 * @param onPlayPause 播放/暂停回调 (Spacebar 触发)
 * @param onSeekDelta 相对 seek 偏移 (←/→ 触发, 毫秒)
 * @param onSpeedChange 倍速切换 (长按 → 触发 2x, 松开恢复 1x)
 * @param controlsVisible 控制层可见状态 (键盘事件感知)
 * @param onToggleControls 显隐控制层 (Escape 触发)
 * @param onTitleClick 标题区点击回调 (对照 Activity onTitleClick)
 * @param titleActions 标题栏右侧 actions (由 Route 注入 refresh/shelf/overflowMenu)
 * @param isFullScreen 全屏态 (隐藏标题栏与选集网格, 对照 Activity isFullScreen)
 * @param chapters 章节列表 (选集网格数据源)
 * @param displayTitles 章节显示标题 (与 [chapters] 同序)
 * @param countWords 是否显示章节字数 (对照 AppConfig.tocCountWords)
 * @param onOpenChapter 选集点击回调 (章节索引)
 */
@Composable
fun VideoPlayerScreenContent(
    bookName: String,
    curChapterIndex: Int,
    onBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    videoRenderSlot: @Composable (Modifier) -> Unit,
    // 键盘事件回调 (默认空 lambda 保持向后兼容)
    onPlayPause: () -> Unit = {},
    onSeekDelta: (Long) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    // 手势/按键反馈文字 (键盘长按倍速 2.0X 等; 转发给 handleMediaKeys, 由渲染层经 ScreenModel 显示)
    onGestureText: (String?) -> Unit = {},
    controlsVisible: Boolean = false,
    onToggleControls: () -> Unit = {},
    // 平台自定义顶栏 (null = 用 shared VideoTitleBar; 传 {} 隐藏)
    topBarSlot: (@Composable () -> Unit)? = null,
    // 标题区点击 + 标题栏右侧 actions (仅 topBarSlot=null 时生效)
    onTitleClick: () -> Unit = {},
    titleActions: @Composable RowScope.() -> Unit = {},
    isFullScreen: Boolean = false,
    chapters: List<BookChapter> = emptyList(),
    displayTitles: List<String> = emptyList(),
    countWords: Boolean = false,
    onOpenChapter: (Int) -> Unit = {},
    // 容器背景 (对照 app: 页面走主题背景色, 黑底只在视频渲染区内)
    containerColor: Color = AppTheme.colors.background,
) {
    val scope = rememberCoroutineScope()
    // 键盘事件焦点: onPreviewKeyEvent 需焦点路径上有节点持焦才触发, 进入即取焦点
    // (desktop 端渲染槽另有 focusRequester, 后取焦者生效, 根节点 handler 均在焦点路径上)
    // requestFocus 一次性请求在组合初期可能失败, 循环重试兜底 (同音频页 AudioPlayScreenContent,
    // 用户拍板 2026-08: 避免"必须点一下界面才有键盘交互")
    val keyFocusRequester = remember { FocusRequester() }
    var keyFocusHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (keyFocusHeld) return@LaunchedEffect
            runCatching { keyFocusRequester.requestFocus() }
            delay(50)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(containerColor)
            // 键盘快捷键: 消费共享 handleMediaKeys
            // (Space=播放/暂停, ←/→=seek ∓10s, 长按 →=2x 倍速, ↑/↓=上/下一章;
            //  Esc/Backspace 走 Route onBack 多层级返回, 对齐安卓端 onBackPressedDispatcher:
            //  全屏→退出全屏 / 否则返回)
            .handleMediaKeys(
                onTogglePlayPause = onPlayPause,
                onSeekDelta = onSeekDelta,
                onPrev = onPrevChapter,
                onNext = onNextChapter,
                onSpeedChange = onSpeedChange,
                onGestureText = onGestureText,
                onBack = onBack,
                scope = scope,
            )
            .focusRequester(keyFocusRequester)
            .focusable()
            .onFocusChanged { keyFocusHeld = it.isFocused }
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!isFullScreen) {
                if (topBarSlot != null) {
                    topBarSlot()
                } else {
                    VideoTitleBar(
                        bookName = bookName,
                        onBack = onBack,
                        onTitleClick = onTitleClick,
                        actions = titleActions,
                    )
                }
            }
            val showGrid = !isFullScreen && chapters.size > 1
            if (showGrid) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    // 手机 vs 宽屏断点: 宽边 < wideScreenMinWidth 视为手机 (对齐音频页
                    // maxWidth >= 600dp 的宽屏判定; 矮横窗/手机横屏宽边 ≥600 不再被当手机)
                    val isPhone = maxWidth < DesignTokens.wideScreenMinWidth
                    when {
                        // 窄横屏 (宽 <600 且高更小): 视频全屏不列列表
                        isPhone && maxWidth > maxHeight ->
                            Box(Modifier.fillMaxSize()) {
                                videoRenderSlot(Modifier.matchParentSize())
                            }

                        // 平板/桌面横排: 左视频 + 右选集网格 (视频盒子 weight 占剩余, 网格固定窄栏)
                        // 视频上限用 maxWidth, 实际宽度由视频盒子 (容器宽 - 列表宽) 钳制;
                        // 不再用 60% 上限 —— 那会让视频比盒子窄、居中后左右留白
                        maxWidth > maxHeight ->
                            VideoBody(
                                vertical = false,
                                videoMaxHeight = maxHeight,
                                videoMaxWidth = maxWidth,
                                gridMaxWidth = (maxWidth * 0.35f)
                                    .coerceAtMost(DesignTokens.sidePanelMaxWidth),
                                chapters = chapters,
                                displayTitles = displayTitles,
                                durIndex = curChapterIndex,
                                countWords = countWords,
                                onOpenChapter = onOpenChapter,
                                videoRenderSlot = videoRenderSlot,
                            )

                        // 竖屏 (手机竖屏 / 平板竖屏): 上视频 + 下选集网格
                        else ->
                            VideoBody(
                                vertical = true,
                                videoMaxHeight = maxHeight * 2f / 3f,
                                videoMaxWidth = maxWidth,
                                chapters = chapters,
                                displayTitles = displayTitles,
                                durIndex = curChapterIndex,
                                countWords = countWords,
                                onOpenChapter = onOpenChapter,
                                videoRenderSlot = videoRenderSlot,
                            )
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    videoRenderSlot(Modifier.matchParentSize())
                }
            }
        }
    }
}

// ---- 视频区 + 选集网格 (横排/竖排共用) ----

/**
 * 视频渲染区与选集网格的组合布局。
 *
 * @param vertical true=竖排 (上视频下网格); false=横排 (左视频右网格)
 * @param videoMaxHeight 视频区可用的最大高度 (竖排时给网格让出空间)
 * @param videoMaxWidth 视频区可用的最大宽度 (横排时给网格让出空间)
 * @param gridMaxWidth 横排时网格固定宽度 (null = 与视频各占一半, 对照旧行为)
 */
@Composable
private fun VideoBody(
    vertical: Boolean,
    videoMaxHeight: Dp,
    videoMaxWidth: Dp,
    gridMaxWidth: Dp? = null,
    chapters: List<BookChapter>,
    displayTitles: List<String>,
    durIndex: Int,
    countWords: Boolean,
    onOpenChapter: (Int) -> Unit,
    videoRenderSlot: @Composable (Modifier) -> Unit,
) {
    // 按 16:9 在可用区域内收窄视频宽
    val videoWidth = minOf(videoMaxWidth, videoMaxHeight * 16f / 9f)
    val video: @Composable () -> Unit = {
        Box(Modifier.width(videoWidth).aspectRatio(16f / 9f)) {
            videoRenderSlot(Modifier.matchParentSize())
        }
    }
    val grid: @Composable () -> Unit = {
        VideoChapterGrid(
            chapters = chapters,
            displayTitles = displayTitles,
            durIndex = durIndex,
            onClick = onOpenChapter,
            countWords = countWords,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (vertical) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            video()
            Box(Modifier.weight(1f)) { grid() }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                video()
            }
            if (gridMaxWidth != null) {
                // 固定窄栏 (对照音频页侧栏): 网格在栏内按自身宽度自适应列数
                Box(Modifier.width(gridMaxWidth)) { grid() }
            } else {
                Box(Modifier.weight(1f)) { grid() }
            }
        }
    }
}

// ---- 顶部标题栏 ----

/** 视频标题栏 (对照 app 端 VideoPlayScreen: AppTitleBar + 标题区整体可点进书籍详情)。
 *
 *  @param onTitleClick 标题区点击回调, 由 Route 桥接 navigator.push(BookInfo)
 *  @param actions 右侧 action 区 (由 Route 注入 refresh/shelf/overflowMenu)
 */
@Composable
fun VideoTitleBar(
    bookName: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = AppTheme.colors
    AppTitleBar(
        title = "",
        onBack = onBack,
        titleContent = {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable { onTitleClick() },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = bookName,
                    color = colors.primaryText,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = actions,
    )
}

// ---- 播放控制层 ----

/**
 * 视频控制层: 中央播放/暂停钮 + 底部进度条/倍速/分辨率。
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
fun VideoControlsOverlay(
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
    // 颜色走 ThemeStore 动态色, 倍速档位对齐 app SpeedButton
    accentColor: Color = AppTheme.colors.accent,
    secondaryTextColor: Color = AppTheme.colors.primaryText,
    speeds: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f),
    controller: VideoControlsController? = null,
    bufferedMs: Long = 0L,
    onSeekDragStateChange: (Boolean) -> Unit = {},
    centerControls: @Composable (BoxScope.() -> Unit)? = null,
    leadingContent: @Composable (BoxScope.() -> Unit) = {},
    // 系统级全屏 (对照 app toggleOrientationFullscreen: 隐藏系统底栏/窗口装饰);
    // 与右上角菜单的窗口内全屏 (onToggleFullScreen) 区分
    isSystemFullScreen: Boolean = false,
    onToggleSystemFullScreen: () -> Unit = {},
    // 是否在底部控制栏渲染系统级全屏按钮 (desktop 传 true, app 用 trailingBottomContent 自行注入)
    showSystemFullScreenButton: Boolean = false,
    trailingBottomContent: @Composable (RowScope.() -> Unit) = {},
    enterTransition: EnterTransition = fadeIn(),
    exitTransition: ExitTransition = fadeOut(),
    modifier: Modifier = Modifier,
) {
    val effectivePositionMs = controller?.positionMs ?: positionMs
    val effectiveDurationMs = controller?.durationMs ?: durationMs
    val effectiveBufferedMs = controller?.bufferedMs ?: bufferedMs
    val effectiveBufferColor =
        if (controller != null) accentColor.copy(alpha = 0.5f)
        else Color.Unspecified
    AnimatedVisibility(
        visible = visible,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier,
    ) {
        // 整层压暗 (exo_controls_background: exo_black_opacity_60 = 0x98000000)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x98000000))
        ) {
            if (centerControls != null) {
                centerControls()
            } else {
                PlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = onPlayPause,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            leadingContent()
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                VideoSeekBar(
                    value = effectivePositionMs,
                    max = effectiveDurationMs,
                    activeColor = accentColor,
                    onSeek = onSeek,
                    buffered = effectiveBufferedMs,
                    bufferColor = effectiveBufferColor,
                    onDragStateChange = { dragging ->
                        if (controller != null) controller.seeking = dragging
                        else onSeekDragStateChange(dragging)
                    },
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
                    // 当前位置 / 总时长
                    Text(
                        text = "%s / %s".format(
                            effectivePositionMs.toDurationTime(),
                            effectiveDurationMs.toDurationTime(),
                        ),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    SpeedButton(
                        currentSpeed = playbackSpeed,
                        onSpeedChange = onSpeedChange,
                        speeds = speeds,
                        currentSpeedColor = accentColor,
                        otherSpeedColor = secondaryTextColor,
                    )
                    ResolutionButton(
                        resolutions = resolutions,
                        currentResolutionIndex = currentResolutionIndex,
                        onSwitchResolution = onSwitchResolution,
                    )
                    // 系统级全屏按钮 (desktop 传 showSystemFullScreenButton=true; app 用 trailingBottomContent)
                    if (showSystemFullScreenButton) {
                        IconButton(onClick = onToggleSystemFullScreen) {
                            Icon(
                                painter = painterResource(
                                    if (isSystemFullScreen) Res.drawable.ic_fullscreen_exit
                                    else Res.drawable.ic_fullscreen_enter
                                ),
                                contentDescription = stringResource(Res.string.full_screen),
                                tint = Color.White,
                            )
                        }
                    }
                    trailingBottomContent()
                }
            }
        }
    }
}

/**
 * 中央控制行 (原 exo_center_controls): 上一集 / 后退 / 播放暂停 / 前进 / 下一集。
 *
 * prev/next 图标 + desc 走 shared 资源 (rememberPainter/rememberString);
 * rewind/forward shared 无对应资源, 由调用方注入平台 painter + desc。
 *
 * @param rewindPainter 后退钮图标 (平台注入, 如 media3 exo_ic_rewind)
 * @param forwardPainter 前进钮图标 (平台注入, 如 media3 exo_ic_forward)
 * @param rewindDesc 后退钮 contentDescription (平台注入)
 * @param forwardDesc 前进钮 contentDescription (平台注入)
 */
@Composable
fun VideoCenterControls(
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit,
    rewindDesc: String,
    forwardDesc: String,
    // 平台注入 (如 media3 exo_ic_rewind); 默认用 shared 双三角图标
    rewindPainter: Painter = painterResource(Res.drawable.ic_fast_rewind),
    forwardPainter: Painter = painterResource(Res.drawable.ic_fast_forward),
    enabledPrev: Boolean = true,
    enabledNext: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoCircleIconButton(
            painter = painterResource(Res.drawable.ic_skip_previous),
            contentDescription = stringResource(Res.string.previous_chapter),
            enabled = enabledPrev,
            onClick = onPrev,
        )
        VideoCircleIconButton(
            painter = rewindPainter,
            contentDescription = rewindDesc,
            onClick = onSeekBack,
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = onPlayPause,
            iconTint = Color.White,
            iconSize = 48.dp,
            backgroundColor = Color.Transparent,
        )
        VideoCircleIconButton(
            painter = forwardPainter,
            contentDescription = forwardDesc,
            onClick = onSeekForward,
        )
        VideoCircleIconButton(
            painter = painterResource(Res.drawable.ic_skip_next),
            contentDescription = stringResource(Res.string.next_chapter),
            enabled = enabledNext,
            onClick = onNext,
        )
    }
}

/**
 * 通用圆形控制钮 (48dp 圆 + 32dp 图标), 对齐 app 端原 ControlIcon 视觉。
 */
@Composable
fun VideoCircleIconButton(
    painter: Painter,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(32.dp),
        )
    }
}

/**
 * 中央播放/暂停钮 (64dp 点击区 + 图标), 对照 app 端 PlayPauseButton (白图标 48dp, 无底色)。
 *
 * @param iconTint 图标 tint
 * @param iconSize 图标尺寸
 * @param backgroundColor 背景色 (默认透明, 同 app)
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White,
    iconSize: Dp = 48.dp,
    backgroundColor: Color = Color.Transparent,
) {
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(if (isPlaying) "ic_pause_24dp" else "ic_play_24dp"),
            contentDescription = if (isPlaying) stringResource(Res.string.pause) else stringResource(
                Res.string.play
            ),
            tint = iconTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 自绘视频进度条 (背景 + 缓冲层 + 已播层 + thumb, 支持点击/拖动 seek)。
 *
 * @param value 当前位置 ms
 * @param max 总时长 ms
 * @param activeColor 已播层 + thumb 颜色
 * @param onSeek seek 回调 (ms), 拖动结束 + 点击时触发
 * @param buffered 缓冲进度 ms (0 = 不绘制缓冲层)
 * @param bufferColor 缓冲层颜色 (Color.Unspecified = 不绘制)
 * @param onDragStateChange 拖动状态变更回调 (true=开始拖动, false=结束/取消), 用于调用方暂停自动隐藏等
 */
@Composable
fun VideoSeekBar(
    value: Long,
    max: Long,
    activeColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    buffered: Long = 0L,
    bufferColor: Color = Color.Unspecified,
    onDragStateChange: (Boolean) -> Unit = {},
) {
    val range = max.coerceAtLeast(1L)

    // 拖动中的预览值 (拖动期间不回显事件进度)
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
                    onDragStart = { onDragStateChange(true) },
                    onDragEnd = {
                        dragValue?.let { onSeek(it) }
                        dragValue = null
                        onDragStateChange(false)
                    },
                    onDragCancel = {
                        dragValue = null
                        onDragStateChange(false)
                    },
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
            // 进度背景
            drawLine(Color(0xB3FFFFFF), Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            // 缓冲层 (bufferColor 显式指定 + buffered > 0 时绘制)
            if (bufferColor != Color.Unspecified && buffered > 0L) {
                val bufFrac = (buffered.toFloat() / range).coerceIn(0f, 1f)
                val bufX = startX + (endX - startX) * bufFrac
                if (bufX > startX) {
                    drawLine(bufferColor, Offset(startX, cy), Offset(bufX, cy), trackH, StrokeCap.Round)
                }
            }
            // 已播层
            drawLine(activeColor, Offset(startX, cy), Offset(playX, cy), trackH, StrokeCap.Round)
            // thumb
            drawCircle(activeColor, thumbR, Offset(playX, cy))
        }
    }
}

/**
 * 倍速钮 (文字 + AppDropdownMenu, 可配置档位/颜色)。
 *
 * @param speeds 倍速档位 (默认对齐 app 端 7 档)
 * @param currentSpeedColor 当前选中档位文字色
 * @param otherSpeedColor 未选中档位文字色
 */
@Composable
fun SpeedButton(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    speeds: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f),
    currentSpeedColor: Color = AppTheme.colors.accent,
    otherSpeedColor: Color = AppTheme.colors.primaryText,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = speedLabel(currentSpeed),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(DesignTokens.shapeSm)
                .clickable { expanded = true }
                .padding(12.dp),
        )
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            speeds.forEach { speed ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSpeedChange(speed)
                    },
                ) {
                    Text(
                        speedLabel(speed),
                        color = if (abs(speed - currentSpeed) < 0.01f) {
                            currentSpeedColor
                        } else {
                            otherSpeedColor
                        },
                    )
                }
            }
        }
    }
}

/**
 * 分辨率钮 (显示当前分辨率名, 点击弹 AlertDialog 单选)。
 */
@Composable
fun ResolutionButton(
    resolutions: List<VideoResolution>,
    currentResolutionIndex: Int,
    onSwitchResolution: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentName =
        resolutions.getOrNull(currentResolutionIndex)?.name ?: stringResource(Res.string.resolution)
    Text(
        text = currentName,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(DesignTokens.shapeSm)
            .clickable { showDialog = true }
            .padding(12.dp),
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(Res.string.resolution)) },
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
                    Text(stringResource(Res.string.cancel))
                }
            },
            modifier = Modifier.appDialogSize(),
            properties = AppDialogSizes.properties(),
            shape = DesignTokens.dialogShape,
            backgroundColor = AppTheme.colors.background,
        )
    }
}

/** 倍速文字格式 (去尾零 + "X", 如 1X / 1.5X / 0.5X)。 */
fun speedLabel(speed: Float): String {
    // Kotlin/Native 无 BigDecimal, 用 %.2f + 去尾零等价 stripTrailingZeros().toPlainString()
    val s = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return s + "X"
}

/** 毫秒 → mm:ss / h:mm:ss 时长格式。 */
fun Long.toDurationTime(): String {
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

// ---- 加载/错误覆盖层 ----
// 注: 由平台渲染槽按自己的状态调用, 本文件不自动叠加 —— 播放器是否就绪只有平台层知道
// (desktop 未装 mpv 时要出安装引导, 盖上通用 loading 就永远转圈)。

@Composable
fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(Res.string.loading),
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
fun ErrorOverlay(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = error, color = Color.White, textAlign = TextAlign.Center)
            Text(
                text = stringResource(Res.string.reload),
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

// ---- 手势反馈标签 / 缓冲圈 / 锁定钮 ----
// 注: 原 app AndroidVideoPlayPlatformProvider 与 desktop MediampVideoPlayPlatformProvider
// 各写一份且细节分化 (标签: app 灰底 arco_fill_3 + primaryText + 24sp vs desktop 半透明黑
// 0x80000000 + 白字 + 20sp; 缓冲圈: app accent vs desktop 白; 锁钮: 两份逐字节相同)。
// 收拢为本文件统一实现, 两端渲染层直接复用, 消除跨端重复与颜色/尺寸分化。

/**
 * 手势/按键反馈标签 (长按倍速 "2.0X"、音量、亮度、进度等), 叠于视频控制层之上。
 * 原 app tv_video_speed: 底色 arco_fill_3 (亮 #FFE6E6E6 / 暗 #FF2A2A2A, 跟随主题) +
 * primaryText + 24sp, 与移动端/master 一致; 调用方负责定位 (一般 TopCenter + 顶部 padding)。
 */
@Composable
fun VideoGestureFeedbackText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = AppTheme.colors.primaryText,
        fontSize = 24.sp,
        modifier = modifier
            .background(rememberColor("arco_fill_3"), DesignTokens.shapeDefault)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 缓冲圈 (叠于控制层之上): 统一收拢, 颜色默认主题强调色 (与移动端一致)。 */
@Composable
fun VideoBufferingIndicator(
    color: Color = AppTheme.colors.accent,
    modifier: Modifier = Modifier,
) {
    CircularProgressIndicator(
        color = color,
        modifier = modifier.size(48.dp),
    )
}

/** 锁定/解锁钮 (原 app/desktop 两份完全相同的 private 实现, 收拢为共享实现)。 */
@Composable
fun VideoLockToggle(
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

