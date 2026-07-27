package io.legado.app.ui.book.video

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getDisplayTitle
import io.legado.app.help.book.getUseReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.toDurationTime
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.media3.ui.R as Media3UiR

/** 视频播放页：标题栏 + 渲染面(AndroidView) + 手势层(pointerInput) + 控制层(Compose) + 选集网格 */
@Composable
fun VideoPlayScreen(activity: VideoPlayActivity) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxSize()) {
        if (!activity.isFullScreen) {
            AppTitleBar(
                title = "",
                onBack = { activity.onBackPressedDispatcher.onBackPressed() },
                titleContent = {
                    // 原 toolbar 整体可点进书籍详情
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clickable { activity.onTitleClick() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = activity.titleText,
                            color = colors.primaryText,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = { VideoTitleActions(activity) },
            )
        }
        val showGrid = !activity.isFullScreen && activity.chapters.size > 1
        Box(
            if (showGrid) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            }
        ) {
            VideoSurface(activity, Modifier.matchParentSize())
            VideoGestureOverlay(activity, Modifier.matchParentSize())
            VideoControlsOverlay(activity, Modifier.matchParentSize())
            // 锁定态：控制层整体隐藏，仅留半透明小锁钮，点击解锁
            if (activity.isLocked) {
                VideoLockToggle(
                    locked = true,
                    onClick = { activity.isLocked = false },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                )
            }
            // 缓冲圈(原 show_buffering=when_playing)
            if (activity.playWhenReady && activity.playbackState == Player.STATE_BUFFERING) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                )
            }
            activity.gestureText?.let {
                Text(
                    text = it,
                    color = colors.primaryText,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(colorResource(R.color.arco_fill_3), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (showGrid) {
            VideoChapterGrid(
                activity,
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/** ① 渲染面：最薄平台件, useController=false, 零逻辑零手势 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun VideoSurface(activity: VideoPlayActivity, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = activity.exoPlayer },
        modifier = modifier,
    )
}

/** ② 手势层：pointerInput 驱动 gestureHandler(逐项对齐原 VideoGestureListener) */
@Composable
private fun VideoGestureOverlay(activity: VideoPlayActivity, modifier: Modifier) {
    val gs = activity.gestureHandler
    val locked = activity.isLocked
    Box(
        modifier
            .pointerInput(gs, locked) {
                // 锁定态：手势层整体旁路(左右滑/双击/上下滑/长按全部失效)
                if (locked) return@pointerInput
                detectTapGestures(
                    onTap = { gs.onSingleTap() },
                    onDoubleTap = { gs.onDoubleTap() },
                    onLongPress = { gs.onLongPress() },
                )
            }
            .pointerInput(gs, locked) {
                if (locked) return@pointerInput
                // 滑动/抬手：越过 slop 才消费(同时打断 tap 层)；长按倍速期间不响应滑动
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    gs.onDown(down.position.x, down.position.y)
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break
                            if (gs.speedBoosted) continue
                            if (!dragging) {
                                val slop = viewConfiguration.touchSlop
                                val delta = change.position - down.position
                                dragging = abs(delta.x) > slop || abs(delta.y) > slop
                            }
                            if (dragging) {
                                change.consume()
                                gs.onScroll(change.position.x, change.position.y)
                            }
                        }
                    } finally {
                        gs.onUp()
                    }
                }
            }
    )
}

// ---- ③ 控制状态层(原自定义 exo controller 的 Compose 等价) ----

@Composable
private fun VideoControlsOverlay(activity: VideoPlayActivity, modifier: Modifier) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val player = activity.exoPlayer
    var positionMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    // 进度回显：控制层可见时 500ms 轮询
    LaunchedEffect(player, activity.controlsVisible) {
        while (activity.controlsVisible) {
            player?.let {
                positionMs = it.currentPosition.coerceAtLeast(0L)
                bufferedMs = it.bufferedPosition.coerceAtLeast(0L)
                durationMs = it.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }
    // 自动隐藏(原 controllerShowTimeoutMs=5s，仅播放/缓冲中计时)
    val playingOrBuffering = activity.isPlaying ||
            (activity.playWhenReady && activity.playbackState == Player.STATE_BUFFERING)
    val seeking = dragMs != null
    LaunchedEffect(activity.controlsVisible, playingOrBuffering, seeking) {
        if (activity.controlsVisible && playingOrBuffering && !seeking) {
            delay(5000)
            activity.controlsVisible = false
        }
    }
    AnimatedVisibility(
        visible = activity.controlsVisible,
        enter = if (eInk) EnterTransition.None else fadeIn(),
        exit = if (eInk) ExitTransition.None else fadeOut(),
        modifier = modifier,
    ) {
        // 整层压暗(原 exo_controls_background: exo_black_opacity_60)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x98000000))
        ) {
            CenterControls(activity, Modifier.align(Alignment.Center))
            // 锁定钮(与控制层其余按钮同白 tint/尺寸)：锁定后隐藏控制层
            VideoLockToggle(
                locked = false,
                onClick = {
                    activity.isLocked = true
                    activity.controlsVisible = false
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                VideoSeekBar(
                    value = dragMs ?: positionMs,
                    buffered = bufferedMs,
                    max = durationMs,
                    activeColor = colors.accent,
                    bufferColor = colors.accent.copy(alpha = 0.5f),
                    onDrag = { dragMs = it },
                    onDragFinished = {
                        dragMs?.let { activity.seekTo(it) }
                        dragMs = null
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
                    Text(
                        text = "%s / %s".format(
                            (dragMs ?: positionMs).toInt().toDurationTime(),
                            durationMs.toInt().toDurationTime(),
                        ),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    SpeedButton(activity)
                    // 分辨率切换: 复用 shared ResolutionButton
                    val resolutions = activity.viewModel.resolutions.value.orEmpty()
                    if (resolutions.size > 1) {
                        ResolutionButton(
                            resolutions = resolutions,
                            currentResolutionIndex = activity.viewModel.currentResolutionIndex,
                            onSwitchResolution = { index -> activity.switchResolution(index) },
                        )
                    }
                    val isLandscape =
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    IconButton(onClick = { activity.toggleOrientationFullscreen() }) {
                        Icon(
                            painter = painterResource(
                                if (isLandscape) Media3UiR.drawable.exo_ic_fullscreen_exit
                                else Media3UiR.drawable.exo_ic_fullscreen_enter
                            ),
                            contentDescription = stringResource(R.string.full_screen),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** 中央控制行(原 exo_center_controls): 上一集 / 后退 / 播放暂停 / 前进 / 下一集 */
@Composable
private fun CenterControls(activity: VideoPlayActivity, modifier: Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ControlIcon(
            painter = painterResource(Media3UiR.drawable.exo_ic_skip_previous),
            descRes = R.string.previous_chapter,
            enabled = activity.durChapterIndex > 0,
            onClick = { activity.playPrevChapter() },
        )
        ControlIcon(
            painter = painterResource(Media3UiR.drawable.exo_ic_rewind),
            descRes = Media3UiR.string.exo_controls_rewind_description,
            onClick = { activity.seekBack() },
        )
        PlayPauseButton(activity)
        ControlIcon(
            painter = painterResource(Media3UiR.drawable.exo_ic_forward),
            descRes = Media3UiR.string.exo_controls_fastforward_description,
            onClick = { activity.seekForward() },
        )
        ControlIcon(
            painter = painterResource(Media3UiR.drawable.exo_ic_skip_next),
            descRes = R.string.next_chapter,
            enabled = activity.durChapterIndex < activity.chapters.lastIndex,
            onClick = { activity.playNextChapter() },
        )
    }
}

/** 锁定/解锁钮：复用锁矢量，白 tint 同其余控制钮；locked 态半透明常驻 */
@Composable
private fun VideoLockToggle(
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

@Composable
private fun ControlIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    descRes: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = stringResource(descRes),
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun PlayPauseButton(activity: VideoPlayActivity) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable { activity.playButton() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(
                if (activity.isPlaying) "ic_pause_24dp" else "ic_play_24dp"
            ),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun SpeedButton(activity: VideoPlayActivity) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            text = speedLabel(activity.playbackSpeed),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(12.dp),
        )
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            speedLabel(speed),
                            color = if (speed == activity.playbackSpeed) AppTheme.colors.accent
                            else AppTheme.colors.primaryText,
                        )
                    },
                    onClick = {
                        expanded = false
                        activity.setPlaySpeed(speed)
                    },
                )
            }
        }
    }
}

private fun speedLabel(speed: Float): String =
    speed.toBigDecimal().stripTrailingZeros().toPlainString() + "X"

/** 自绘 MD2 SeekBar 加缓冲层(同音频页形态)，Long 毫秒域 */
@Composable
private fun VideoSeekBar(
    value: Long,
    buffered: Long,
    max: Long,
    activeColor: Color,
    bufferColor: Color,
    onDrag: (Long) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = max.coerceAtLeast(1L)

    fun fractionToValue(fraction: Float): Long =
        (fraction * range).toLong().coerceIn(0L, range)

    Box(
        modifier
            .pointerInput(max) {
                detectTapGestures(onTap = { pos ->
                    onDrag(fractionToValue(pos.x / size.width))
                    onDragFinished()
                })
            }
            .pointerInput(max) {
                detectHorizontalDragGestures(
                    onDragEnd = { onDragFinished() },
                    onDragCancel = { onDragFinished() },
                ) { change, _ ->
                    change.consume()
                    onDrag(fractionToValue(change.position.x / size.width))
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
            val playFrac = (value.toFloat() / range).coerceIn(0f, 1f)
            val bufFrac = (buffered.toFloat() / range).coerceIn(0f, 1f)
            val playX = startX + (endX - startX) * playFrac
            val bufX = startX + (endX - startX) * bufFrac
            drawLine(Color(0xB3FFFFFF), Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            if (bufX > startX) {
                drawLine(bufferColor, Offset(startX, cy), Offset(bufX, cy), trackH, StrokeCap.Round)
            }
            drawLine(activeColor, Offset(startX, cy), Offset(playX, cy), trackH, StrokeCap.Round)
            drawCircle(activeColor, thumbR, Offset(playX, cy))
        }
    }
}

@Composable
private fun VideoTitleActions(activity: VideoPlayActivity) {
    val colors = AppTheme.colors
    IconButton(onClick = { activity.refreshChapter() }) {
        Icon(
            painter = rememberPainter("ic_refresh_black_24dp"),
            contentDescription = stringResource(R.string.refresh),
            tint = colors.primaryText,
        )
    }
    IconButton(onClick = { activity.toggleShelf() }) {
        Icon(
            painter = rememberPainter(
                if (activity.inShelf) "ic_star" else "ic_star_border"
            ),
            contentDescription = stringResource(
                if (activity.inShelf) R.string.in_favorites else R.string.out_favorites
            ),
            tint = colors.primaryText,
        )
    }
    OverflowMenu { dismiss ->
        VideoMenuItem(R.string.full_screen) { dismiss(); activity.toggleFullScreen() }
        if (activity.viewModel.curBookSource?.hasLogin() == true) {
            VideoMenuItem(R.string.login) { dismiss(); activity.showLogin() }
        }
        VideoMenuItem(R.string.copy_play_url) { dismiss(); activity.copyPlayUrl() }
        VideoMenuItem(R.string.set_source_variable) { dismiss(); activity.showSourceVariable() }
        VideoMenuItem(R.string.set_book_variable) { dismiss(); activity.showBookVariable() }
        VideoMenuItem(R.string.edit_book_source) { dismiss(); activity.editSource() }
        if (activity.viewModel.curBookSource?.reviewRule?.reviewUrl.isNullOrBlank() == false) {
            VideoMenuItem(R.string.review) { dismiss(); activity.openReview() }
        }
        VideoMenuItem(R.string.bookmark_add) { dismiss(); activity.addBookmark() }
        VideoMenuItem(R.string.log) { dismiss(); activity.showAppLog() }
    }
}

@Composable
private fun VideoMenuItem(textRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(textRes), color = AppTheme.colors.primaryText) },
        onClick = onClick,
    )
}

// ---- 选集网格(原 ChapterListAdapter + GridLayoutManager(3)) ----

@Composable
private fun VideoChapterGrid(activity: VideoPlayActivity, modifier: Modifier) {
    val chapters = activity.chapters
    val gridState = rememberLazyGridState()
    var displayTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(chapters) {
        gridState.scrollToItem(activity.durChapterIndex.coerceIn(0, chapters.lastIndex))
        // 标题净化规则后台计算(原 upDisplayTitles)
        val book = activity.viewModel.curBook ?: return@LaunchedEffect
        withContext(IO) {
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
            displayTitles = chapters.associate {
                it.title to it.getDisplayTitle(replaceRules, useReplace)
            }
        }
    }
    LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState, modifier = modifier) {
        items(chapters, key = { it.url }) { chapter ->
            VideoChapterItem(
                chapter = chapter,
                title = displayTitles[chapter.title] ?: chapter.title,
                isDur = chapter.index == activity.durChapterIndex,
            ) { activity.openChapter(chapter) }
        }
    }
}

/** 对照 item_chapter_list: 卷名 btn_bg 底、当前集 accent+勾选、未缓存云图标、vip 锁 */
@Composable
private fun VideoChapterItem(
    chapter: BookChapter,
    title: String,
    isDur: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val volumeBg = if (chapter.isVolume) {
        Modifier.background(colorResource(R.color.btn_bg))
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(volumeBg)
            .combinedClickable(
                onClick = { if (!chapter.isVolume) onClick() },
                onLongClick = { context.longToastOnUi(title) },
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chapter.isVip && !chapter.isPay) {
            Icon(
                painter = rememberPainter("ic_lock_outline"),
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 8.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isDur) colors.accent else colors.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                if (AppConfig.tocCountWords && !chapter.wordCount.isNullOrEmpty() && !chapter.isVolume) {
                    Text(
                        text = chapter.wordCount!!,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
                if (!chapter.tag.isNullOrEmpty() && !chapter.isVolume) {
                    Text(
                        text = chapter.tag!!,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val rightIcon = when {
            chapter.isVolume -> "ic_expand_less"
            isDur -> "ic_check"
            else -> "ic_outline_cloud_24"
        }
        Icon(
            painter = rememberPainter(rightIcon),
            contentDescription = stringResource(R.string.success),
            tint = colors.secondaryText,
            modifier = Modifier
                .size(24.dp)
                .padding(4.dp),
        )
    }
}
