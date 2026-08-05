package io.legado.app.ui.book.audio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 音频播放页全端共享平台 slot (对照原版 AudioPlayActivity 的平台渲染):
 *
 * - [SharedAudioCoverSlot]: 圆形封面 (原版 ivCover; 经 BookImageLoaders 加载, 未注册平台走占位)
 * - [SharedAudioBlurBgSlot]: 模糊背景 (原版 iv_bg: loadBlur 整图 + 300ms TransitionDrawable 淡入;
 *   均匀遮罩 #3A000000 由 AudioPlayScreenContent 统一叠加, 此处不重复)
 * - [AudioPlayTimerDialog] / [AudioPlaySpeedDialog]: 定时/倍速弹窗 (原版 Popup+SliderPopupCard 的
 *   AlertDialog 等价, 四端共用)
 * - [SharedAudioPlayScreenContent]: 四端 Provider 的统一 Content 组装 (对照原版 AudioPlayActivity);
 *   歌词用 [LrcViewShared] (复刻原版 LrcView), 取色用 [rememberLrcColors]
 *   (复刻原版 updateLrcColor → setColors + SeekBar tint)
 *
 * Android 端差异仅经参数覆盖 (titleBarTrailingSlot=评论钮 + app 端默认值),
 * iOS/OHOS/desktop 走默认参数。
 */
@Composable
fun SharedAudioPlayScreenContent(
    state: AudioPlayUiState,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookSourceEdit: (String) -> Unit,
    onOpenReview: () -> Unit,
    overflowActions: AudioPlayOverflowActions,
    onEvent: (AudioPlayUiEvent) -> Unit,
    titleBarTrailingSlot: @Composable RowScope.() -> Unit = {},
    timerIconKey: String = "ic_time_add_24dp",
    speedIconKey: String = "ic_speed",
    chapterListIconKey: String = "ic_toc",
    filletLabelColor: Color = Color(0x66000000),
    playMenuButtonPressedBgEnabled: Boolean = false,
    playMenuAlpha: Float = 1f,
    titleBarHorizontalPadding: Dp = 8.dp,
    playModeIconPadding: Dp = 4.dp,
) {
    // 封面取色 → 歌词 + SeekBar 配色 (对照原版 updateCover → updateLrcColor)
    val lrcColors = rememberLrcColors(
        state.coverUrl,
        sourceOrigin = AudioPlayShared.book?.origin,
    )
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
        lrcActiveColor = lrcColors?.first,
        lrcInactiveColor = lrcColors?.second,
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
        coverSlot = { url, modifier -> SharedAudioCoverSlot(url, modifier) },
        blurBgSlot = { url, modifier -> SharedAudioBlurBgSlot(url, modifier) },
        lrcSlot = { modifier ->
            LrcViewShared(
                lrcData = state.lrcData,
                lrcProgress = state.lrcProgress,
                primaryColor = lrcColors?.first ?: Color(0xFFFFFFFF),
                secondaryColor = lrcColors?.second ?: Color(0x80FFFFFF),
                onLineClick = { onEvent(AudioPlayUiEvent.LrcClick(it)) },
                modifier = modifier,
            )
        },
        titleBarTrailingSlot = titleBarTrailingSlot,
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            AudioPlayTimerDialog(initial, onProgressChanged, onDismiss)
        },
        speedDialogSlot = { initial, onProgressChanged, onDismiss ->
            AudioPlaySpeedDialog(initial, onProgressChanged, onDismiss)
        },
        timerIconKey = timerIconKey,
        speedIconKey = speedIconKey,
        chapterListIconKey = chapterListIconKey,
        filletLabelColor = filletLabelColor,
        playMenuButtonPressedBgEnabled = playMenuButtonPressedBgEnabled,
        playMenuAlpha = playMenuAlpha,
        titleBarHorizontalPadding = titleBarHorizontalPadding,
        playModeIconPadding = playModeIconPadding,
    )
}

// ---- 圆形封面 slot (原版 ivCover; BookImageLoaders 异步加载, 未注册平台回退占位底) ----

@Composable
private fun SharedAudioCoverSlot(coverUrl: String?, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUrl, loader) {
        bitmap = null
        if (coverUrl.isNullOrBlank() || loader == null) return@LaunchedEffect
        bitmap = loader.loadCoverOrNull(coverUrl, AudioPlayShared.book?.origin)
    }
    Box(
        modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF165DFF))
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ---- 模糊背景 slot (原版 iv_bg: 整图模糊 + 300ms TransitionDrawable 淡入; 均匀遮罩由 shared 层叠) ----

@Composable
private fun SharedAudioBlurBgSlot(coverUrl: String?, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    var bitmap by remember(coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUrl, loader) {
        bitmap = null
        if (coverUrl.isNullOrBlank() || loader == null) return@LaunchedEffect
        bitmap = loader.loadCoverOrNull(coverUrl, AudioPlayShared.book?.origin)
    }
    val success = bitmap != null
    // 300ms 淡入 (对照原版 TransitionDrawable.startTransition(300); 切封面时重新淡入)
    val alpha by animateFloatAsState(
        targetValue = if (success) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
    )
    Box(modifier) {
        if (success) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(24.dp)
                    .graphicsLayer { this.alpha = alpha },
                contentScale = ContentScale.Crop,
            )
        } else {
            // 加载中占位: accent 半透明 (原版露 Activity 深色背景; 浅色主题下避免闪白,
            // 成功淡入后即被覆盖)
            Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
        }
    }
}

// ---- 定时弹窗 (原版 SliderPopupCard 的 AlertDialog 等价, 四端共用) ----

@Composable
fun AudioPlayTimerDialog(
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

// ---- 倍速弹窗 (原版 SliderPopupCard 的 AlertDialog 等价, 四端共用) ----

@Composable
fun AudioPlaySpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // shared 传入 Float 初值; 内部用 (speed*10).toInt() int 形态 slider (对齐原版)
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
