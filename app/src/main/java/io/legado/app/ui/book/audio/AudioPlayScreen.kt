package io.legado.app.ui.book.audio

import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmapOrNull
import coil3.asDrawable
import coil3.load
import coil3.request.placeholder
import io.legado.app.R
import io.legado.app.model.AudioPlay
import io.legado.app.model.blurConfig
import io.legado.app.model.coverConfig
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.LrcView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getRepresentativeColor
import java.util.Locale
import org.jetbrains.compose.resources.stringResource

/**
 * 音频播放页 (薄壳): 主体 UI 调用 shared [AudioPlayScreenContent], 平台特殊部分以 slot 注入。
 *
 * 平台 slot:
 * - [AudioPlayScreenContent.coverSlot]: 圆形封面 (Coil + AndroidView + coverConfig + placeholder)
 * - [AudioPlayScreenContent.blurBgSlot]: 模糊背景 (Coil + AndroidView + blurConfig + TransitionDrawable 淡入 +
 *   onBlurCoverLoaded 回调, 用于推导 lrcColors 调色板)
 * - [AudioPlayScreenContent.lrcSlot]: 自绘 LrcView (AndroidView 桥接, 滚动 + 渐变动画)
 * - [AudioPlayScreenContent.titleBarTrailingSlot]: review 钮 + 溢出菜单 (登录/复制URL/变量/编辑书源/锁屏/书签/日志)
 * - [AudioPlayScreenContent.timerDialogSlot]/[speedDialogSlot]: Popup + AppSlider (对照原 SliderPopupCard)
 */
@Composable
fun AudioPlayScreen(activity: AudioPlayActivity) {
    // 构造溢出菜单动作 (对照 AudioPlayRoute 的 overflowActions, 经 shared AudioPlayOverflowMenu 渲染)
    val source = AudioPlay.bookSource
    val overflowActions = AudioPlayOverflowActions(
        hasLogin = source?.hasLogin() == true,
        onLogin = { activity.showLogin() },
        onCopyAudioUrl = { activity.copyAudioUrl() },
        onOpenAudioUrl = { activity.openAudioUrl() },
        onSetSourceVariable = { activity.showSourceVariable() },
        onSetBookVariable = { activity.showBookVariable() },
        onEditBookSource = { activity.editSource() },
        onAddBookmark = { activity.addBookmark() },
        onShowAppLog = { activity.showAppLog() },
        onToggleWakeLock = { activity.toggleWakeLock() },
    )
    AudioPlayAndroidContent(
        state = AudioPlayUiState(
            title = activity.titleText,
            subTitle = activity.subTitle,
            coverUrl = activity.coverUrl,
            coverVisible = activity.coverVisible,
            timerMinute = activity.timerMinute,
            speed = activity.speed,
            progressMs = activity.progressMs,
            durationMs = activity.durationMs,
            bufferMs = activity.bufferMs,
            isPlaying = activity.isPlaying,
            loading = activity.loading,
            playMode = activity.playMode,
            prevEnabled = activity.prevEnabled,
            nextEnabled = activity.nextEnabled,
            lrcData = activity.lrcData,
            lrcProgress = activity.lrcProgress,
        ),
        onBack = { activity.onBackPressedDispatcher.onBackPressed() },
        onOpenChangeSource = activity::showChangeSource,
        onOpenToc = activity::openChapterList,
        onOpenBookSourceEdit = { activity.editSource() },
        onOpenReview = activity::openReview,
        overflowActions = overflowActions,
        onEvent = { event ->
            when (event) {
                AudioPlayUiEvent.CoverClick -> activity.coverVisible = false
                AudioPlayUiEvent.TogglePlay -> activity.playButton()
                AudioPlayUiEvent.Prev -> activity.viewModel.prev()
                AudioPlayUiEvent.Next -> activity.viewModel.next()
                AudioPlayUiEvent.ChangePlayMode -> activity.viewModel.changePlayMode()
                is AudioPlayUiEvent.Seek -> activity.viewModel.adjustProgress(event.positionMs)
                is AudioPlayUiEvent.SetTimer -> activity.viewModel.setTimer(event.minute)
                is AudioPlayUiEvent.SetSpeed -> activity.viewModel.adjustSpeed(event.speed)
                is AudioPlayUiEvent.LrcClick -> activity.onLrcClick(event.time)
                is AudioPlayUiEvent.Init -> Unit
                is AudioPlayUiEvent.UpdateInShelf -> Unit
            }
        },
        activity = activity,
    )
}

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
    activity: AudioPlayActivity? = null,
) {
    val colors = AppTheme.colors
    var lrcColors by remember { mutableStateOf<Pair<Int, Int>?>(activity?.lrcColors) }
    val lrcActiveColor = lrcColors?.let { Color(it.first) }
    val lrcInactiveColor = lrcColors?.let { Color(it.second) }
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
        accentColor = colors.accent,
        lrcActiveColor = lrcActiveColor,
        lrcInactiveColor = lrcInactiveColor,
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
        coverSlot = { url, modifier -> CoverSlot(url, modifier) },
        blurBgSlot = { url, modifier ->
            BlurBgSlot(url, modifier) { colorsPair -> lrcColors = colorsPair }
        },
        lrcSlot = { modifier -> LrcSlot(state, onEvent, lrcColors, modifier) },
        titleBarTrailingSlot = {
            TitleBarTrailing(onOpenReview = onOpenReview)
        },
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            SliderPopupCard(
                max = 180,
                initial = initial,
                formatText = { "${it}m" },
                onProgressChanged = onProgressChanged,
                onDismiss = onDismiss,
            )
        },
        speedDialogSlot = { initial, onProgressChanged, onDismiss ->
            // shared 传入 Float 初值; 原实现用 (speed*10).toInt() int 形态 slider, 这里反向 *10 取整
            SliderPopupCard(
                max = 30,
                initial = (initial * 10).toInt(),
                formatText = { String.format(Locale.ROOT, "%.1fX", it / 10.0f) },
                onProgressChanged = { onProgressChanged(it / 10.0f) },
                onDismiss = onDismiss,
            )
        },
        timerIconKey = "ic_timer_black_24dp",
        speedIconKey = "ic_fast_forward",
        chapterListIconKey = "ic_chapter_list",
        filletLabelColor = colorResource(R.color.arco_fill_3),
        playMenuButtonPressedBgEnabled = true,
        playMenuAlpha = 0.7f,
        titleBarHorizontalPadding = 0.dp,
        playModeIconPadding = 8.dp,
    )
}

// ---- 标题栏尾部: review 钮 (溢出菜单由 shared AudioPlayOverflowMenu 经 overflowActions 渲染) ----

@Composable
private fun TitleBarTrailing(onOpenReview: () -> Unit) {
    if (AudioPlay.bookSource?.reviewRule?.reviewUrl.isNullOrBlank() == false) {
        IconButton(onClick = onOpenReview) {
            Icon(
                painter = rememberPainter("ic_edit"),
                contentDescription = stringResource(R.string.review),
                tint = Color.White,
            )
        }
    }
}

// ---- 平台 coverSlot: 圆形封面 (Coil + AndroidView + coverConfig + placeholder) ----

@Composable
private fun CoverSlot(coverUrl: String?, modifier: Modifier) {
    val coverDesc = stringResource(R.string.img_cover)
    AndroidView(
        factory = {
            AppCompatImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = coverDesc
            }
        },
        modifier = modifier,
        update = { iv ->
            if (coverUrl != null && iv.tag != coverUrl) {
                iv.tag = coverUrl
                iv.load(coverUrl) {
                    coverConfig(
                        seed = AudioPlay.book?.name,
                        sourceOrigin = AudioPlay.bookSource?.bookSourceUrl,
                    )
                    // 不落持久区: 对照原版 AudioPlayActivity.updateCover 的 BookCover.load 默认
                    // inBookshelf = false; 持久区只留给书架列表封面
                    placeholder(iv.drawable)
                }
            }
        },
    )
}

// ---- 平台 blurBgSlot: 模糊背景 (blurConfig + TransitionDrawable 淡入 + onBlurCoverLoaded 回调) ----

@Composable
private fun BlurBgSlot(
    coverUrl: String?,
    modifier: Modifier,
    onColorsChanged: (Pair<Int, Int>) -> Unit,
) {
    AndroidView(
        factory = {
            AppCompatImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        modifier = modifier,
        update = { iv ->
            if (coverUrl != null && iv.tag != coverUrl) {
                iv.tag = coverUrl
                iv.load(coverUrl) {
                    blurConfig(
                        seed = AudioPlay.book?.name,
                        sourceOrigin = AudioPlay.bookSource?.bookSourceUrl,
                    )
                    listener(
                        onSuccess = { _, result ->
                            val newDrawable = result.image?.asDrawable(iv.resources)
                            if (newDrawable != null) {
                                if (iv.drawable != null) {
                                    val transitionDrawable =
                                        TransitionDrawable(arrayOf(iv.drawable, newDrawable))
                                    transitionDrawable.isCrossFadeEnabled = true
                                    iv.setImageDrawable(transitionDrawable)
                                    transitionDrawable.startTransition(300)
                                } else {
                                    iv.setImageDrawable(newDrawable)
                                }
                                newDrawable.toBitmapOrNull()?.deriveLrcColors()
                                    ?.let(onColorsChanged)
                            }
                        },
                    )
                }
            }
        },
    )
}

// ---- 平台 lrcSlot: 自绘 LrcView (AndroidView 桥接, 滚动 + 渐变动画) ----

@Composable
private fun LrcSlot(
    state: AudioPlayUiState,
    onEvent: (AudioPlayUiEvent) -> Unit,
    colors: Pair<Int, Int>?,
    modifier: Modifier,
) {
    val holder = remember { LrcKeys() }
    AndroidView(
        factory = { ctx ->
            LrcView(ctx).apply {
                val pad = 16.dpToPx()
                setPadding(pad, 0, pad, 0)
                setOnPlayClickListener { time ->
                    onEvent(AudioPlayUiEvent.LrcClick(time))
                    updateProgress(time)
                }
            }
        },
        modifier = modifier,
        update = { view ->
            state.lrcData?.let {
                if (holder.data !== it) {
                    holder.data = it
                    view.setLrcData(it)
                }
            }
            colors?.let {
                if (holder.colors != it) {
                    holder.colors = it
                    view.setColors(it.first, it.second)
                }
            }
            view.updateProgress(state.lrcProgress)
        },
    )
}

private class LrcKeys {
    var data: List<Pair<Int, String>>? = null
    var colors: Pair<Int, Int>? = null
}

private fun android.graphics.Bitmap.deriveLrcColors(): Pair<Int, Int>? {
    val meanColor = runCatching { getRepresentativeColor() }.getOrNull() ?: return null
    val secondaryHsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(meanColor, secondaryHsl)
    val isLight = secondaryHsl[2] > 0.6f
    secondaryHsl[2] = if (isLight) {
        (secondaryHsl[2] - 0.45f).coerceAtLeast(0.3f)
    } else {
        (secondaryHsl[2] + 0.45f).coerceAtMost(0.7f)
    }
    val secondaryColor = androidx.core.graphics.ColorUtils.HSLToColor(secondaryHsl)
    val primaryHsl = secondaryHsl.copyOf()
    primaryHsl[2] = if (isLight) {
        (primaryHsl[2] - 0.35f).coerceAtLeast(0.2f)
    } else {
        (primaryHsl[2] + 0.35f).coerceAtMost(0.8f)
    }
    return androidx.core.graphics.ColorUtils.HSLToColor(primaryHsl) to secondaryColor
}

// ---- 定时/倍速滑条弹窗 (原 SliderPopup: 全宽卡片, 锚点下沿上方 100dp) ----

@Composable
private fun SliderPopupCard(
    max: Int,
    initial: Int,
    formatText: (Int) -> String,
    onProgressChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val yOff = with(LocalDensity.current) { (-100).dp.roundToPx() }
    var value by remember { mutableIntStateOf(initial) }
    Popup(
        popupPositionProvider = remember(yOff) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset(0, anchorBounds.bottom + yOff)
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colorResource(R.color.background_card), DesignTokens.shapeDefault)
                .padding(8.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(formatText(value), color = AppTheme.colors.secondaryText, fontSize = 14.sp)
            AppSlider(
                value = value,
                max = max,
                onValueChange = {
                    value = it
                    onProgressChanged(it)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
