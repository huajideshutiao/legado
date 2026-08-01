package io.legado.app.ui.book.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.LrcView
import io.legado.app.utils.format

/**
 * Android 端音频播放页 Composable: 复用 shared [AudioPlayScreenContent],
 * 注入 Android 专属 slot (Coil3 封面 / [LrcView] 歌词 / AlertDialog 弹窗) 与 app 端默认值。
 *
 * 对照 desktop 端 DesktopAudioPlayPlatformProvider; app 端 AudioPlayScreen 的平台渲染下沉入口。
 */
@Composable
fun AudioPlayAndroidContent(
    state: AudioPlayUiState,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookSourceEdit: (String) -> Unit,
    onOpenReview: () -> Unit,
    overflowActions: AudioPlayOverflowActions,
    onEvent: (AudioPlayUiEvent) -> Unit,
) {
    AudioPlayScreenContent(
        title = state.title,
        subTitle = state.subTitle,
        coverUrl = state.coverUrl,
        coverVisible = state.coverVisible,
        timerMinute = state.timerMinute,
        speed = state.speed,
        progressMs = state.progressMs,
        durationMs = state.durationMs,
        bufferMs = state.bufferMs,
        isPlaying = state.isPlaying,
        loading = state.loading,
        playMode = state.playMode,
        prevEnabled = state.prevEnabled,
        nextEnabled = state.nextEnabled,
        accentColor = AppTheme.colors.accent,
        onBack = onBack,
        onOpenChangeSource = onOpenChangeSource,
        onCoverClick = { onEvent(AudioPlayUiEvent.CoverClick) },
        onTogglePlay = { onEvent(AudioPlayUiEvent.TogglePlay) },
        onPrev = { onEvent(AudioPlayUiEvent.Prev) },
        onNext = { onEvent(AudioPlayUiEvent.Next) },
        onChangePlayMode = { onEvent(AudioPlayUiEvent.ChangePlayMode) },
        onOpenToc = onOpenToc,
        onSeek = { onEvent(AudioPlayUiEvent.Seek(it)) },
        onSetTimer = { onEvent(AudioPlayUiEvent.SetTimer(it)) },
        onSetSpeed = { onEvent(AudioPlayUiEvent.SetSpeed(it)) },
        onStop = null,
        overflowActions = overflowActions,
        coverSlot = { url, modifier -> AndroidCoverSlot(url, modifier) },
        blurBgSlot = { url, modifier -> AndroidBlurBgSlot(url, modifier) },
        lrcSlot = { modifier -> AndroidLrcSlot(state, onEvent, modifier) },
        titleBarTrailingSlot = {
            // 评论入口 (对照 app 端 AudioPlayScreen 标题栏评论钮)
            IconButton(onClick = onOpenReview) {
                Icon(
                    painter = rememberPainter("ic_review_thumb_up"),
                    contentDescription = rememberString("review"),
                    tint = Color.White,
                )
            }
        },
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            AndroidTimerDialog(initial, onProgressChanged, onDismiss)
        },
        speedDialogSlot = { initial, onProgressChanged, onDismiss ->
            AndroidSpeedDialog(initial, onProgressChanged, onDismiss)
        },
        // app 端默认值 (对照 AudioPlayScreenContent.kt 注释)
        timerIconKey = "ic_timer_black_24dp",
        speedIconKey = "ic_fast_forward",
        chapterListIconKey = "ic_chapter_list",
        filletLabelColor = rememberColor("arco_fill_3"),
        playMenuButtonPressedBgEnabled = true,
        playMenuAlpha = 0.7f,
        titleBarHorizontalPadding = 0.dp,
        playModeIconPadding = 8.dp,
    )
}

// ---- 封面 slot: Coil3 AsyncImage (替代 app 端 Glide+AndroidView) ----

@Composable
private fun AndroidCoverSlot(coverUrl: String?, modifier: Modifier) {
    // 深色占位底, 避免封面未加载时透明穿透模糊背景
    Box(modifier.background(Color(0xFF2C2C2C))) {
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ---- 模糊背景 slot: Coil3 AsyncImage + blur (替代 app 端 blurConfig+TransitionDrawable 淡入) ----

@Composable
private fun AndroidBlurBgSlot(coverUrl: String?, modifier: Modifier) {
    Box(modifier) {
        if (!coverUrl.isNullOrEmpty()) {
            // TODO: API<31 时 Modifier.blur 为 no-op, 后续可补 RenderScript/TransitionDrawable 淡入方案
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ---- 歌词 slot: app 端自绘 LrcView (对照 app 端 AudioPlayScreen 歌词区) ----

@Composable
private fun AndroidLrcSlot(
    state: AudioPlayUiState,
    onEvent: (AudioPlayUiEvent) -> Unit,
    modifier: Modifier,
) {
    val lrcData = state.lrcData
    if (lrcData.isNullOrEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无歌词",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
        }
        return
    }
    AndroidView(
        factory = { context ->
            LrcView(context).apply {
                setOnPlayClickListener { time -> onEvent(AudioPlayUiEvent.LrcClick(time)) }
            }
        },
        update = { view ->
            // 仅在数据引用变化时重建, 避免每次重组都 clear+rebuild
            if (view.tag !== lrcData) {
                view.setLrcData(lrcData)
                view.tag = lrcData
            }
            if (state.lrcProgress >= 0) {
                view.updateProgress(state.lrcProgress)
            }
        },
        modifier = modifier,
    )
}

// ---- 定时弹窗: AlertDialog + AppSlider (替代 app 端 Popup+SliderPopupCard) ----

@Composable
private fun AndroidTimerDialog(
    initial: Int,
    onProgressChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column {
                Text("${value}m", color = AppTheme.colors.secondaryText)
                AppSlider(
                    value = value,
                    max = 180,
                    onValueChange = {
                        value = it
                        onProgressChanged(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}

// ---- 倍速弹窗: AlertDialog + AppSlider (替代 app 端 Popup+SliderPopupCard) ----

@Composable
private fun AndroidSpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // 内部用 (speed*10).toInt() int 形态 slider (对齐 desktop)
    var value by remember { mutableIntStateOf((initial * 10).toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放速度") },
        text = {
            Column {
                Text(
                    "%.1fX".format(value / 10.0f),
                    color = AppTheme.colors.secondaryText,
                )
                AppSlider(
                    value = value,
                    max = 30,
                    onValueChange = {
                        value = it
                        onProgressChanged(it / 10.0f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}
