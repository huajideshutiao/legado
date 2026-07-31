package io.legado.app.ui.book.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.foundation.focusable
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
import io.legado.app.data.entities.VideoResolution
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.handleMediaKeys
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlin.math.abs
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.back
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.change_source
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_exchange
import legado.shared.generated.resources.ic_skip_next
import legado.shared.generated.resources.ic_skip_previous
import legado.shared.generated.resources.ic_toc
import legado.shared.generated.resources.loading
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.pause
import legado.shared.generated.resources.play
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.reload
import legado.shared.generated.resources.resolution
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 视频播放页主体内容 (标题栏 + 渲染槽 + 章节控制栏)。
 *
 * 平台渲染层通过 [videoRenderSlot] 注入: desktop 传 SwingPanel(AWT Canvas, mpv --wid 嵌入,
 * 控制层用 mpv 内建 OSC); app 传 AndroidView(PlayerView) + 自有控件层。槽内自管
 * 渲染面 + 控件叠加 + 加载/错误态, 复用本文件导出的 Composable。
 *
 * 键盘事件: 最外层 Box 消费共享 handleMediaKeys (空格/←/→/↑/↓/Esc), 回调由调用方注入
 * (与 [VideoControlsOverlay] 按钮共用同一 lambda)。新参数均带默认值, 保持 desktop 现有调用不报错。
 *
 * @param bookName 书名
 * @param chapterTitle 当前章节标题
 * @param curChapterIndex 当前章节索引 (0-based)
 * @param chapterSize 章节总数
 * @param onBack 返回回调
 * @param onOpenToc 打开目录回调
 * @param onOpenChangeSource 打开换源回调
 * @param onPrevChapter 上一章回调
 * @param onNextChapter 下一章回调
 * @param videoRenderSlot 平台渲染层槽 (接收 Modifier, 内部叠加控件/加载/错误)
 * @param onPlayPause 播放/暂停回调 (Spacebar 触发)
 * @param onSeekDelta 相对 seek 偏移 (←/→ 触发, 毫秒)
 * @param onSpeedChange 倍速切换 (长按 → 触发 2x, 松开恢复 1x)
 * @param controlsVisible 控制层可见状态 (键盘事件感知)
 * @param onToggleControls 显隐控制层 (Escape 触发)
 * @param onTitleClick 标题区点击回调 (对照 Activity onTitleClick, 默认空保持向后兼容)
 * @param titleActions 标题栏右侧额外 actions (由 Route 注入 refresh/shelf/overflowMenu, 默认空)
 */
@Composable
fun VideoPlayerScreenContent(
    bookName: String,
    chapterTitle: String,
    curChapterIndex: Int,
    chapterSize: Int,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    videoRenderSlot: @Composable (Modifier) -> Unit,
    // 键盘事件回调 (默认空 lambda 保持向后兼容)
    onPlayPause: () -> Unit = {},
    onSeekDelta: (Long) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    controlsVisible: Boolean = false,
    onToggleControls: () -> Unit = {},
    // 平台自定义顶栏 (null = 用 shared VideoTitleBar; 传 {} 隐藏)
    topBarSlot: (@Composable () -> Unit)? = null,
    // 标题区点击 + 标题栏右侧额外 actions (默认空保持向后兼容, 仅 topBarSlot=null 时生效)
    onTitleClick: () -> Unit = {},
    titleActions: @Composable RowScope.() -> Unit = {},
    // 平台自定义底栏 (默认 shared VideoControlBar; 传 {} 隐藏; ColumnScope 允许 weight)
    bottomBarSlot: @Composable ColumnScope.() -> Unit = {
        VideoControlBar(
            curIndex = curChapterIndex,
            size = chapterSize,
            onPrev = onPrevChapter,
            onNext = onNextChapter,
        )
    },
    // 视频区填充模式 (true = weight(1f) 撑满; false = aspectRatio(16:9) 固定比例)
    videoAreaFill: Boolean = true,
    // 容器背景 (desktop 默认黑底; app 传 Color.Unspecified 让 activity 主题背景透出)
    containerColor: Color = Color(0xFF000000),
) {
    val scope = rememberCoroutineScope()
    // 键盘事件焦点: onPreviewKeyEvent 需焦点路径上有节点持焦才触发, 进入即取焦点
    // (desktop 端渲染槽另有 focusRequester, 后取焦者生效, 根节点 handler 均在焦点路径上)
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyFocusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(containerColor)
            // 键盘快捷键: 消费共享 handleMediaKeys
            // (Space=播放/暂停, ←/→=seek ∓10s, 长按 →=2x 倍速, ↑/↓=上/下一章;
            //  Esc/Backspace 控制层可见先收控制层, 否则返回)
            .handleMediaKeys(
                onTogglePlayPause = onPlayPause,
                onSeekDelta = onSeekDelta,
                onPrev = onPrevChapter,
                onNext = onNextChapter,
                onSpeedChange = onSpeedChange,
                onBack = { if (controlsVisible) onToggleControls() else onBack() },
                scope = scope,
            )
            .focusRequester(keyFocusRequester)
            .focusable()
    ) {
        Column(Modifier.fillMaxSize()) {
            if (topBarSlot != null) {
                topBarSlot()
            } else {
                VideoTitleBar(
                    bookName = bookName,
                    chapterTitle = chapterTitle,
                    onBack = onBack,
                    onOpenToc = onOpenToc,
                    onOpenChangeSource = onOpenChangeSource,
                    onTitleClick = onTitleClick,
                    actions = titleActions,
                )
            }
            Box(
                if (videoAreaFill) Modifier.fillMaxSize().weight(1f)
                else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            ) {
                videoRenderSlot(Modifier.matchParentSize())
            }
            bottomBarSlot()
        }
    }
}

// ---- 顶部标题栏 ----

/** 视频标题栏 (56dp, 返回 + 书名/章节名 + 目录 + 换源, 黑底白字)。
 *
 *  对照 app 端 [io.legado.app.ui.book.video.VideoPlayActivity.onTitleClick]:
 *  标题区整体可点进书籍详情, 由 [onTitleClick] 桥接 navigator.push(BookInfo)。
 *
 *  @param onTitleClick 标题区点击回调 (默认空, 向后兼容 desktop)
 *  @param actions 右侧额外 action 区 (默认空, 由 Route 注入 refresh/shelf/overflowMenu)
 */
@Composable
fun VideoTitleBar(
    bookName: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onTitleClick: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_back),
                contentDescription = stringResource(Res.string.back),
                tint = Color.White,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .clickable { onTitleClick() }
        ) {
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
        actions()
        IconButton(onClick = onOpenToc) {
            Icon(
                painter = painterResource(Res.drawable.ic_toc),
                contentDescription = stringResource(Res.string.chapter_list),
                tint = Color.White,
            )
        }
        IconButton(onClick = onOpenChangeSource) {
            Icon(
                painter = painterResource(Res.drawable.ic_exchange),
                contentDescription = stringResource(Res.string.change_source),
                tint = Color.White,
            )
        }
    }
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
    accentColor: Color = MaterialTheme.colors.primary,
    secondaryTextColor: Color = MaterialTheme.colors.onSurface,
    speeds: List<Float> = listOf(0.5f, 1f, 1.5f, 2f),
    controller: VideoControlsController? = null,
    bufferedMs: Long = 0L,
    onSeekDragStateChange: (Boolean) -> Unit = {},
    centerControls: @Composable (BoxScope.() -> Unit)? = null,
    leadingContent: @Composable (BoxScope.() -> Unit) = {},
    trailingBottomContent: @Composable (RowScope.() -> Unit) = {},
    enterTransition: EnterTransition = fadeIn(),
    exitTransition: ExitTransition = fadeOut(),
    modifier: Modifier = Modifier,
) {
    val effectivePositionMs = controller?.positionMs ?: positionMs
    val effectiveDurationMs = controller?.durationMs ?: durationMs
    val effectiveBufferedMs = controller?.bufferedMs ?: bufferedMs
    val effectiveBufferColor =
        if (controller != null) MaterialTheme.colors.primary.copy(alpha = 0.5f)
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
                    if (hasMultiResolution) {
                        ResolutionButton(
                            resolutions = resolutions,
                            currentResolutionIndex = currentResolutionIndex,
                            onSwitchResolution = onSwitchResolution,
                        )
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
    rewindPainter: Painter,
    forwardPainter: Painter,
    rewindDesc: String,
    forwardDesc: String,
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
 * 中央播放/暂停钮 (64dp 圆形 + 图标)。
 *
 * @param iconTint 图标 tint (默认黑色, app 端可传白色)
 * @param iconSize 图标尺寸 (默认 36dp, app 端可传 48dp)
 * @param backgroundColor 背景色 (默认白底, app 端可传 Transparent 透明)
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Black,
    iconSize: Dp = 36.dp,
    backgroundColor: Color = Color.White,
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
 * @param speeds 倍速档位 (默认 4 档, app 端可传 7 档)
 * @param currentSpeedColor 当前选中档位文字色
 * @param otherSpeedColor 未选中档位文字色
 */
@Composable
fun SpeedButton(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    speeds: List<Float> = listOf(0.5f, 1f, 1.5f, 2f),
    currentSpeedColor: Color = MaterialTheme.colors.primary,
    otherSpeedColor: Color = MaterialTheme.colors.onSurface,
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
        )
    }
}

/** 倍速文字格式 (去尾零 + "X", 如 1X / 1.5X / 0.5X)。 */
fun speedLabel(speed: Float): String =
    speed.toBigDecimal().stripTrailingZeros().toPlainString() + "X"

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

// ---- 底部章节控制栏 ----

/**
 * 底部章节控制栏: 上一章 / 进度 / 下一章 (黑底白字, 与标题栏风格一致)。
 */
@Composable
fun VideoControlBar(
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
        IconButton(onClick = onPrev, enabled = curIndex > 0) {
            Icon(
                painter = painterResource(Res.drawable.ic_skip_previous),
                contentDescription = stringResource(Res.string.previous_chapter),
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
        IconButton(onClick = onNext, enabled = curIndex < size - 1) {
            Icon(
                painter = painterResource(Res.drawable.ic_skip_next),
                contentDescription = stringResource(Res.string.next_chapter),
                tint = if (curIndex < size - 1) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}
