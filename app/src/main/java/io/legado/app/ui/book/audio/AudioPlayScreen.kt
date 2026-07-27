package io.legado.app.ui.book.audio

import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
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
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AudioPlay
import io.legado.app.model.blurConfig
import io.legado.app.model.coverConfig
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.LrcView
import io.legado.app.utils.dpToPx
import java.util.Locale

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
    val colors = AppTheme.colors
    // 模糊背景代表色衍生主/次色, null 时回退 accent
    val lrcActiveColor = activity.lrcColors?.let { Color(it.first) }
    val lrcInactiveColor = activity.lrcColors?.let { Color(it.second) }
    AudioPlayScreenContent(
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
        accentColor = colors.accent,
        lrcActiveColor = lrcActiveColor,
        lrcInactiveColor = lrcInactiveColor,
        onBack = { activity.onBackPressedDispatcher.onBackPressed() },
        onOpenChangeSource = { activity.showChangeSource() },
        onCoverClick = { activity.coverVisible = false },
        onTogglePlay = { activity.playButton() },
        onPrev = { activity.viewModel.prev() },
        onNext = { activity.viewModel.next() },
        onChangePlayMode = { activity.viewModel.changePlayMode() },
        onOpenToc = { activity.openChapterList() },
        onSeek = { activity.viewModel.adjustProgress(it) },
        onSetTimer = { activity.viewModel.setTimer(it) },
        onSetSpeed = { activity.viewModel.adjustSpeed(it) },
        onStop = null,
        coverSlot = { url, modifier -> CoverSlot(url, modifier) },
        blurBgSlot = { url, modifier -> BlurBgSlot(activity, url, modifier) },
        lrcSlot = { modifier -> LrcSlot(activity, modifier) },
        titleBarTrailingSlot = { TitleBarTrailing(activity) },
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            SliderPopupCard(
                max = 180,
                initial = initial,
                formatText = { activity.getString(R.string.timer_m, it) },
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

// ---- 标题栏尾部: review + 溢出菜单 (登录/复制URL/变量/编辑书源/锁屏/书签/日志) ----

@Composable
private fun TitleBarTrailing(activity: AudioPlayActivity) {
    if (AudioPlay.bookSource?.reviewRule?.reviewUrl.isNullOrBlank() == false) {
        IconButton(onClick = { activity.openReview() }) {
            Icon(
                painter = rememberPainter("ic_edit"),
                contentDescription = stringResource(R.string.review),
                tint = Color.White,
            )
        }
    }
    AudioOverflowMenu(activity)
}

@Composable
private fun AudioOverflowMenu(activity: AudioPlayActivity) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = rememberPainter("ic_more_vert"),
                contentDescription = stringResource(R.string.more_menu),
                tint = Color.White,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val dismiss = { expanded = false }
            if (AudioPlay.bookSource?.hasLogin() == true) {
                AudioMenuItem(R.string.login) { dismiss(); activity.showLogin() }
            }
            AudioMenuItem(R.string.copy_play_url) { dismiss(); activity.copyAudioUrl() }
            AudioMenuItem(R.string.set_source_variable) { dismiss(); activity.showSourceVariable() }
            AudioMenuItem(R.string.set_book_variable) { dismiss(); activity.showBookVariable() }
            AudioMenuItem(R.string.edit_book_source) { dismiss(); activity.editSource() }
            DropdownMenuItem(onClick = { dismiss(); activity.toggleWakeLock() }) {
                Text(
                    stringResource(R.string.audio_play_wake_lock),
                    color = colors.primaryText,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                AppMenuCheckbox(checked = AppConfig.audioPlayUseWakeLock)
            }
            AudioMenuItem(R.string.bookmark_add) { dismiss(); activity.addBookmark() }
            AudioMenuItem(R.string.log) { dismiss(); activity.showAppLog() }
        }
    }
}

@Composable
private fun AudioMenuItem(textRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(stringResource(textRes), color = AppTheme.colors.primaryText)
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
                    placeholder(iv.drawable)
                }
            }
        },
    )
}

// ---- 平台 blurBgSlot: 模糊背景 (blurConfig + TransitionDrawable 淡入 + onBlurCoverLoaded 回调) ----

@Composable
private fun BlurBgSlot(activity: AudioPlayActivity, coverUrl: String?, modifier: Modifier) {
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
                                newDrawable.toBitmapOrNull()?.let { activity.onBlurCoverLoaded(it) }
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
private fun LrcSlot(activity: AudioPlayActivity, modifier: Modifier) {
    val holder = remember { LrcKeys() }
    AndroidView(
        factory = { ctx ->
            LrcView(ctx).apply {
                val pad = 16.dpToPx()
                setPadding(pad, 0, pad, 0)
                setOnPlayClickListener { time ->
                    activity.onLrcClick(time)
                    updateProgress(time)
                }
            }
        },
        modifier = modifier,
        update = { view ->
            activity.lrcData?.let {
                if (holder.data !== it) {
                    holder.data = it
                    view.setLrcData(it)
                }
            }
            activity.lrcColors?.let {
                if (holder.colors != it) {
                    holder.colors = it
                    view.setColors(it.first, it.second)
                }
            }
            view.updateProgress(activity.lrcProgress)
        },
    )
}

private class LrcKeys {
    var data: List<Pair<Int, String>>? = null
    var colors: Pair<Int, Int>? = null
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
