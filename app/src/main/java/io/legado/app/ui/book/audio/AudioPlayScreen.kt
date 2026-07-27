package io.legado.app.ui.book.audio

import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmapOrNull
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.iconRes
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.LrcView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toDurationTime
import java.util.Locale

/** 音频播放页，对照 activity_audio_play.xml：模糊背景+遮罩、透明白字标题栏、圆形封面、歌词、进度条、播放控制排 */
@Composable
fun AudioPlayScreen(activity: AudioPlayActivity) {
    Box(Modifier.fillMaxSize()) {
        BlurBg(activity, Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x3A000000))
        )
        Column(Modifier.fillMaxSize()) {
            AudioTitleBar(activity)
            Text(
                text = activity.subTitle,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (activity.coverVisible) {
                        CoverImage(activity)
                    }
                    LrcPanel(
                        activity,
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 16.dp),
                    )
                }
                if (activity.timerMinute > 0) {
                    FilletLabel(
                        text = "${activity.timerMinute}m",
                        iconRes = R.drawable.ic_timer_black_24dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                    )
                }
                activity.speedText?.let {
                    FilletLabel(
                        text = it,
                        iconRes = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
            }
            ProgressRow(activity)
            PlayMenu(activity)
        }
    }
}

// ---- 标题栏(原 TitleBar themeMode=dark + 透明背景: 白字白图标) ----

@Composable
private fun AudioTitleBar(activity: AudioPlayActivity) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { activity.onBackPressedDispatcher.onBackPressed() }) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
                tint = Color.White,
            )
        }
        Text(
            text = activity.titleText,
            color = Color.White,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { activity.showChangeSource() }) {
            Icon(
                painter = painterResource(R.drawable.ic_exchange),
                contentDescription = stringResource(R.string.change_origin),
                tint = Color.White,
            )
        }
        if (AudioPlay.bookSource?.reviewRule?.reviewUrl.isNullOrBlank() == false) {
            IconButton(onClick = { activity.openReview() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.review),
                    tint = Color.White,
                )
            }
        }
        AudioOverflowMenu(activity)
    }
}

@Composable
private fun AudioOverflowMenu(activity: AudioPlayActivity) {
    val colors = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
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
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.audio_play_wake_lock),
                        color = colors.primaryText,
                    )
                },
                trailingIcon = {
                    AppMenuCheckbox(checked = AppConfig.audioPlayUseWakeLock)
                },
                onClick = { dismiss(); activity.toggleWakeLock() },
            )
            AudioMenuItem(R.string.bookmark_add) { dismiss(); activity.addBookmark() }
            AudioMenuItem(R.string.log) { dismiss(); activity.showAppLog() }
        }
    }
}

@Composable
private fun AudioMenuItem(textRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(textRes), color = AppTheme.colors.primaryText) },
        onClick = onClick,
    )
}

// ---- 封面/背景(Glide 栈经 AndroidView 桥接, 对齐 BookInfoScreen.BlurCoverBg 先例) ----

@Composable
private fun BlurBg(activity: AudioPlayActivity, modifier: Modifier) {
    AndroidView(
        factory = {
            AppCompatImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        modifier = modifier,
        update = { iv ->
            val url = activity.coverUrl
            if (url != null && iv.tag != url) {
                iv.tag = url
                BookCover.loadBlur(
                    ImageLoader.with(iv), url,
                    sourceOrigin = AudioPlay.bookSource?.bookSourceUrl,
                    seed = AudioPlay.book?.name,
                ).into(object : CustomViewTarget<ImageView, Drawable>(iv) {
                    override fun onResourceCleared(p0: Drawable?) {}
                    override fun onLoadFailed(p0: Drawable?) {}
                    override fun onResourceReady(p0: Drawable, p1: Transition<in Drawable>?) {
                        if (view.drawable != null) {
                            val transitionDrawable =
                                TransitionDrawable(arrayOf(view.drawable, p0))
                            transitionDrawable.isCrossFadeEnabled = true
                            view.setImageDrawable(transitionDrawable)
                            transitionDrawable.startTransition(300)
                        } else {
                            view.setImageDrawable(p0)
                        }
                        p0.toBitmapOrNull()?.let { activity.onBlurCoverLoaded(it) }
                    }
                })
            }
        },
    )
}

@Composable
private fun CoverImage(activity: AudioPlayActivity) {
    val colors = AppTheme.colors
    val coverDesc = stringResource(R.string.img_cover)
    AndroidView(
        factory = {
            AppCompatImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = coverDesc
            }
        },
        modifier = Modifier
            .padding(top = 16.dp)
            .size(200.dp)
            .clip(CircleShape)
            // 圆形描边用主题强调色(原 strokeColor 动态设置以响应主题切换)
            .border(2.dp, colors.accent, CircleShape)
            .clickable { activity.coverVisible = false },
        update = { iv ->
            val url = activity.coverUrl
            if (url != null && iv.tag != url) {
                iv.tag = url
                BookCover.load(
                    ImageLoader.with(iv), url,
                    sourceOrigin = AudioPlay.bookSource?.bookSourceUrl,
                    seed = AudioPlay.book?.name,
                ).placeholder(iv.drawable).into(iv)
            }
        },
    )
}

/** LrcView 为自绘歌词控件(滚动/渐变动画), 保留 View 实现经 AndroidView 桥接(登记欠账) */
@Composable
private fun LrcPanel(activity: AudioPlayActivity, modifier: Modifier) {
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

// ---- 定时/倍速回显标签(shape_fillet_btn_press: arco_fill_3 填充 8dp 圆角) ----

@Composable
private fun FilletLabel(text: String, iconRes: Int?, modifier: Modifier) {
    Row(
        modifier
            .background(colorResource(R.color.arco_fill_3), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

// ---- 进度条 ----

@Composable
private fun ProgressRow(activity: AudioPlayActivity) {
    val colors = AppTheme.colors
    // 拖动中的预览值(原 adjustProgress 标记: 拖动期间不回显事件进度)
    var dragValue by remember { mutableStateOf<Int?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (dragValue ?: activity.progressMs).toDurationTime(),
            color = Color.White,
            fontSize = 14.sp,
        )
        AudioSeekBar(
            value = dragValue ?: activity.progressMs,
            secondary = activity.bufferMs,
            max = activity.durationMs,
            activeColor = activity.lrcColors?.let { Color(it.first) } ?: colors.accent,
            bufferColor = activity.lrcColors?.let { Color(it.second) }
                ?: colors.accent.copy(alpha = 0.5f),
            onDrag = { dragValue = it },
            onDragFinished = {
                dragValue?.let { activity.viewModel.adjustProgress(it) }
                dragValue = null
            },
            modifier = Modifier
                .weight(1f)
                .height(25.dp),
        )
        Text(activity.durationMs.toDurationTime(), color = Color.White, fontSize = 14.sp)
    }
}

/** 自绘 MD2 SeekBar(同 AppSlider 形态)加缓冲层: 背景 md_dark_secondary、缓冲 secondary、已播 active */
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
            // 进度背景(原 progressBackgroundTint=md_dark_secondary)
            drawLine(Color(0xB3FFFFFF), Offset(startX, cy), Offset(endX, cy), trackH, StrokeCap.Round)
            if (bufX > startX) {
                drawLine(bufferColor, Offset(startX, cy), Offset(bufX, cy), trackH, StrokeCap.Round)
            }
            drawLine(activeColor, Offset(startX, cy), Offset(playX, cy), trackH, StrokeCap.Round)
            drawCircle(activeColor, thumbR, Offset(playX, cy))
        }
    }
}

// ---- 播放控制排 ----

@Composable
private fun PlayMenu(activity: AudioPlayActivity) {
    val colors = AppTheme.colors
    var showTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .alpha(0.7f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PlayMenuButton(R.drawable.ic_timer_black_24dp, stringResource(R.string.set_timer)) {
                showTimer = true
            }
            if (showTimer) {
                SliderPopupCard(
                    max = 180,
                    initial = AudioPlayService.timeMinute,
                    formatText = { activity.getString(R.string.timer_m, it) },
                    onProgressChanged = { activity.viewModel.setTimer(it) },
                    onDismiss = { showTimer = false },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            PlayMenuButton(R.drawable.ic_fast_forward, stringResource(R.string.skip_previous)) {
                showSpeed = true
            }
            if (showSpeed) {
                SliderPopupCard(
                    max = 30,
                    initial = (AudioPlayService.playSpeed * 10).toInt(),
                    formatText = { String.format(Locale.ROOT, "%.1fX", it / 10.0f) },
                    onProgressChanged = { activity.viewModel.adjustSpeed(it / 10.0f) },
                    onDismiss = { showSpeed = false },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        PlayMenuButton(
            R.drawable.ic_skip_previous,
            stringResource(R.string.skip_previous),
            enabled = activity.prevEnabled,
        ) { activity.viewModel.prev() }
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .padding(12.dp)
                    .size(56.dp)
                    .shadow(6.dp, CircleShape)
                    // clip: ripple 裁进圆形, 对齐原 FloatingActionButton 圆形按压反馈
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { activity.playButton() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (activity.isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
                    ),
                    contentDescription = stringResource(R.string.audio_play),
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (activity.loading) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        PlayMenuButton(
            R.drawable.ic_skip_next,
            stringResource(R.string.skip_next),
            enabled = activity.nextEnabled,
        ) { activity.viewModel.next() }
        Spacer(Modifier.weight(1f))
        PlayMenuButton(
            activity.playMode.iconRes,
            stringResource(R.string.skip_next),
            iconPadding = 8.dp,
        ) { activity.viewModel.changePlayMode() }
        Spacer(Modifier.weight(1f))
        PlayMenuButton(R.drawable.ic_chapter_list, stringResource(R.string.chapter_list)) {
            activity.openChapterList()
        }
    }
}

/** 46dp 圆钮: 按压态圆形 arco_fill_3 底(selector_circle_btn_bg), 图标白/禁用 25% 白(selector_white_icon) */
@Composable
private fun PlayMenuButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean = true,
    iconPadding: Dp = 4.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg = colorResource(R.color.arco_fill_3)
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (pressed) pressedBg else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color(0x3FFFFFFF),
            modifier = Modifier
                .fillMaxSize()
                .padding(iconPadding),
        )
    }
}

// ---- 定时/倍速滑条弹窗(原 SliderPopup: 全宽卡片, 锚点下沿上方 100dp) ----

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
                .background(colorResource(R.color.background_card), RoundedCornerShape(8.dp))
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
