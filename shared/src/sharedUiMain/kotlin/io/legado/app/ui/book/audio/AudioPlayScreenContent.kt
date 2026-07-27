package io.legado.app.ui.book.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.toDurationTime

/**
 * 音频播放页主体内容 (模糊封面背景 + 遮罩 + 标题栏 + 副标题 + 封面/歌词区 + 进度条 + 播放控制排)。
 *
 * 结构对照 app 端 [io.legado.app.ui.book.audio.AudioPlayScreen], 通过 slot 注入平台特殊部分:
 * - [coverSlot]: 封面图加载 (app: Glide+AndroidView; desktop: OkHttp+ImageIO)
 * - [blurBgSlot]: 模糊封面背景加载槽 (app: blurConfig+TransitionDrawable 淡入+onBlurCoverLoaded 回调; desktop: null 复用 coverSlot)
 * - [lrcSlot]: 歌词渲染 (app: 自绘 LrcView; desktop: LazyColumn 简化版)
 * - [titleBarTrailingSlot]: 标题栏尾部 (app: review+overflow; desktop: 无)
 * - [timerDialogSlot]/[speedDialogSlot]: 定时/倍速弹窗 (app: Popup; desktop: AlertDialog)
 *
 * 默认参数对齐 desktop 视觉; app 端采用时通过参数覆盖差异 (titleBarHorizontalPadding/playMenuAlpha 等)。
 *
 * @param title 标题 (书名)
 * @param subTitle 副标题 (章节名)
 * @param coverUrl 封面 URL (null/空 → 不加载)
 * @param coverVisible 封面圆形图显隐 (点击封面切到 false, 由调用方管理状态)
 * @param timerMinute 定时分钟数 (>0 时左上角回显标签)
 * @param speed 倍速 (!=1f 时右上角回显标签)
 * @param progressMs 当前播放位置 ms
 * @param durationMs 总时长 ms
 * @param bufferMs 缓冲进度 ms
 * @param isPlaying 播放中 (回显播放/暂停钮)
 * @param loading 加载中 (回显 CircularProgressIndicator)
 * @param playMode 播放模式 (回显播放模式钮图标)
 * @param prevEnabled 上一章钮可用
 * @param nextEnabled 下一章钮可用
 * @param accentColor 强调色 (圆形封面描边 + SeekBar 已播层 + 加载指示器)
 * @param lrcActiveColor SeekBar 已播层颜色 (null=用 accentColor; app 端从 lrcColors 取)
 * @param lrcInactiveColor SeekBar 缓冲层颜色 (null=用 accentColor.copy(0.5); app 端从 lrcColors 取)
 * @param onBack 返回
 * @param onOpenChangeSource 换源
 * @param onCoverClick 封面点击 (隐藏封面)
 * @param onTogglePlay 播放/暂停切换
 * @param onPrev 上一章
 * @param onNext 下一章
 * @param onChangePlayMode 切换播放模式
 * @param onOpenToc 打开目录
 * @param onSeek 进度跳转 (ms)
 * @param onSetTimer 设定定时 (分钟)
 * @param onSetSpeed 设定倍速
 * @param onStop 停止回调 (null=不显示停止钮; desktop 端独有)
 * @param coverSlot 封面加载槽 (url, modifier) → 平台图片加载 Composable
 * @param blurBgSlot 模糊封面背景加载槽 (null=复用 coverSlot; app 端走 blurConfig + 淡入 + 回调)
 * @param lrcSlot 歌词渲染槽 (modifier) → 平台歌词 Composable
 * @param titleBarTrailingSlot 标题栏尾部槽 (app: review+overflow; desktop: 空)
 * @param timerDialogSlot 定时弹窗槽 (initial, onProgressChanged, onDismiss)
 * @param speedDialogSlot 倍速弹窗槽 (initial, onProgressChanged, onDismiss)
 * @param timerIconKey 定时钮图标 key (desktop: ic_time_add_24dp; app: ic_timer_black_24dp)
 * @param speedIconKey 倍速钮图标 key (desktop: ic_speed; app: ic_fast_forward)
 * @param chapterListIconKey 目录钮图标 key (desktop: ic_toc; app: ic_chapter_list)
 * @param filletLabelColor 回显标签底色 (desktop: 0x66000000; app: arco_fill_3)
 * @param playMenuButtonPressedBgEnabled 控制钮是否启用按压态背景 (app: true; desktop: false)
 * @param playMenuAlpha 控制排整体透明度 (app: 0.7f; desktop: 1f)
 * @param titleBarHorizontalPadding 标题栏横向内边距 (app: 0dp; desktop: 8dp)
 * @param playModeIconPadding 播放模式钮图标内边距 (app: 8dp; desktop: 4dp)
 */
@Composable
fun AudioPlayScreenContent(
    title: String,
    subTitle: String,
    coverUrl: String?,
    coverVisible: Boolean,
    timerMinute: Int,
    speed: Float,
    progressMs: Int,
    durationMs: Int,
    bufferMs: Int,
    isPlaying: Boolean,
    loading: Boolean,
    playMode: AudioPlayShared.PlayMode,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    accentColor: Color,
    lrcActiveColor: Color? = null,
    lrcInactiveColor: Color? = null,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onCoverClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangePlayMode: () -> Unit,
    onOpenToc: () -> Unit,
    onSeek: (Int) -> Unit,
    onSetTimer: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onStop: (() -> Unit)? = null,
    coverSlot: @Composable (String?, Modifier) -> Unit,
    blurBgSlot: (@Composable (String?, Modifier) -> Unit)? = null,
    lrcSlot: @Composable (Modifier) -> Unit,
    titleBarTrailingSlot: @Composable RowScope.() -> Unit = {},
    timerDialogSlot: @Composable (initial: Int, onProgressChanged: (Int) -> Unit, onDismiss: () -> Unit) -> Unit,
    speedDialogSlot: @Composable (initial: Float, onProgressChanged: (Float) -> Unit, onDismiss: () -> Unit) -> Unit,
    timerIconKey: String = "ic_time_add_24dp",
    speedIconKey: String = "ic_speed",
    chapterListIconKey: String = "ic_toc",
    filletLabelColor: Color = Color(0x66000000),
    playMenuButtonPressedBgEnabled: Boolean = false,
    playMenuAlpha: Float = 1f,
    titleBarHorizontalPadding: Dp = 8.dp,
    playModeIconPadding: Dp = 4.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // 键盘快捷键: Space=播放/暂停, ←=进度后退10s, →=进度前进10s, Esc/Backspace=返回
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar -> { onTogglePlay(); true }
                    Key.DirectionLeft -> {
                        onSeek((progressMs - 10000).coerceAtLeast(0)); true
                    }
                    Key.DirectionRight -> {
                        onSeek((progressMs + 10000).coerceAtMost(durationMs)); true
                    }
                    Key.Escape, Key.Backspace -> { onBack(); true }
                    else -> false
                }
            },
    ) {
        // 模糊封面背景 (平台 blurBgSlot 加载; null 时复用 coverSlot)
        val bgSlot = blurBgSlot ?: coverSlot
        bgSlot(coverUrl, Modifier.matchParentSize())
        // 半透明遮罩 (对照 app 端 0x3A000000)
        Box(Modifier.matchParentSize().background(Color(0x3A000000)))
        Column(Modifier.fillMaxSize()) {
            AudioTitleBar(
                title = title,
                onBack = onBack,
                onOpenChangeSource = onOpenChangeSource,
                trailingSlot = titleBarTrailingSlot,
                horizontalPadding = titleBarHorizontalPadding,
            )
            Text(
                text = subTitle,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (coverVisible) {
                        CoverImage(
                            coverUrl = coverUrl,
                            accentColor = accentColor,
                            onClick = onCoverClick,
                            coverSlot = coverSlot,
                        )
                    }
                    lrcSlot(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 16.dp),
                    )
                }
                // 定时回显标签 (左上)
                if (timerMinute > 0) {
                    FilletLabel(
                        text = "${timerMinute}m",
                        iconKey = timerIconKey,
                        bgColor = filletLabelColor,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                    )
                }
                // 倍速回显标签 (右上)
                if (speed != 1f) {
                    FilletLabel(
                        text = "%.1fX".format(speed),
                        iconKey = null,
                        bgColor = filletLabelColor,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
            }
            ProgressRow(
                progressMs = progressMs,
                bufferMs = bufferMs,
                durationMs = durationMs,
                accentColor = accentColor,
                lrcActiveColor = lrcActiveColor,
                lrcInactiveColor = lrcInactiveColor,
                onSeek = onSeek,
            )
            PlayMenu(
                isPlaying = isPlaying,
                loading = loading,
                playMode = playMode,
                timerMinute = timerMinute,
                speed = speed,
                prevEnabled = prevEnabled,
                nextEnabled = nextEnabled,
                onStop = onStop,
                accentColor = accentColor,
                timerIconKey = timerIconKey,
                speedIconKey = speedIconKey,
                chapterListIconKey = chapterListIconKey,
                playMenuButtonPressedBgEnabled = playMenuButtonPressedBgEnabled,
                playMenuAlpha = playMenuAlpha,
                playModeIconPadding = playModeIconPadding,
                onTogglePlay = onTogglePlay,
                onPrev = onPrev,
                onNext = onNext,
                onChangePlayMode = onChangePlayMode,
                onOpenToc = onOpenToc,
                onSetTimer = onSetTimer,
                onSetSpeed = onSetSpeed,
                timerDialogSlot = timerDialogSlot,
                speedDialogSlot = speedDialogSlot,
            )
        }
    }
}

// ---- 标题栏 (返回 + 标题 + 换源 + 尾部槽) ----

@Composable
private fun AudioTitleBar(
    title: String,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    trailingSlot: @Composable RowScope.() -> Unit,
    horizontalPadding: Dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = rememberPainter("ic_arrow_back"),
                contentDescription = rememberString("back"),
                tint = Color.White,
            )
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenChangeSource) {
            Icon(
                painter = rememberPainter("ic_exchange"),
                contentDescription = rememberString("change_origin"),
                tint = Color.White,
            )
        }
        trailingSlot()
    }
}

// ---- 圆形封面 (200dp 圆形 + accent 描边, 平台 coverSlot 加载) ----

@Composable
private fun CoverImage(
    coverUrl: String?,
    accentColor: Color,
    onClick: () -> Unit,
    coverSlot: @Composable (String?, Modifier) -> Unit,
) {
    coverSlot(
        coverUrl,
        Modifier
            .padding(top = 16.dp)
            .size(200.dp)
            .clip(CircleShape)
            .border(2.dp, accentColor, CircleShape)
            .clickable(onClick = onClick),
    )
}

// ---- 定时/倍速回显标签 (圆角填充底 + 可选图标 + 白字) ----

@Composable
private fun FilletLabel(
    text: String,
    iconKey: String?,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconKey != null) {
            Icon(
                painter = rememberPainter(iconKey),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

// ---- 进度条 (时间 + 自绘 SeekBar + 时间) ----

@Composable
private fun ProgressRow(
    progressMs: Int,
    bufferMs: Int,
    durationMs: Int,
    accentColor: Color,
    lrcActiveColor: Color?,
    lrcInactiveColor: Color?,
    onSeek: (Int) -> Unit,
) {
    // 拖动中的预览值 (拖动期间不回显事件进度)
    var dragValue by remember { mutableStateOf<Int?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (dragValue ?: progressMs).toDurationTime(),
            color = Color.White,
            fontSize = 14.sp,
        )
        AudioSeekBar(
            value = dragValue ?: progressMs,
            secondary = bufferMs,
            max = durationMs,
            activeColor = lrcActiveColor ?: accentColor,
            bufferColor = lrcInactiveColor ?: accentColor.copy(alpha = 0.5f),
            onDrag = { dragValue = it },
            onDragFinished = {
                dragValue?.let { onSeek(it) }
                dragValue = null
            },
            modifier = Modifier
                .weight(1f)
                .height(25.dp),
        )
        Text(durationMs.toDurationTime(), color = Color.White, fontSize = 14.sp)
    }
}

/** 自绘 SeekBar: 背景 + 缓冲层 + 已播层 + thumb, 支持点击/拖动 seek */
@Composable
private fun AudioSeekBar(
    value: Int,
    secondary: Int,
    max: Int,
    activeColor: Color,
    bufferColor: Color,
    onDrag: (Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = max.coerceAtLeast(1)

    fun fractionToValue(fraction: Float): Int =
        (fraction * range).toInt().coerceIn(0, range)

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
            val bufFrac = (secondary.toFloat() / range).coerceIn(0f, 1f)
            val playX = startX + (endX - startX) * playFrac
            val bufX = startX + (endX - startX) * bufFrac
            // 进度背景
            drawLine(Color(0xB3FFFFFF), Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            if (bufX > startX) {
                drawLine(bufferColor, Offset(startX, cy), Offset(bufX, cy), trackH, StrokeCap.Round)
            }
            drawLine(activeColor, Offset(startX, cy), Offset(playX, cy), trackH, StrokeCap.Round)
            drawCircle(activeColor, thumbR, Offset(playX, cy))
        }
    }
}

// ---- 播放控制排 (定时 + 倍速 + 上一章 + 播放/暂停 + 下一章 + [停止] + 播放模式 + 目录) ----

@Composable
private fun PlayMenu(
    isPlaying: Boolean,
    loading: Boolean,
    playMode: AudioPlayShared.PlayMode,
    timerMinute: Int,
    speed: Float,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onStop: (() -> Unit)?,
    accentColor: Color,
    timerIconKey: String,
    speedIconKey: String,
    chapterListIconKey: String,
    playMenuButtonPressedBgEnabled: Boolean,
    playMenuAlpha: Float,
    playModeIconPadding: Dp,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangePlayMode: () -> Unit,
    onOpenToc: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    timerDialogSlot: @Composable (initial: Int, onProgressChanged: (Int) -> Unit, onDismiss: () -> Unit) -> Unit,
    speedDialogSlot: @Composable (initial: Float, onProgressChanged: (Float) -> Unit, onDismiss: () -> Unit) -> Unit,
) {
    var showTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .alpha(playMenuAlpha)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 定时
        Box {
            PlayMenuButton(
                iconKey = timerIconKey,
                contentDescription = rememberString("set_timer"),
                pressedBgEnabled = playMenuButtonPressedBgEnabled,
            ) { showTimer = true }
            if (showTimer) {
                timerDialogSlot(timerMinute, onSetTimer) { showTimer = false }
            }
        }
        Spacer(Modifier.weight(1f))
        // 倍速
        Box {
            PlayMenuButton(
                iconKey = speedIconKey,
                contentDescription = rememberString("speed"),
                pressedBgEnabled = playMenuButtonPressedBgEnabled,
            ) { showSpeed = true }
            if (showSpeed) {
                speedDialogSlot(speed, onSetSpeed) { showSpeed = false }
            }
        }
        Spacer(Modifier.weight(1f))
        // 上一章
        PlayMenuButton(
            iconKey = "ic_skip_previous",
            contentDescription = rememberString("previous_chapter"),
            enabled = prevEnabled,
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onPrev() }
        // 播放/暂停 (圆形白底)
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .padding(12.dp)
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = rememberPainter(if (isPlaying) "ic_pause_24dp" else "ic_play_24dp"),
                    contentDescription = rememberString("audio_play"),
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (loading) {
                CircularProgressIndicator(
                    color = accentColor,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        // 下一章
        PlayMenuButton(
            iconKey = "ic_skip_next",
            contentDescription = rememberString("next_chapter"),
            enabled = nextEnabled,
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onNext() }
        Spacer(Modifier.weight(1f))
        // 停止 (desktop 独有, app 端 onStop=null 不渲染)
        if (onStop != null) {
            PlayMenuButton(
                iconKey = "ic_stop_black_24dp",
                contentDescription = rememberString("stop"),
                pressedBgEnabled = playMenuButtonPressedBgEnabled,
            ) { onStop() }
            Spacer(Modifier.weight(1f))
        }
        // 播放模式
        PlayMenuButton(
            iconKey = playModeIconKey(playMode),
            contentDescription = rememberString("play_mode"),
            iconPadding = playModeIconPadding,
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onChangePlayMode() }
        Spacer(Modifier.weight(1f))
        // 目录
        PlayMenuButton(
            iconKey = chapterListIconKey,
            contentDescription = rememberString("chapter_list"),
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onOpenToc() }
    }
}

/** 46dp 圆钮: 按压态圆形底 (可选) + 白图标/禁用 25% 白 */
@Composable
private fun PlayMenuButton(
    iconKey: String,
    contentDescription: String,
    enabled: Boolean = true,
    iconPadding: Dp = 4.dp,
    pressedBgEnabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg = rememberColor("arco_fill_3")
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .then(
                if (pressedBgEnabled && pressed) Modifier.background(pressedBg)
                else Modifier,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color(0x3FFFFFFF),
            modifier = Modifier
                .fillMaxSize()
                .padding(iconPadding),
        )
    }
}

/** 播放模式 → 图标 key (各端 rememberPainter 解析: app→R.drawable.ic_play_mode_*; desktop→SVG) */
private fun playModeIconKey(mode: AudioPlayShared.PlayMode): String = when (mode) {
    AudioPlayShared.PlayMode.LIST_END_STOP -> "ic_play_mode_list_end_stop"
    AudioPlayShared.PlayMode.SINGLE_LOOP -> "ic_play_mode_single_loop"
    AudioPlayShared.PlayMode.RANDOM -> "ic_play_mode_random"
    AudioPlayShared.PlayMode.LIST_LOOP -> "ic_play_mode_list_loop"
}
