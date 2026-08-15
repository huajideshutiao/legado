package io.legado.app.ui.book.audio

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.image_cover_default
import legado.shared.generated.resources.timer_m
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * 音频播放页全端共享平台 slot (对照原版 AudioPlayActivity 的平台渲染):
 *
 * - [SharedAudioCoverSlot]: 圆形封面 (原版 ivCover; 经 BookImageLoaders 加载, 未注册平台走占位;
 *   keep-previous 旧图保留 + 新图就绪后 Crossfade 300ms 交叉淡化, 对照原版 Glide placeholder(旧图)
 *   + TransitionDrawable.startTransition(300))
 * - [SharedAudioBlurBgSlot]: 模糊背景 (原版 iv_bg: loadBlur 整图 + 300ms TransitionDrawable 淡入;
 *   同上 keep-previous 旧图→新图交叉淡化; 均匀遮罩 #3A000000 由 AudioPlayScreenContent 统一叠加, 此处不重复)
 * - [AudioPlayTimerDialog] / [AudioPlaySpeedDialog]: 定时/倍速弹窗 (原版 Popup+SliderPopupCard 的
 *   AlertDialog 等价, 四端共用)
 * - [SharedAudioPlayScreenContent]: 四端 Provider 的统一 Content 组装 (对照原版 AudioPlayActivity);
 *   歌词用 [LrcViewShared] (复刻原版 LrcView), 取色用 [rememberLrcColors]
 *   (复刻原版 updateLrcColor → setColors + SeekBar tint)
 *
 * 视觉参数 (评论钮/图标/回显标签底色/透明度/内边距) 已统一为共享默认 (app 原版值),
 * 四端 provider 均为纯透传, 不再有平台覆盖; 控制钮按压底用 Compose 默认指示。
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
        onTapOutsideSidePanel = onTapOutsideSidePanel,
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

@Composable
private fun SharedAudioBlurBgSlot(coverUrl: String?, modifier: Modifier) {
    val loader = remember { BookImageLoaders.getOrNull() }
    // keep-previous: 不随 coverUrl 变化重置, 切歌瞬间旧背景保留直到新图就绪;
    // 新图加载失败时回落内置默认封面 (同前景 slot 语义, 2026-08 修)
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showBuiltIn by remember { mutableStateOf(false) }
    val currentUrl by rememberUpdatedState(coverUrl)
    LaunchedEffect(coverUrl, loader) {
        if (loader == null) {
            // 未注册 loader (ohos 无 Coil3): 直接内置默认封面
            bitmap = null
            showBuiltIn = true
            return@LaunchedEffect
        }
        // 加载链对照原版 BookCover.loadBlur (与前景同 seed, 失败回落同一张默认图)
        val bmp = loadAudioCover(loader, coverUrl)
        // 仅当本次请求仍是当前 URL 才落值 (防旧 URL 回调覆盖新背景)
        if (coverUrl == currentUrl) {
            if (bmp != null) {
                bitmap = bmp
                showBuiltIn = false
            } else {
                // 加载失败 (含默认图集为空): 回落内置默认封面 (原版 loadBlur 的内置回落)
                bitmap = null
                showBuiltIn = true
            }
        }
    }
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
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                    contentScale = ContentScale.Crop,
                )
                // 默认图集也为空: 内置默认封面 (对照原版 loadBlur 的内置回落, 同样模糊)
                showBuiltIn -> Image(
                    painter = painterResource(Res.drawable.image_cover_default),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(24.dp),
                    contentScale = ContentScale.Crop,
                )
                // 加载中占位: 页面默认背景色 (原版露 Activity 深色背景; 原实现闪主题蓝
                // 0x165DFF 半透明, 改默认背景色不再突兀)
                else -> Box(Modifier.fillMaxSize().background(AppTheme.colors.background))
            }
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
        title = { Text("定时") },
        text = {
            Column {
                Text(
                    stringResource(Res.string.timer_m, value),
                    color = AppTheme.colors.secondaryText,
                )
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
            TextButton(onClick = onDismiss) { Text("确认") }
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
                    "${(value / 10.0f).let { (it * 10).roundToInt() / 10f }}X",
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
            TextButton(onClick = onDismiss) { Text("确认") }
        },
    )
}
