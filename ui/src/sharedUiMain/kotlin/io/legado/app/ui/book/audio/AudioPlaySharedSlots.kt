package io.legado.app.ui.book.audio

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.legado.app.ui.compose.component.AppPopup
import io.legado.app.ui.compose.platform.rememberColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.image.BookImageLoader
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.model.AudioPlayShared
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.ui.bookshelf.defaultCoverFilePath
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.root.PlatformCapabilityProviders
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.image_cover_default
import legado.ui.generated.resources.timer_m
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 音频播放页全端共享平台 slot (对照原版 AudioPlayActivity 的平台渲染):
 *
 * - [SharedAudioCoverSlot]: 圆形封面 (原版 ivCover; 经 BookImageLoaders 加载, 未注册平台走占位;
 *   keep-previous 旧图保留 + 新图就绪后 Crossfade 300ms 交叉淡化, 对照原版 Glide placeholder(旧图)
 *   + TransitionDrawable.startTransition(300))
 * - [SharedAudioBlurBgSlot]: 模糊背景 (原版 iv_bg: loadBlur 整图 + 300ms TransitionDrawable 淡入;
 *   同上 keep-previous 旧图→新图交叉淡化; 均匀遮罩 #3A000000 由 AudioPlayScreenContent 统一叠加, 此处不重复)
 * - [AudioPlayTimerDialog] / [AudioPlaySpeedDialog]: 定时/倍速弹窗 (原版 PopupWindow+SliderPopup
 *   的细长条卡片等价, 四端共用)
 * - [SharedAudioPlayScreenContent]: 四端 Provider 的统一 Content 组装 (对照原版 AudioPlayActivity);
 *   歌词用 [LrcViewShared] (复刻原版 LrcView), 取色用 [rememberAudioCoverPalette]
 *   (复刻原版 updateLrcColor → setColors + SeekBar tint, 同时给出边缘光效配色)
 *
 * 视觉参数 (评论钮/图标/回显标签底色/透明度/内边距) 已统一为共享默认 (app 原版值),
 * 四端 provider 均为纯透传, 不再有平台覆盖; 控制钮按压底用 Compose 默认指示。
 */
@Composable
fun SharedAudioPlayScreenContent(
    state: AudioPlayUiState,
    onBack: () -> Unit,
    onOpenBookInfo: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenBookSourceEdit: (String) -> Unit,
    onOpenReview: () -> Unit,
    overflowActions: AudioPlayOverflowActions,
    onEvent: (AudioPlayUiEvent) -> Unit,
    titleBarTrailingSlot: @Composable RowScope.() -> Unit = {
        // 评论入口 (reviewUrl 非空才显示; hasReview 随书源切换刷新)。
        // 原 app/desktop/iOS/鸿蒙四端 provider 各写一份逐字相同, 收拢为共享默认。
        if (state.hasReview) {
            IconButton(onClick = onOpenReview) {
                Icon(
                    painter = rememberPainter("ic_edit"),
                    contentDescription = rememberString("review"),
                    tint = Color.White,
                )
            }
        }
    },
    sidePanelWidth: Dp = 0.dp,
    sidePanelVisible: Boolean = false,
    sidePanelKind: AudioPlaySidePanelKind? = null,
    sidePanelSlot: @Composable (AudioPlaySidePanelKind) -> Unit = {},
    onTapOutsideSidePanel: (() -> Unit)? = null,
    onOpenCover: () -> Unit = {},
) {
    // 封面取色 → 歌词/SeekBar 配色 + 边缘光效配色 (一次采样算齐, 对照原版 updateCover → updateLrcColor)
    val palette = rememberAudioCoverPalette(state.coverUrl)
    val fallbackGlowColors = rememberFallbackGlowColors()
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
        lrcActiveColor = palette?.lrcPrimary,
        lrcInactiveColor = palette?.lrcSecondary,
        onBack = onBack,
        onOpenBookInfo = onOpenBookInfo,
        onCoverClick = { onEvent(AudioPlayUiEvent.CoverClick) },
        onCoverLongClick = onOpenCover,
        onTogglePlay = { onEvent(AudioPlayUiEvent.TogglePlay) },
        onPrev = { onEvent(AudioPlayUiEvent.Prev) },
        onNext = { onEvent(AudioPlayUiEvent.Next) },
        onChangePlayMode = { onEvent(AudioPlayUiEvent.ChangePlayMode) },
        onOpenToc = onOpenToc,
        onSeek = { onEvent(AudioPlayUiEvent.Seek(it)) },
        onSetTimer = { onEvent(AudioPlayUiEvent.SetTimer(it)) },
        onSetSpeed = { onEvent(AudioPlayUiEvent.SetSpeed(it)) },
        overflowActions = overflowActions,
        onTapOutsideSidePanel = onTapOutsideSidePanel,
        coverSlot = { url, modifier -> SharedAudioCoverSlot(url, modifier) },
        blurBgSlot = { url, modifier -> SharedAudioBlurBgSlot(url, modifier) },
        edgeGlowSlot = if (overflowActions.edgeGlowEnabled) {
            { modifier ->
                AudioPlayEdgeGlow(
                    colors = palette?.glowColors ?: fallbackGlowColors,
                    isPlaying = state.isPlaying,
                    modifier = modifier,
                )
            }
        } else {
            null
        },
        lrcSlot = { modifier ->
            val lrcData = state.lrc
            LrcViewShared(
                lrcData = lrcData,
                // 当前行按自适应精准延时调度派生 (见 rememberLrcIndex)
                lrcProgress = rememberLrcIndex(
                    lrcData = lrcData,
                    isPlaying = state.isPlaying,
                    playSpeed = state.speed,
                ),
                primaryColor = palette?.lrcPrimary ?: Color(0xFFFFFFFF),
                secondaryColor = palette?.lrcSecondary ?: Color(0x80FFFFFF),
                onLineClick = { onEvent(AudioPlayUiEvent.LrcClick(it)) },
                modifier = modifier,
                isPlaying = state.isPlaying,
                playSpeed = state.speed,
                textGlowEnabled = overflowActions.glowEnabled,
            )
        },
        titleBarTrailingSlot = titleBarTrailingSlot,
        timerDialogSlot = { initial, onProgressChanged, onDismiss ->
            AudioPlayTimerDialog(initial, onProgressChanged, onDismiss)
        },
        speedDialogSlot = { initial, onProgressChanged, onDismiss ->
            AudioPlaySpeedDialog(initial, onProgressChanged, onDismiss)
        },
        sidePanelWidth = sidePanelWidth,
        sidePanelVisible = sidePanelVisible,
        sidePanelKind = sidePanelKind,
        sidePanelSlot = sidePanelSlot,
    )
}

// ---- 圆形封面 slot (原版 ivCover; 加载链对照原版 BookCover.load: 失败回落默认图集) ----

/**
 * 封面无图占位底色: 中性灰 (对齐项目内 BookInfoScreen 占位惯例)。
 * 原实现用纯主题蓝 Color(0xFF165DFF), 切歌瞬间会闪一片蓝, 改中性色弱化。
 */
private val CoverPlaceholderColor = Color(0xFF888888)

@Composable
private fun SharedAudioCoverSlot(coverUrl: String?, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    // keep-previous: 不随 coverUrl 变化重置, 切歌瞬间旧图保留直到新图就绪
    // (对照原版 Glide placeholder=当前旧图, 新图就绪后 Crossfade 交叉淡化替换);
    // 新图**加载失败**时不再保留旧图, 回落内置默认封面 (对齐原版 .error(newDefaultDrawable) 语义,
    // 2026-08 修: 防"有旧图时切到无封面书"永远显示上一本书封面)。
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showBuiltIn by remember { mutableStateOf(false) }
    // rememberUpdatedState: LaunchedEffect 内读到最新 coverUrl, 用于比对丢弃过期回调
    val currentUrl by rememberUpdatedState(coverUrl)
    LaunchedEffect(coverUrl, loader) {
        if (loader == null) {
            // 未注册 loader (ohos 无 Coil3): 直接内置默认封面, 不再停留灰色占位
            bitmap = null
            showBuiltIn = true
            return@LaunchedEffect
        }
        val bmp = loadAudioCover(loader, coverUrl)
        // 仅当本次请求仍是当前 URL 才落值 (防旧 URL 的异步回调覆盖新图;
        // 协程取消后一般到不了这里, 比对是双保险)
        if (coverUrl == currentUrl) {
            if (bmp != null) {
                bitmap = bmp
                showBuiltIn = false
            } else {
                // 加载失败 (含默认图集为空): 回落内置默认封面 (原版 newDefaultDrawable 的内置回落)
                bitmap = null
                showBuiltIn = true
            }
        }
    }
    Box(
        modifier.clip(androidx.compose.foundation.shape.CircleShape).background(CoverPlaceholderColor)
    ) {
        // 交叉淡化: targetState=当前显示的 bitmap; 切歌时旧图保持显示, 新图就绪后
        // 300ms 旧图淡出/新图淡入 (对照原版 TransitionDrawable.startTransition(300))
        Crossfade(
            targetState = bitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 300),
            label = "audioCoverCrossfade",
        ) { bmp ->
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentScale = ContentScale.Crop,
                )
                // 默认图集也为空: 内置默认封面 (对照原版 newDefaultDrawable 的内置回落)
                showBuiltIn -> Image(
                    painter = painterResource(Res.drawable.image_cover_default),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/**
 * 音频页封面加载链 (对照原版 BookCover.load / loadBlur):
 * useDefaultCover / 空 URL / 加载失败 → 默认图集按 seed=书名 挑一张 (与书架同源
 * defaultCoverFilePath); 返回 null 表示默认图集也为空, 调用方落内置默认封面图。
 * 前景/背景共用同一 seed, 保证回落挑到同一张 (对照原版 load/loadBlur 同 seed 注释)。
 */
private suspend fun loadAudioCover(loader: BookImageLoader, coverUrl: String?): ImageBitmap? {
    suspend fun loadDefault(): ImageBitmap? {
        val path = defaultCoverFilePath(AudioPlayShared.book?.name, CoverRatio.NOVEL)
        return path?.let { loader.loadImageOrNull(it, null) }
    }
    if (AppConfigProviders.get().useDefaultCover || coverUrl.isNullOrBlank()) return loadDefault()
    return loader.loadCoverOrNull(coverUrl, AudioPlayShared.book?.origin) ?: loadDefault()
}

// ---- 模糊背景 slot (原版 iv_bg: 整图模糊 + 300ms TransitionDrawable 淡入; 均匀遮罩由 shared 层叠) ----

/** 背景模糊半径 (dp): 绘制期模糊与被预模糊位图共用同一值 */
private val BG_BLUR_RADIUS = 24.dp

/** 背景浮动的放大基准: 留出位移余量, 移动时不会露出底色 */
private const val BG_DRIFT_SCALE_BASE = 1.10f

/** 背景浮动的最定位移 (dp) */
private const val BG_DRIFT_MAX_DP = 12f

/** 背景浮动的缩放变化幅度 (±) */
private const val BG_DRIFT_SCALE_RANGE = 0.02f

/** 背景浮动单程时长区间 (ms): 随机取值, 不跟着音乐变 */
private const val BG_DRIFT_MIN_MS = 6000
private const val BG_DRIFT_MAX_MS = 14000

/**
 * 背景模糊图的轻微浮动: 每段随机游走, 与音频能量无关 (不看音量、不跟着拍子)。
 *
 * 位移与缩放都在绘制阶段读取, 只失效重绘不重组; 图预先放大 [BG_DRIFT_SCALE_BASE]
 * 保证移动后边缘不露出底色。墨水屏由调用方整条修饰符不挂 (既不浮动也不放大)。
 */
@Composable
private fun Modifier.bgDrift(): Modifier {
    val density = LocalDensity.current
    val maxOffsetPx = with(density) { BG_DRIFT_MAX_DP.dp.toPx() }
    val driftX = remember { mutableFloatStateOf(0f) }
    val driftY = remember { mutableFloatStateOf(0f) }
    val driftScale = remember { mutableFloatStateOf(BG_DRIFT_SCALE_BASE) }
    LaunchedEffect(maxOffsetPx) {
        val random = Random.Default
        var fromX = 0f
        var fromY = 0f
        var fromScale = BG_DRIFT_SCALE_BASE
        while (isActive) {
            val toX = (random.nextFloat() * 2f - 1f) * maxOffsetPx
            val toY = (random.nextFloat() * 2f - 1f) * maxOffsetPx
            val toScale = BG_DRIFT_SCALE_BASE +
                (random.nextFloat() * 2f - 1f) * BG_DRIFT_SCALE_RANGE
            val durationMs = BG_DRIFT_MIN_MS +
                random.nextInt(BG_DRIFT_MAX_MS - BG_DRIFT_MIN_MS + 1)
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
            ) { progress, _ ->
                driftX.floatValue = fromX + (toX - fromX) * progress
                driftY.floatValue = fromY + (toY - fromY) * progress
                driftScale.floatValue = fromScale + (toScale - fromScale) * progress
            }
            fromX = toX
            fromY = toY
            fromScale = toScale
        }
    }
    return graphicsLayer {
        translationX = driftX.floatValue
        translationY = driftY.floatValue
        scaleX = driftScale.floatValue
        scaleY = driftScale.floatValue
    }
}

@Composable
private fun SharedAudioBlurBgSlot(coverUrl: String?, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    // 框架的模糊修饰符在 Android 12 以下不生效: 这些端改为加载后一次性算好模糊位图
    val drawBlur = PlatformCapabilityProviders.get().drawBlurSupported
    val blurRadiusPx = with(LocalDensity.current) { BG_BLUR_RADIUS.toPx().roundToInt() }
    // keep-previous: 不随 coverUrl 变化重置, 切歌瞬间旧背景保留直到新图就绪;
    // 新图加载失败时回落内置默认封面 (同前景 slot 语义, 2026-08 修)
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showBuiltIn by remember { mutableStateOf(false) }
    val currentUrl by rememberUpdatedState(coverUrl)
    LaunchedEffect(coverUrl, loader, drawBlur, blurRadiusPx) {
        if (loader == null) {
            // 未注册 loader (ohos 无 Coil3): 直接内置默认封面
            bitmap = null
            showBuiltIn = true
            return@LaunchedEffect
        }
        // 加载链对照原版 BookCover.loadBlur (与前景同 seed, 失败回落同一张默认图)
        val bmp = loadAudioCover(loader, coverUrl)
        val prepared = if (drawBlur || bmp == null) bmp
        else withContext(Dispatchers.Default) {
            loader.blurForBackground(bmp, blurRadiusPx)
        } ?: bmp
        // 仅当本次请求仍是当前 URL 才落值 (防旧 URL 回调覆盖新背景)
        if (coverUrl == currentUrl) {
            if (prepared != null) {
                bitmap = prepared
                showBuiltIn = false
            } else {
                // 加载失败 (含默认图集为空): 回落内置默认封面 (原版 loadBlur 的内置回落)
                bitmap = null
                showBuiltIn = true
            }
        }
    }
    val eInk = LocalEInk.current
    // 墨水屏不浮动 (连放大基准也不挂, 不白裁一圈)
    val driftModifier = if (eInk) Modifier else Modifier.bgDrift()
    // 不生效的端不再叠一层空操作的绘制期模糊
    val blurModifier = if (drawBlur) Modifier.blur(BG_BLUR_RADIUS) else Modifier
    Box(modifier) {
        // 旧图→新图 300ms 交叉淡化 (对照原版 TransitionDrawable.startTransition(300);
        // 切歌瞬间旧背景保留, 新图就绪后旧图淡出/新图淡入)
        Crossfade(
            targetState = bitmap,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 300),
            label = "audioBlurBgCrossfade",
        ) { bmp ->
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().then(driftModifier).then(blurModifier),
                    contentScale = ContentScale.Crop,
                )
                // 默认图集也为空: 内置默认封面 (对照原版 loadBlur 的内置回落, 同样模糊)
                showBuiltIn -> Image(
                    bitmap = rememberBuiltInBlurBg(loader, drawBlur, blurRadiusPx),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().then(driftModifier).then(blurModifier),
                    contentScale = ContentScale.Crop,
                )
                // 加载中占位: 页面默认背景色 (原版露 Activity 深色背景; 原实现闪主题蓝
                // 0x165DFF 半透明, 改默认背景色不再突兀)
                else -> Box(Modifier.fillMaxSize().background(AppTheme.colors.background))
            }
        }
    }
}

/** 内置默认封面按同一规则处理: 绘制期模糊生效就用原图, 否则先算好模糊位图。 */
@Composable
private fun rememberBuiltInBlurBg(
    loader: BookImageLoader?,
    drawBlur: Boolean,
    blurRadiusPx: Int,
): ImageBitmap {
    val raw = imageResource(Res.drawable.image_cover_default)
    return remember(raw, loader, drawBlur, blurRadiusPx) {
        if (drawBlur || loader == null) raw else loader.blurForBackground(raw, blurRadiusPx) ?: raw
    }
}

// ---- 定时 / 倍速: 原版 SliderPopup (细长条卡片, 四端共用) ----

/**
 * 原版 SliderPopup 的等价实现 (app 端 ui/book/audio/SliderPopup.kt):
 * PopupWindow(MATCH_PARENT × WRAP_CONTENT) + shape_card_view(8dp 圆角, background_card 底色)
 * + 8dp 内边距, 竖排「值文本 + SeekBar」; 拖动即时生效, 点外部关闭, 没有确认按钮。
 *
 * 定位对齐原版 showAsDropDown(anchor, 0, -100dp, Gravity.TOP): 卡片贴在触发按钮上方,
 * 宽度撑满窗口 (左右各留 [SliderPopupSideMargin])。
 */
@Composable
private fun AudioPlaySliderPopup(
    value: Int,
    min: Int,
    max: Int,
    text: String,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val marginPx = with(density) { SliderPopupSideMargin.roundToPx() }
    val gapPx = with(density) { SliderPopupAnchorGap.roundToPx() }
    val positionProvider = remember(marginPx, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = marginPx,
                // 底边贴在触发按钮上方 (原版用 -100dp 抬到按钮上方, 这里贴着触发按钮更稳)
                y = anchorBounds.top - popupContentSize.height - gapPx,
            )
        }
    }
    AppPopup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Column(
            Modifier
                .width(windowWidth - SliderPopupSideMargin * 2)
                .clip(RoundedCornerShape(SliderPopupCorner))
                .background(rememberColor("background_card"))
                .padding(SliderPopupPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = text, color = AppTheme.colors.secondaryText)
            AppSlider(
                value = value,
                min = min,
                max = max,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val SliderPopupSideMargin = 8.dp
private val SliderPopupAnchorGap = 8.dp
private val SliderPopupPadding = 8.dp
private val SliderPopupCorner = 8.dp

/** 定时: 原版 max=180 分钟, 值文本走 `timer_m`。 */
@Composable
fun AudioPlayTimerDialog(
    initial: Int,
    onProgressChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf(initial.coerceIn(0, 180)) }
    AudioPlaySliderPopup(
        value = value,
        min = 0,
        max = 180,
        text = stringResource(Res.string.timer_m, value),
        onValueChange = {
            value = it
            onProgressChanged(it)
        },
        onDismiss = onDismiss,
    )
}

/** 倍速: 原版 max=30 即 3.0X; 下限取 1 (0.1X) —— 0 倍速对引擎是非法值。 */
@Composable
fun AudioPlaySpeedDialog(
    initial: Float,
    onProgressChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableIntStateOf((initial * 10).roundToInt().coerceIn(1, 30)) }
    AudioPlaySliderPopup(
        value = value,
        min = 1,
        max = 30,
        text = "${value / 10}.${value % 10}X",
        onValueChange = {
            value = it
            onProgressChanged(it / 10f)
        },
        onDismiss = onDismiss,
    )
}
