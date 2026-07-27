package io.legado.app.ui.book.audio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.FlowBus

/**
 * iOS 端音频播放页入口 (包装 shared/sharedUiMain 的 [AudioPlayScreenContent])。
 *
 * 状态面: [FlowBus.withSticky] 订阅 [EventBus].AUDIO_* 刷新 UI;
 * 命令面: [AudioPlayShared] 派发到已注册的 IosAudioPlayCommander (AVPlayer);
 * 平台 slot: 封面走 Coil3 (注册见 IosBookImageLoader), 歌词/弹窗照抄 desktop。
 *
 * @param book 待播放的音频书
 * @param onBack 返回回调
 * @param onOpenToc 跳转目录 (由 IosNavHost 注入)
 * @param onOpenChangeSource 跳转切换书源 (由 IosNavHost 注入)
 */
@Composable
fun IosAudioPlayScreen(
    book: Book,
    onBack: () -> Unit,
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
) {
    // 生命周期接线: 进入初始化 + 退出释放 (对照 app 端 AudioPlayViewModel.initData / Activity.onDestroy)
    LaunchedEffect(book.bookUrl) {
        AudioPlayShared.inBookshelf = book.type and BookType.notShelf == 0
        AudioPlayShared.resetData(book)
        if (AudioPlayShared.status == Status.STOP) {
            AudioPlayShared.loadOrUpPlayUrl()
        }
    }
    // 退出时若已停/已暂停才停止, 释放 AVPlayer (LOADING 算"在播", 留给后台继续)
    DisposableEffect(book.bookUrl) {
        onDispose {
            val cur = AudioPlayShared.status
            if (cur == Status.STOP || cur == Status.PAUSE) {
                AudioPlayShared.stop()
            }
        }
    }

    // 订阅 EventBus sticky 事件为 Compose State (对照 app 端 observeLiveBus)
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
    val coverUrl by stickyEventState(EventBus.AUDIO_COVER, "")

    val isPlaying = status == Status.PLAY
    val resolvedCoverUrl = coverUrl.ifBlank { book.getDisplayCover() }
    var coverVisible by remember { mutableStateOf(true) }

    AudioPlayScreenContent(
        title = book.name,
        subTitle = subTitle,
        coverUrl = resolvedCoverUrl,
        coverVisible = coverVisible && !resolvedCoverUrl.isNullOrEmpty(),
        timerMinute = timerMinute,
        speed = speed,
        progressMs = progressMs,
        durationMs = durationMs,
        bufferMs = bufferMs,
        isPlaying = isPlaying,
        loading = loading,
        playMode = playMode,
        prevEnabled = AudioPlayShared.durChapterIndex > 0,
        nextEnabled = AudioPlayShared.durChapterIndex < AudioPlayShared.simulatedChapterSize - 1,
        accentColor = AppTheme.colors.accent,
        onBack = onBack,
        onOpenChangeSource = onOpenChangeSource,
        onCoverClick = { coverVisible = false },
        onTogglePlay = {
            when (AudioPlayShared.status) {
                Status.PLAY -> AudioPlayShared.pause()
                Status.PAUSE -> AudioPlayShared.resume()
                else -> AudioPlayShared.loadOrUpPlayUrl()
            }
        },
        onPrev = { AudioPlayShared.prev() },
        onNext = { AudioPlayShared.next() },
        onChangePlayMode = { AudioPlayShared.changePlayMode() },
        onOpenToc = onOpenToc,
        onSeek = { AudioPlayShared.adjustProgress(it) },
        onSetTimer = { AudioPlayShared.setTimer(it) },
        onSetSpeed = { AudioPlayShared.adjustSpeed(it) },
        onStop = { AudioPlayShared.stop() },
        coverSlot = { url, modifier -> CoverSlot(url, modifier) },
        // blurBgSlot 不传: Content 内部复用 coverSlot
        lrcSlot = { modifier -> LrcSlot(lrcData, lrcProgress, modifier) },
        titleBarTrailingSlot = {},
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            TimerDialog(initial, onProgressChanged, onDismiss)
        },
        speedDialogSlot = { initial, onProgressChanged, onDismiss ->
            SpeedDialog(initial, onProgressChanged, onDismiss)
        },
    )
}

// 平台 coverSlot: Coil3 加载封面, 失败兜底 "?" (对照 desktop CoverSlot)

@Composable
private fun CoverSlot(coverUrl: String?, modifier: Modifier) {
    val painter = rememberAsyncImagePainter(coverUrl)
    val state by painter.state.collectAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = rememberString("cover"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = "?",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// 平台 lrcSlot: LazyColumn 文本列表 + 当前行高亮 (照抄 desktop LrcSlot)

@Composable
private fun LrcSlot(
    lrcData: List<Pair<Int, String>>,
    lrcProgress: Int,
    modifier: Modifier = Modifier,
) {
    if (lrcData.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = rememberString("no_lyrics"),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )
        }
        return
    }
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

// 定时/倍速弹窗 (AlertDialog + Slider, 照抄 desktop)

@Composable
private fun TimerDialog(
    initial: Int,
    onProgressChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("set_timer"),
        okButton = AlertButton(rememberString("ok")),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
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
    }
}

@Composable
private fun SpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = rememberString("speed"),
        okButton = AlertButton(rememberString("ok")),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
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
    }
}

// 辅助: sticky 事件订阅为 Compose State (照抄 desktop stickyEventState)

@Composable
private fun <T> stickyEventState(key: String, initial: T): State<T> {
    return produceState(initial, key) {
        FlowBus.withSticky(key).collect {
            @Suppress("UNCHECKED_CAST")
            value = it as T
        }
    }
}
