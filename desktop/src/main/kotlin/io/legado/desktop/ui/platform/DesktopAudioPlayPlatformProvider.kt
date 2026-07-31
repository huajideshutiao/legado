package io.legado.desktop.ui.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import io.legado.app.ui.book.audio.AudioPlayOverflowActions
import io.legado.app.ui.book.audio.AudioPlayPlatformProvider
import io.legado.app.ui.book.audio.AudioPlayScreenContent
import io.legado.app.ui.book.audio.AudioPlayUiEvent
import io.legado.app.ui.book.audio.AudioPlayUiState
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.desktop.ui.component.rememberCoverPainter

/**
 * desktop 端 [AudioPlayPlatformProvider] 真实实现: 复用 shared [AudioPlayScreenContent]。
 *
 * # 平台 slot 注入 (对照 app 端 [io.legado.app.ui.book.audio.AudioPlayScreen])
 *
 * - **coverSlot**: Coil3 `rememberCoverPainter` + `Image` (圆形裁剪), 与 desktop 详情页封面同栈
 * - **blurBgSlot**: 同 painter + `blur(24.dp)` 模糊背景 (无 TransitionDrawable 淡入, 直接替换)
 * - **lrcSlot**: `LazyColumn` 简化版歌词 (当前行高亮 + 自动滚动)
 * - **timerDialogSlot / speedDialogSlot**: `AlertDialog` + `AppSlider` (替代 app 端 Popup)
 *
 * 播放引擎由 [io.legado.desktop.audio.DesktopAudioPlayProvider] (AudioPlayCommander/Bridge) 承载,
 * 此处仅提供音频页 UI Content, 与 app 端 AudioPlayScreen 职责对齐。
 */
class DesktopAudioPlayPlatformProvider : AudioPlayPlatformProvider {

    @Composable
    override fun Content(
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
            coverSlot = { url, modifier -> DesktopCoverSlot(url, modifier) },
            blurBgSlot = { url, modifier -> DesktopBlurBgSlot(url, modifier) },
            lrcSlot = { modifier -> DesktopLrcSlot(state, onEvent, modifier) },
            titleBarTrailingSlot = {},
            timerDialogSlot = { initial, onProgressChanged, onDismiss ->
                DesktopTimerDialog(initial, onProgressChanged, onDismiss)
            },
            speedDialogSlot = { initial, onProgressChanged, onDismiss ->
                DesktopSpeedDialog(initial, onProgressChanged, onDismiss)
            },
        )
    }
}

// ---- 封面 slot: Coil3 圆形封面 (对齐 app 端 CoverSlot, 替换 Glide→Coil3) ----

@Composable
private fun DesktopCoverSlot(coverUrl: String?, modifier: Modifier) {
    val painter = rememberCoverPainter(coverUrl, persistent = true)
    val state by painter.state.collectAsState()
    Box(modifier.clip(CircleShape).background(Color(0xFF165DFF))) {
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ---- 模糊背景 slot: Coil3 + blur (对齐 app 端 BlurBgSlot, 省略 TransitionDrawable 淡入) ----

@Composable
private fun DesktopBlurBgSlot(coverUrl: String?, modifier: Modifier) {
    val painter = rememberCoverPainter(coverUrl, persistent = true)
    val state by painter.state.collectAsState()
    Box(modifier) {
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(24.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // accent 半透明遮罩 (与 desktop 详情页 BlurCoverBg 一致)
        Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
    }
}

// ---- 歌词 slot: LazyColumn 简化版 (当前行高亮 + 自动滚动) ----

@Composable
private fun DesktopLrcSlot(
    state: AudioPlayUiState,
    onEvent: (AudioPlayUiEvent) -> Unit,
    modifier: Modifier,
) {
    val lrcData = state.lrcData
    val lrcProgress = state.lrcProgress
    val listState = rememberLazyListState()

    // 当前行变化时自动滚动到可视区域
    LaunchedEffect(lrcProgress) {
        if (lrcProgress >= 0 && lrcProgress < (lrcData?.size ?: 0)) {
            listState.animateScrollToItem(lrcProgress)
        }
    }

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

    LazyColumn(
        modifier = modifier,
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(lrcData.size) { index ->
            val (timeMs, text) = lrcData[index]
            val isActive = index == lrcProgress
            Text(
                text = text,
                color = if (isActive) AppTheme.colors.accent else Color.White.copy(alpha = 0.5f),
                fontSize = if (isActive) 16.sp else 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 16.dp)
                    .let { mod ->
                        // 点击歌词跳转到对应时间
                        if (text.isNotBlank()) mod.clickable {
                            onEvent(AudioPlayUiEvent.LrcClick(timeMs))
                        } else mod
                    },
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- 定时弹窗: AlertDialog + AppSlider (替代 app 端 Popup + SliderPopupCard) ----

@Composable
private fun DesktopTimerDialog(
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

// ---- 倍速弹窗: AlertDialog + AppSlider (替代 app 端 Popup + SliderPopupCard) ----

@Composable
private fun DesktopSpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // shared 传入 Float 初值; 内部用 (speed*10).toInt() int 形态 slider (对齐 app 端)
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
