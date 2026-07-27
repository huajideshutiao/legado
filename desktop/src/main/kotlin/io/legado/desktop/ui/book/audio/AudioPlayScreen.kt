package io.legado.desktop.ui.book.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 桌面端音频播放 Screen (对照 app 端 [io.legado.app.ui.book.audio.AudioPlayScreen])。
 *
 * # 职责
 *
 * 包装 shared/commonMain 的 [AudioPlayShared] 跨组件状态, 通过 [FlowBus] 订阅
 * [EventBus].AUDIO_* 事件刷新 UI, 命令面经 [AudioPlayShared] 派发到
 * [io.legado.desktop.audio.DesktopAudioPlayProvider] (已注册为 AudioPlayCommander)。
 *
 * # 与 app 端 AudioPlayScreen 对照
 *
 * - **结构**: 模糊封面背景 + 遮罩 → 标题栏 → 副标题(章节名) → 封面/歌词区 → 进度条 → 播放控制排
 *   (布局结构与宽高边距对齐 app 端, 不擅自修改 UI 样式)
 * - **状态订阅**: app 端用 observeEventSticky(LiveDataBus), 桌面端用 [FlowBus.withSticky]
 *   + [produceState] 收集为 Compose State (语义等价, FlowBus 是 shared commonMain 下沉实现)
 * - **封面**: app 端用 Glide + AndroidView(ImageView), 桌面端用 [produceState] +
 *   OkHttp + ImageIO 加载为 [ImageBitmap] (参照 DesktopBookCover 加载策略, 不引入 Glide)
 * - **歌词**: app 端用自绘 LrcView(AndroidView 桥接), 桌面端用 LazyColumn 简化文本列表 +
 *   当前行高亮 (DesktopAudioPlayProvider.loadLrcData 用 AnalyzeRuleCore + LrcParser 加载,
 *   经 EventBus.AUDIO_LRC / AUDIO_LRCPROGRESS 推送, 此组件订阅展示)
 * - **进度条**: 自绘 Canvas + pointerInput(点击/拖动 seek), 与 app 端 AudioSeekBar 一致
 * - **速度/定时**: app 端用 Popup + Slider, 桌面端用 AlertDialog + Slider (桌面端常见交互)
 * - **PlayMode 图标**: app 端用 R.drawable.ic_play_mode_*, 桌面端 shared SVG 资源缺失,
 *   改用 Material Icons (Repeat/RepeatOne/Shuffle/LastPage) 替代, 视觉语义对齐
 *
 * # 生命周期
 *
 * - 进入: [LaunchedEffect](book.bookUrl) 调 [AudioPlayShared.resetData] 加载书 + 章节列表,
 *   status==STOP 时调 [AudioPlayShared.loadOrUpPlayUrl] 触发播放
 * - 退出: [DisposableEffect].onDispose 在非 PLAY 状态调 [AudioPlayShared.stop] 释放播放器
 *   (对照 app 端 AudioPlayActivity.onDestroy)
 *
 * @param book 待播放的音频书
 * @param onBack 返回回调 (切回调用方路由)
 * @param onOpenToc 打开目录回调 (切到 TOC 路由, 携带 Book)
 * @param onOpenChangeSource 打开换源回调 (切到 CHANGE_SOURCE 路由, 携带 Book)
 */
@Composable
fun AudioPlayScreen(
    book: Book,
    onBack: () -> Unit,
    onOpenToc: (Book) -> Unit = {},
    onOpenChangeSource: (Book) -> Unit = {},
) {
    // ---- 初始化 AudioPlayShared (对照 app 端 AudioPlayViewModel.initData) ----
    // resetData: stop + 设置 book + 加载章节列表 + upDurChapter (suspend)
    // loadOrUpPlayUrl: 触发 DesktopAudioPlayProvider 加载播放 URL 并播放
    LaunchedEffect(book.bookUrl) {
        AudioPlayShared.inBookshelf = book.type and BookType.notShelf == 0
        AudioPlayShared.resetData(book)
        if (AudioPlayShared.status == Status.STOP) {
            AudioPlayShared.loadOrUpPlayUrl()
        }
    }
    // 退出时若非 PLAY 状态则停止播放, 释放 jlayer 播放器资源
    // (对照 app 端 onDestroy: if (AudioPlay.status != Status.PLAY) viewModel.stop())
    DisposableEffect(book.bookUrl) {
        onDispose {
            if (AudioPlayShared.status != Status.PLAY) {
                AudioPlayShared.stop()
            }
        }
    }

    // ---- 订阅 EventBus sticky 事件为 Compose State (对照 app 端 observeLiveBus) ----
    val status by stickyEventState(EventBus.AUDIO_STATE, Status.STOP)
    val subTitle by stickyEventState(EventBus.AUDIO_SUB_TITLE, "")
    val durationMs by stickyEventState(EventBus.AUDIO_SIZE, 0)
    val progressMs by stickyEventState(EventBus.AUDIO_PROGRESS, 0)
    val bufferMs by stickyEventState(EventBus.AUDIO_BUFFER_PROGRESS, 0)
    val speed by stickyEventState(EventBus.AUDIO_SPEED, 1f)
    val timerMinute by stickyEventState(EventBus.AUDIO_DS, 0)
    val loading by stickyEventState(EventBus.AUDIO_LOADING, false)
    val playMode by stickyEventState(EventBus.PLAY_MODE_CHANGED, AudioPlayShared.PlayMode.LIST_END_STOP)
    val lrcData by stickyEventState(EventBus.AUDIO_LRC, emptyList<Pair<Int, String>>())
    val lrcProgress by stickyEventState(EventBus.AUDIO_LRCPROGRESS, -1)

    val isPlaying = status == Status.PLAY
    // 封面 URL: 优先 AUDIO_COVER 事件, 事件为空时回退 book.getDisplayCover()
    // (DesktopAudioPlayProvider 暂未 post AUDIO_COVER, 实际走 getDisplayCover 兜底)
    val coverUrl by stickyEventState(EventBus.AUDIO_COVER, "")
    val resolvedCoverUrl = coverUrl.ifBlank { book.getDisplayCover() }
    var coverVisible by remember { mutableStateOf(true) }

    // ---- UI (布局结构对齐 app 端 AudioPlayScreen) ----
    Box(Modifier.fillMaxSize()) {
        // 模糊封面背景 + 半透明遮罩 (对照 app 端 BlurBg + 0x3A000000 遮罩)
        BlurBg(resolvedCoverUrl, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Color(0x3A000000)))
        Column(Modifier.fillMaxSize()) {
            AudioTitleBar(
                title = book.name,
                onBack = onBack,
                onOpenChangeSource = { onOpenChangeSource(book) },
            )
            Text(
                text = subTitle,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (coverVisible && !resolvedCoverUrl.isNullOrEmpty()) {
                        CoverImage(
                            coverUrl = resolvedCoverUrl!!,
                            modifier = Modifier.padding(top = 16.dp),
                            onClick = { coverVisible = false },
                        )
                    }
                    LrcPanel(
                        lrcData = lrcData,
                        lrcProgress = lrcProgress,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp),
                    )
                }
                // 定时回显标签 (左上, 对照 app 端 FilletLabel)
                if (timerMinute > 0) {
                    FilletLabel(
                        text = "${timerMinute}m",
                        iconKey = "ic_time_add_24dp",
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    )
                }
                // 倍速回显标签 (右上, 对照 app 端 speedText)
                if (speed != 1f) {
                    FilletLabel(
                        text = "%.1fX".format(speed),
                        iconKey = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    )
                }
            }
            ProgressRow(
                progressMs = progressMs,
                bufferMs = bufferMs,
                durationMs = durationMs,
                onSeek = { AudioPlayShared.adjustProgress(it) },
            )
            PlayMenu(
                isPlaying = isPlaying,
                loading = loading,
                playMode = playMode,
                timerMinute = timerMinute,
                speed = speed,
                prevEnabled = AudioPlayShared.durChapterIndex > 0,
                nextEnabled = AudioPlayShared.durChapterIndex < AudioPlayShared.simulatedChapterSize - 1,
                onTogglePlay = {
                    when (AudioPlayShared.status) {
                        Status.PLAY -> AudioPlayShared.pause()
                        Status.PAUSE -> AudioPlayShared.resume()
                        else -> AudioPlayShared.loadOrUpPlayUrl()
                    }
                },
                onStop = { AudioPlayShared.stop() },
                onPrev = { AudioPlayShared.prev() },
                onNext = { AudioPlayShared.next() },
                onChangePlayMode = { AudioPlayShared.changePlayMode() },
                onOpenToc = { onOpenToc(book) },
            )
        }
    }
}

// ---- 标题栏 (对照 app 端 AudioTitleBar: 返回 + 标题 + 换源) ----

@Composable
private fun AudioTitleBar(
    title: String,
    onBack: () -> Unit,
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
                contentDescription = rememberString("change_source"),
                tint = Color.White,
            )
        }
    }
}

// ---- 模糊背景 (对照 app 端 BlurBg: 封面铺满 + blur) ----

@Composable
private fun BlurBg(coverUrl: String?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, coverUrl) {
        if (coverUrl.isNullOrEmpty()) return@produceState
        value = loadAudioCover(coverUrl)
    }
    Box(modifier) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 加载中/失败: 深色兜底 (避免白底刺眼)
            Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
        }
    }
}

// ---- 圆形封面 (对照 app 端 CoverImage: 200dp 圆形 + accent 描边) ----

@Composable
private fun CoverImage(coverUrl: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val bitmap by produceState<ImageBitmap?>(null, coverUrl) {
        if (coverUrl.isEmpty()) return@produceState
        value = loadAudioCover(coverUrl)
    }
    val bmp = bitmap
    Box(
        modifier
            .size(200.dp)
            .clip(CircleShape)
            .border(2.dp, accent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = rememberString("cover"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 兜底: 书名首字 (对照 DesktopBookCover.InfoCover 占位风格)
            Text(
                text = "?",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---- 歌词区 (简化版, 对照 app 端 LrcPanel) ----

/**
 * 歌词显示 (桌面端简化实现)。
 *
 * app 端用自绘 LrcView (滚动 + 渐变动画), 桌面端用 LazyColumn 文本列表 + 当前行高亮。
 * 歌词数据由 [io.legado.desktop.audio.DesktopAudioPlayProvider.loadLrcData] 经
 * EventBus.AUDIO_LRC 推送; AUDIO_LRCPROGRESS 推进当前行高亮 (每秒随播放位置刷新)。
 * 无歌词 (subContent 规则空 / 加载失败) 时显示占位文本。
 */
@Composable
private fun LrcPanel(
    lrcData: List<Pair<Int, String>>,
    lrcProgress: Int,
    modifier: Modifier = Modifier,
) {
    if (lrcData.isEmpty()) {
        // 无歌词: 居中占位提示
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = rememberString("no_lyrics"),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )
        }
        return
    }
    // 计算当前高亮行 (时间戳 <= lrcProgress 的最后一行)
    val currentLine = remember(lrcData, lrcProgress) {
        lrcData.indexOfLast { it.first <= lrcProgress }.coerceAtLeast(0)
    }
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lrcData) { line ->
            val isActive = lrcData.indexOf(line) == currentLine
            Text(
                text = line.second,
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                fontSize = if (isActive) 16.sp else 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- 定时/倍速回显标签 (对照 app 端 FilletLabel) ----

@Composable
private fun FilletLabel(text: String, iconKey: String?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0x66000000), RoundedCornerShape(8.dp))
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

// ---- 进度条 (对照 app 端 ProgressRow + AudioSeekBar: 自绘 Canvas + 拖动 seek) ----

@Composable
private fun ProgressRow(
    progressMs: Int,
    bufferMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // 拖动中的预览值 (拖动期间不回显事件进度, 对照 app 端 dragValue)
    var dragValue by remember { mutableStateOf<Int?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
            activeColor = accent,
            bufferColor = accent.copy(alpha = 0.5f),
            onDrag = { dragValue = it },
            onDragFinished = {
                dragValue?.let { onSeek(it) }
                dragValue = null
            },
            modifier = Modifier.weight(1f).height(25.dp),
        )
        Text(durationMs.toDurationTime(), color = Color.White, fontSize = 14.sp)
    }
}

/**
 * 自绘 SeekBar (对照 app 端 AudioSeekBar: 背景 + 缓冲层 + 已播层 + thumb)。
 *
 * 支持 点击跳转 + 水平拖动 seek, 与 app 端 pointerInput 行为一致。
 */
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
            // 进度背景 (对照 app 端 0xB3FFFFFF)
            drawLine(Color(0xB3FFFFFF), Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            if (bufX > startX) {
                drawLine(bufferColor, Offset(startX, cy), Offset(bufX, cy), trackH, StrokeCap.Round)
            }
            drawLine(activeColor, Offset(startX, cy), Offset(playX, cy), trackH, StrokeCap.Round)
            drawCircle(activeColor, thumbR, Offset(playX, cy))
        }
    }
}

// ---- 播放控制排 (对照 app 端 PlayMenu) ----

@Composable
private fun PlayMenu(
    isPlaying: Boolean,
    loading: Boolean,
    playMode: AudioPlayShared.PlayMode,
    timerMinute: Int,
    speed: Float,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangePlayMode: () -> Unit,
    onOpenToc: () -> Unit,
) {
    // 定时/倍速弹窗状态 (对照 app 端 showTimer/showSpeed, 桌面端用 AlertDialog 替代 Popup)
    var showTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 定时 (对照 app 端 ic_timer_black_24dp, 桌面端用 ic_time_add_24dp)
        Box {
            PlayMenuButton(
                iconKey = "ic_time_add_24dp",
                contentDescription = rememberString("set_timer"),
            ) { showTimer = true }
            if (showTimer) {
                TimerDialog(
                    initial = timerMinute,
                    onProgressChanged = { AudioPlayShared.setTimer(it) },
                    onDismiss = { showTimer = false },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 倍速 (对照 app 端 ic_fast_forward, 桌面端用 ic_speed Material Icon)
        Box {
            PlayMenuButton(
                iconKey = "ic_speed",
                contentDescription = rememberString("speed"),
            ) { showSpeed = true }
            if (showSpeed) {
                SpeedDialog(
                    initial = speed,
                    onProgressChanged = { AudioPlayShared.adjustSpeed(it) },
                    onDismiss = { showSpeed = false },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 上一章
        PlayMenuButton(
            iconKey = "ic_skip_previous",
            contentDescription = rememberString("previous_chapter"),
            enabled = prevEnabled,
        ) { onPrev() }
        // 播放/暂停 (圆形白底, 对照 app 端 FloatingActionButton 风格)
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
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        // 下一章
        PlayMenuButton(
            iconKey = "ic_skip_next",
            contentDescription = rememberString("next_chapter"),
            enabled = nextEnabled,
        ) { onNext() }
        Spacer(Modifier.weight(1f))
        // 停止 (任务要求, app 端 PlayMenu 无显式 stop, 桌面端补一个)
        PlayMenuButton(
            iconKey = "ic_stop_black_24dp",
            contentDescription = rememberString("stop"),
        ) { onStop() }
        Spacer(Modifier.weight(1f))
        // 播放模式 (对照 app 端 playMode.iconRes, 桌面端用 Material Icons 替代)
        PlayMenuButton(
            iconVector = playModeIcon(playMode),
            contentDescription = rememberString("play_mode"),
        ) { onChangePlayMode() }
        Spacer(Modifier.weight(1f))
        // 章节列表 (对照 app 端 ic_chapter_list, 桌面端用 ic_toc)
        PlayMenuButton(
            iconKey = "ic_toc",
            contentDescription = rememberString("chapter_list"),
        ) { onOpenToc() }
    }
}

/** 46dp 圆钮 (对照 app 端 PlayMenuButton: 圆形按压态 + 白图标/禁用 25% 白) */
@Composable
private fun PlayMenuButton(
    iconKey: String? = null,
    iconVector: ImageVector? = null,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) Color.White else Color(0x3FFFFFFF)
        when {
            iconVector != null -> Icon(
                imageVector = iconVector,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
            iconKey != null -> Icon(
                painter = rememberPainter(iconKey),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}

// ---- 定时/倍速弹窗 (对照 app 端 SliderPopupCard, 桌面端用 AlertDialog) ----

@Composable
private fun TimerDialog(
    initial: Int,
    onProgressChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rememberString("set_timer")) },
        text = {
            Column {
                Text(rememberString("timer_m", value))
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        value = it.toInt()
                        onProgressChanged(value)
                    },
                    valueRange = 0f..180f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(rememberString("ok")) }
        },
    )
}

@Composable
private fun SpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rememberString("speed")) },
        text = {
            Column {
                Text("%.1fX".format(value))
                // 0.5..3.0 step 0.1 → 26 个值, steps = 26 - 2 = 24
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        onProgressChanged(it)
                    },
                    valueRange = 0.5f..3.0f,
                    steps = 24,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(rememberString("ok")) }
        },
    )
}

// ---- 辅助: PlayMode 图标映射 (对照 app 端 PlayMode.iconRes, 桌面端用 Material Icons) ----

/**
 * 播放模式 → Material Icon 映射。
 *
 * app 端用 R.drawable.ic_play_mode_* (SVG 资源未下沉到 shared), 桌面端用 Material Icons 替代,
 * 视觉语义对齐:
 * - LIST_END_STOP (列表结束停止) → [Icons.Filled.LastPage]
 * - SINGLE_LOOP (单曲循环) → [Icons.Filled.RepeatOne]
 * - RANDOM (随机) → [Icons.Filled.Shuffle]
 * - LIST_LOOP (列表循环) → [Icons.Filled.Repeat]
 */
private fun playModeIcon(mode: AudioPlayShared.PlayMode): ImageVector = when (mode) {
    AudioPlayShared.PlayMode.LIST_END_STOP -> Icons.Filled.LastPage
    AudioPlayShared.PlayMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
    AudioPlayShared.PlayMode.RANDOM -> Icons.Filled.Shuffle
    AudioPlayShared.PlayMode.LIST_LOOP -> Icons.Filled.Repeat
}

// ---- 辅助: sticky 事件订阅为 Compose State (对照 app 端 observeEventSticky) ----

/**
 * 订阅 [FlowBus.withSticky] 事件为 Compose [State], 初始值为 [initial]。
 *
 * 桌面端等价 app 端 `observeEventSticky<EventBus.XXX> { ... }`:
 * FlowBus.withSticky 的 replay=1, 首次 collect 立即收到最近一次 post 的值;
 * 未 post 过时保持 [initial] 直到首次 post。
 *
 * @param key EventBus 常量 (如 [EventBus.AUDIO_STATE])
 * @param initial 初始值 (未 post 过时使用)
 */
@Composable
private fun <T> stickyEventState(key: String, initial: T): State<T> {
    return produceState(initial, key) {
        FlowBus.withSticky(key).collect {
            @Suppress("UNCHECKED_CAST")
            value = it as T
        }
    }
}

// ---- 辅助: 封面加载 (参照 DesktopBookCover.loadCoverBitmap, 不引入 Glide) ----

/**
 * 加载封面为 [ImageBitmap] (本地路径或网络 URL)。
 *
 * 参照 [io.legado.desktop.ui.component.DesktopBookCover] 的加载策略:
 * - `file://` / 绝对路径 `/...`: [ImageIO.read] 读文件
 * - `http://` / `https://`: [OkHttpClientProviders] 下载字节流后 [ImageIO.read] 解码
 * - 任意步骤异常: 返回 null (调用方走占位)
 *
 * 独立实现而非复用 DesktopBookCover.loadCoverBitmap (private), 避免修改其可见性。
 */
private suspend fun loadAudioCover(src: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val image = when {
            src.startsWith("file://") -> ImageIO.read(File(src.removePrefix("file://")))
            src.startsWith("/") -> ImageIO.read(File(src))
            src.startsWith("http://") || src.startsWith("https://") -> {
                val client = OkHttpClientProviders.get().okHttpClient
                val req = Request.Builder().url(src).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.bytes()?.let { ImageIO.read(ByteArrayInputStream(it)) }
                }
            }
            else -> null
        }
        image?.toComposeImageBitmap()
    }.onFailure { AppLog.put("桌面音频封面加载失败: $src\n${it.message}", it) }.getOrNull()
}

// ---- 辅助: 毫秒 → mm:ss 时长格式 (对照 app 端 toDurationTime) ----

private fun Int.toDurationTime(): String {
    if (this <= 0) return "00:00"
    val totalSec = this / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
