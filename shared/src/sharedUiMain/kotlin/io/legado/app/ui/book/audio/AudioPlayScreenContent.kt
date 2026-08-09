package io.legado.app.ui.book.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.model.AudioPlayShared
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppMenuCheckbox
import io.legado.app.ui.compose.platform.handleMediaKeys
import io.legado.app.ui.compose.platform.rememberColor
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.preview.LegadoThemePreview
import io.legado.app.utils.format
import io.legado.app.utils.toDurationTime
import kotlinx.coroutines.delay
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.audio_play
import legado.shared.generated.resources.audio_play_wake_lock
import legado.shared.generated.resources.back
import legado.shared.generated.resources.change_origin
import legado.shared.generated.resources.chapter_list
import legado.shared.generated.resources.ic_arrow_back
import legado.shared.generated.resources.ic_exchange
import legado.shared.generated.resources.ic_more_vert
import legado.shared.generated.resources.more_menu
import legado.shared.generated.resources.next_chapter
import legado.shared.generated.resources.play_mode
import legado.shared.generated.resources.previous_chapter
import legado.shared.generated.resources.set_timer
import legado.shared.generated.resources.speed
import legado.shared.generated.resources.stop
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 宽屏右侧面板内容类型 (评论/目录共用同一面板槽位, 互斥显示)。
 * 窄屏 (窗口宽 < [DesignTokens.wideScreenMinWidth]) 时面板不启用, 入口保持原版交互 (弹窗/全屏页)。
 */
enum class AudioPlaySidePanelKind { TOC, REVIEW }

/**
 * 音频播放页主体内容 (模糊封面背景 + 遮罩 + 标题栏 + 副标题 + 封面/歌词区 + 进度条 + 播放控制排)。
 *
 * 结构对照 app 端 [io.legado.app.ui.book.audio.AudioPlayScreen], 通过 slot 注入平台特殊部分:
 * - [coverSlot]: 封面图加载 (app: Glide+AndroidView; desktop: OkHttp+ImageIO)
 * - [blurBgSlot]: 模糊封面背景加载槽 (app: blurConfig+TransitionDrawable 淡入+onBlurCoverLoaded 回调; desktop: null 复用 coverSlot)
 * - [lrcSlot]: 歌词渲染 (app: 自绘 LrcView; desktop: LazyColumn 简化版)
 * - [titleBarTrailingSlot]: 标题栏尾部 (app: review+overflow; desktop: 无)
 * - [timerDialogSlot]/[speedDialogSlot]: 定时/倍速弹窗 (app: Popup; desktop: AlertDialog)
 *
 * 默认参数对齐 desktop 视觉; app 端采用时通过参数覆盖差异 (titleBarHorizontalPadding/playMenuAlpha 等)。
 *
 * @param title 标题 (书名)
 * @param subTitle 副标题 (章节名)
 * @param coverUrl 封面 URL (null/空 → 不加载)
 * @param coverVisible 封面圆形图显隐 (点击封面切到 false, 由调用方管理状态)
 * @param timerMinute 定时分钟数 (>0 时左上角回显标签)
 * @param speed 倍速 (!=1f 时右上角回显标签)
 * @param progressMs 当前播放位置 ms
 * @param durationMs 总时长 ms
 * @param bufferMs 缓冲进度 ms
 * @param isPlaying 播放中 (回显播放/暂停钮)
 * @param loading 加载中 (回显 CircularProgressIndicator)
 * @param playMode 播放模式 (回显播放模式钮图标)
 * @param prevEnabled 上一章钮可用
 * @param nextEnabled 下一章钮可用
 * @param accentColor 强调色 (圆形封面描边 + SeekBar 已播层 + 加载指示器)
 * @param lrcActiveColor SeekBar 已播层颜色 (null=用 accentColor; app 端从 lrcColors 取)
 * @param lrcInactiveColor SeekBar 缓冲层颜色 (null=用 accentColor.copy(0.5); app 端从 lrcColors 取)
 * @param onBack 返回
 * @param onOpenChangeSource 换源
 * @param onCoverClick 封面点击 (隐藏封面)
 * @param onTogglePlay 播放/暂停切换
 * @param onPrev 上一章
 * @param onNext 下一章
 * @param onChangePlayMode 切换播放模式
 * @param onOpenToc 打开目录
 * @param onSeek 进度跳转 (ms)
 * @param onSetTimer 设定定时 (分钟)
 * @param onSetSpeed 设定倍速
 * @param onStop 停止回调 (null=不显示停止钮; desktop 端独有)
 * @param coverSlot 封面加载槽 (url, modifier) → 平台图片加载 Composable
 * @param blurBgSlot 模糊封面背景加载槽 (null=复用 coverSlot; app 端走 blurConfig + 淡入 + 回调)
 * @param lrcSlot 歌词渲染槽 (modifier) → 平台歌词 Composable
 * @param titleBarTrailingSlot 标题栏尾部槽 (app: review+overflow; desktop: 空)
 * @param timerDialogSlot 定时弹窗槽 (initial, onProgressChanged, onDismiss)
 * @param speedDialogSlot 倍速弹窗槽 (initial, onProgressChanged, onDismiss)
 * @param timerIconKey 定时钮图标 key (desktop: ic_time_add_24dp; app: ic_timer_black_24dp)
 * @param speedIconKey 倍速钮图标 key (desktop: ic_speed; app: ic_fast_forward)
 * @param chapterListIconKey 目录钮图标 key (desktop: ic_toc; app: ic_chapter_list)
 * @param filletLabelColor 回显标签底色 (desktop: 0x66000000; app: arco_fill_3)
 * @param playMenuButtonPressedBgEnabled 控制钮是否启用按压态背景 (app: true; desktop: false)
 * @param playMenuAlpha 控制排整体透明度 (app: 0.7f; desktop: 1f)
 * @param titleBarHorizontalPadding 标题栏横向内边距 (app: 0dp; desktop: 8dp)
 * @param playModeIconPadding 播放模式钮图标内边距 (app: 8dp; desktop: 4dp)
 */
@Composable
fun AudioPlayScreenContent(
    title: String,
    subTitle: String,
    coverUrl: String?,
    coverVisible: Boolean,
    timerMinute: Int,
    speed: Float,
    progressMs: Int,
    durationMs: Int,
    bufferMs: Int,
    isPlaying: Boolean,
    loading: Boolean,
    playMode: AudioPlayShared.PlayMode,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    accentColor: Color,
    lrcActiveColor: Color? = null,
    lrcInactiveColor: Color? = null,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    onCoverClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangePlayMode: () -> Unit,
    onOpenToc: () -> Unit,
    onSeek: (Int) -> Unit,
    onSetTimer: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onStop: (() -> Unit)? = null,
    overflowActions: AudioPlayOverflowActions? = null,
    coverSlot: @Composable (String?, Modifier) -> Unit,
    blurBgSlot: (@Composable (String?, Modifier) -> Unit)? = null,
    lrcSlot: @Composable (Modifier) -> Unit,
    titleBarTrailingSlot: @Composable RowScope.() -> Unit = {},
    timerDialogSlot: @Composable (initial: Int, onProgressChanged: (Int) -> Unit, onDismiss: () -> Unit) -> Unit,
    speedDialogSlot: @Composable (initial: Float, onProgressChanged: (Float) -> Unit, onDismiss: () -> Unit) -> Unit,
    timerIconKey: String = "ic_time_add_24dp",
    speedIconKey: String = "ic_speed",
    chapterListIconKey: String = "ic_toc",
    filletLabelColor: Color = Color(0x66000000),
    playMenuButtonPressedBgEnabled: Boolean = false,
    playMenuAlpha: Float = 1f,
    titleBarHorizontalPadding: Dp = 8.dp,
    playModeIconPadding: Dp = 4.dp,
    /** 宽屏右侧面板宽度 (0=不启用; 由路由层按窗口宽计算)。 */
    sidePanelWidth: Dp = 0.dp,
    /** 面板当前是否显示 (驱动滑入/滑出与左侧挤压动画)。 */
    sidePanelVisible: Boolean = false,
    /** 面板当前内容类型 (null=无; 切换时直接替换内容)。 */
    sidePanelKind: AudioPlaySidePanelKind? = null,
    /** 面板内容渲染 (评论/目录各自的内容组件)。 */
    sidePanelSlot: @Composable (AudioPlaySidePanelKind) -> Unit = {},
    /** 点击左侧内容区空白处时回调 (右侧面板打开时点击外部关闭, 对话框语义; null=不监听)。 */
    onTapOutsideSidePanel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val keyScope = rememberCoroutineScope()
    // 键盘事件焦点: onPreviewKeyEvent 需节点持有焦点才触发, 进入即取焦点
    // (对照 desktop VideoPlayerScreen 焦点接线)。
    // requestFocus 一次性请求在组合初期可能失败 (焦点系统未就绪/被导航抢占),
    // 这里以 onFocusChanged 标志 + 循环重试兜底 (同 WindowTitleBar DWM 轮询先例),
    // 避免"必须点一下界面才有键盘交互"。
    val keyFocusRequester = remember { FocusRequester() }
    var keyFocusHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (keyFocusHeld) return@LaunchedEffect
            runCatching { keyFocusRequester.requestFocus() }
            delay(50)
        }
    }
    // 宽屏面板动画: 滑入位移 + 左侧挤压同步 (E-Ink 一律 snap 无动画, 项目惯例)
    val eInk = LocalEInk.current
    val panelSlideAnim: FiniteAnimationSpec<IntOffset> =
        if (eInk) snap() else tween(durationMillis = 300, easing = FastOutSlowInEasing)
    val panelWidthAnim: FiniteAnimationSpec<Dp> =
        if (eInk) snap() else tween(durationMillis = 300, easing = FastOutSlowInEasing)
    val animatedPanelWidth by animateDpAsState(
        targetValue = if (sidePanelVisible) sidePanelWidth else 0.dp,
        animationSpec = panelWidthAnim,
        label = "audioSidePanelWidth",
    )
    Box(
        modifier
            .fillMaxSize()
            // 键盘快捷键: 消费共享 handleMediaKeys
            // (Space=播放/暂停, ←/→=进度∓10s, ↑/↓=上/下一章, 长按→=2x 倍速松手恢复,
            //  Esc/Backspace=返回)
            .handleMediaKeys(
                onTogglePlayPause = onTogglePlay,
                onSeekDelta = { delta ->
                    onSeek((progressMs + delta.toInt()).coerceIn(0, durationMs))
                },
                onPrev = { if (prevEnabled) onPrev() },
                onNext = { if (nextEnabled) onNext() },
                // 长按右方向键倍速 (2.0f), 松手恢复 (1.0f), 走共享倍速设置
                onSpeedChange = onSetSpeed,
                onBack = onBack,
                scope = keyScope,
            )
            .focusRequester(keyFocusRequester)
            .focusable()
            .onFocusChanged { keyFocusHeld = it.isFocused },
    ) {
        // 模糊封面背景 (平台 blurBgSlot 加载; null 时复用 coverSlot)
        val bgSlot = blurBgSlot ?: coverSlot
        bgSlot(coverUrl, Modifier.matchParentSize())
        // 半透明遮罩 (对照 app 端 0x3A000000)
        Box(Modifier.matchParentSize().background(Color(0x3A000000)))
        // 左区: 右侧随面板宽度动画挤压 (面板滑入时内容同步变窄); 面板打开时点击
        // 左区空白 (未被子级消费的点击) 关闭面板, 对话框语义
        Column(
            Modifier
                .fillMaxSize()
                .padding(end = animatedPanelWidth)
                .tapOnUnconsumed { onTapOutsideSidePanel?.invoke() },
        ) {
            AudioTitleBar(
                title = title,
                onBack = onBack,
                onOpenChangeSource = onOpenChangeSource,
                overflowActions = overflowActions,
                trailingSlot = titleBarTrailingSlot,
                horizontalPadding = titleBarHorizontalPadding,
            )
            // 歌名 (章节名) 不再单独置顶, 随封面一起走 (见下方封面区 AudioSongTitle)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // 宽屏 (弹性区宽 ≥ DesignTokens.wideScreenMinWidth, 官方 Compact/Medium
                // 分界, 手机横屏起): 封面与歌词并排 —— 封面区占容器宽 35% (上限
                // DesignTokens.sidePanelMaxWidth, 与右侧评论/目录面板同款), 封面在区内
                // 居中不贴左, 尺寸上限 300dp (用户拍板 2026-08); 窄屏保持竖排 (封面在上、
                // 歌词在下, 歌词保留最小可视高, 封面保持原版 200dp)。
                // 封面尺寸受封面区宽高双重钳制 (桌面窗口可自由缩放, 防向下溢出压住
                // 进度条/操作区); 竖排时封面还须给歌词让出 COVER_MIN_LRC_HEIGHT。
                val landscape = maxWidth >= DesignTokens.wideScreenMinWidth
                val coverTopPad = 16.dp
                // 封面区宽度 (0.35 比例 + 上限 600dp, 用户拍板) — 区内居中显示封面
                val coverAreaWidth =
                    (maxWidth * 0.35f).coerceAtMost(DesignTokens.audioCoverAreaMaxWidth)
                val coverSize = if (coverVisible) {
                    if (landscape) {
                        minOf(
                            COVER_MAX_SIZE_LANDSCAPE,
                            (maxHeight - coverTopPad * 2 - COVER_TITLE_SPACE)
                                .coerceAtLeast(0.dp),
                            (coverAreaWidth - coverTopPad * 2).coerceAtLeast(0.dp),
                        )
                    } else {
                        (maxHeight - coverTopPad - COVER_TITLE_SPACE - COVER_MIN_LRC_HEIGHT)
                            .coerceIn(0.dp, COVER_MAX_SIZE)
                    }
                } else {
                    0.dp
                }
                if (landscape) {
                    // 并排: 封面区 (占宽比例, 区内水平+垂直居中) + 歌词 (weight 占剩余宽, 高度占满)
                    Row(
                        Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (coverVisible && coverSize > 0.dp) {
                            Box(
                                Modifier
                                    .width(coverAreaWidth)
                                    .fillMaxHeight(),
                                // 封面右对齐 (用户拍板): 紧贴歌词区, 消除封面与歌词之间的空白;
                                // 左侧留白由封面区承载
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // 歌名跟随封面 (居中于封面宽, 隐藏封面时一并隐藏)
                                    if (subTitle.isNotBlank()) {
                                        AudioSongTitle(
                                            text = subTitle,
                                            modifier = Modifier.width(coverSize),
                                        )
                                        Spacer(Modifier.height(16.dp))
                                    }
                                    CoverImage(
                                        coverUrl = coverUrl,
                                        accentColor = accentColor,
                                        onClick = onCoverClick,
                                        coverSlot = coverSlot,
                                        size = coverSize,
                                        modifier = Modifier.padding(coverTopPad),
                                    )
                                }
                            }
                        }
                        lrcSlot(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp),
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (coverVisible && coverSize > 0.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // 歌名跟随封面 (居中于封面宽, 隐藏封面时一并隐藏)
                                if (subTitle.isNotBlank()) {
                                    AudioSongTitle(
                                        text = subTitle,
                                        modifier = Modifier.width(coverSize),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                                CoverImage(
                                    coverUrl = coverUrl,
                                    accentColor = accentColor,
                                    onClick = onCoverClick,
                                    coverSlot = coverSlot,
                                    size = coverSize,
                                    modifier = Modifier.padding(top = coverTopPad),
                                )
                            }
                        }
                        lrcSlot(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 16.dp),
                        )
                    }
                }
                // 定时回显标签 (左上)
                if (timerMinute > 0) {
                    FilletLabel(
                        text = "${timerMinute}m",
                        iconKey = timerIconKey,
                        bgColor = filletLabelColor,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                    )
                }
                // 倍速回显标签 (右上)
                if (speed != 1f) {
                    FilletLabel(
                        text = "%.1fX".format(speed),
                        iconKey = null,
                        bgColor = filletLabelColor,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    )
                }
            }
            ProgressRow(
                progressMs = progressMs,
                bufferMs = bufferMs,
                durationMs = durationMs,
                accentColor = accentColor,
                lrcActiveColor = lrcActiveColor,
                lrcInactiveColor = lrcInactiveColor,
                onSeek = onSeek,
            )
            PlayMenu(
                isPlaying = isPlaying,
                loading = loading,
                playMode = playMode,
                timerMinute = timerMinute,
                speed = speed,
                prevEnabled = prevEnabled,
                nextEnabled = nextEnabled,
                onStop = onStop,
                accentColor = accentColor,
                timerIconKey = timerIconKey,
                speedIconKey = speedIconKey,
                chapterListIconKey = chapterListIconKey,
                playMenuButtonPressedBgEnabled = playMenuButtonPressedBgEnabled,
                playMenuAlpha = playMenuAlpha,
                playModeIconPadding = playModeIconPadding,
                onTogglePlay = onTogglePlay,
                onPrev = onPrev,
                onNext = onNext,
                onChangePlayMode = onChangePlayMode,
                onOpenToc = onOpenToc,
                onSetTimer = onSetTimer,
                onSetSpeed = onSetSpeed,
                timerDialogSlot = timerDialogSlot,
                speedDialogSlot = speedDialogSlot,
            )
        }
        // 宽屏右侧面板 (仅启用时组合; 滑入/滑出动画, 内容由路由层注入)
        if (sidePanelWidth > 0.dp) {
            // exit 动画期间保留旧内容 (否则滑出时面板内容先消失);
            // sidePanelKind 为空时沿用上次内容 (面板已开时切 kind 直接替换)
            var lastKind by remember { mutableStateOf<AudioPlaySidePanelKind?>(null) }
            lastKind = sidePanelKind ?: lastKind
            AnimatedVisibility(
                visible = sidePanelVisible,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                enter = slideInHorizontally(panelSlideAnim) { it },
                exit = slideOutHorizontally(panelSlideAnim) { it },
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(sidePanelWidth)
                        // 左缘分隔线 (与左区内容区分)
                        .drawBehind {
                            val stroke = 1.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = 0.1f),
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = stroke,
                            )
                        }
                ) {
                    lastKind?.let { sidePanelSlot(it) }
                }
            }
        }
    }
}

// ---- 标题栏 (返回 + 标题 + 换源 + 尾部槽) ----

@Composable
private fun AudioTitleBar(
    title: String,
    onBack: () -> Unit,
    onOpenChangeSource: () -> Unit,
    overflowActions: AudioPlayOverflowActions?,
    trailingSlot: @Composable RowScope.() -> Unit,
    horizontalPadding: Dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_back),
                contentDescription = stringResource(Res.string.back),
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
                painter = painterResource(Res.drawable.ic_exchange),
                contentDescription = stringResource(Res.string.change_origin),
                tint = Color.White,
            )
        }
        // 平台尾部槽 (review 钮等)
        trailingSlot()
        // 共享溢出菜单 (登录/复制URL/源变量/书变量/编辑书源/书签/日志)
        if (overflowActions != null) {
            AudioPlayOverflowMenu(actions = overflowActions)
        }
    }
}

// ---- 溢出菜单 (下沉自 app 端 AudioOverflowMenu) ----

@Composable
private fun AudioPlayOverflowMenu(actions: AudioPlayOverflowActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.more_menu),
                tint = Color.White,
            )
        }
        AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val dismiss = { expanded = false }
            if (actions.hasLogin) {
                AudioOverflowItem("login") {
                    dismiss()
                    actions.onLogin()
                }
            }
            AudioOverflowItem("copy_play_url") {
                dismiss()
                actions.onCopyAudioUrl()
            }
            AudioOverflowItem("set_source_variable") {
                dismiss()
                actions.onSetSourceVariable()
            }
            AudioOverflowItem("set_book_variable") {
                dismiss()
                actions.onSetBookVariable()
            }
            AudioOverflowItem("edit_book_source") {
                dismiss()
                actions.onEditBookSource()
            }
            // 唤醒锁 (Android 专属, onToggleWakeLock != null 时显示, 对照 app 端 audio_play_wake_lock)
            if (actions.onToggleWakeLock != null) {
                DropdownMenuItem(onClick = {
                    dismiss()
                    actions.onToggleWakeLock.invoke()
                }) {
                    Text(
                        stringResource(Res.string.audio_play_wake_lock),
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    )
                    AppMenuCheckbox(
                        checked = runCatching {
                            AppConfigProviders.get().audioPlayUseWakeLock
                        }.getOrDefault(false),
                    )
                }
            }
            AudioOverflowItem("bookmark_add") {
                dismiss()
                actions.onAddBookmark()
            }
            AudioOverflowItem("log") {
                dismiss()
                actions.onShowAppLog()
            }
        }
    }
}

@Composable
private fun AudioOverflowItem(textKey: String, onClick: () -> Unit) {
    DropdownMenuItem(onClick = onClick) {
        Text(
            rememberString(textKey),
            color = AppTheme.colors.primaryText,
        )
    }
}

// ---- 圆形封面 (默认 200dp 圆形 + accent 描边, 平台 coverSlot 加载; 尺寸/内边距由调用方按布局模式决定) ----

/** 竖排模式歌词区最小可视高度 (封面压缩时保留的歌词空间)。 */
private val COVER_MIN_LRC_HEIGHT = 120.dp

/** 封面尺寸上限: 竖排 (原版语义, 200dp); 并排宽屏放大到 300dp (用户拍板 2026-08)。 */
private val COVER_MAX_SIZE = 200.dp
private val COVER_MAX_SIZE_LANDSCAPE = 300.dp

/** 封面上方歌名区总高 (单行文本 ≈ 24dp + 16dp spacer), 计算封面尺寸时预留, 避免顶掉歌词区。 */
private val COVER_TITLE_SPACE = 40.dp

/** 歌名: 随封面一起走 (居中于封面宽, 单行省略), 封面隐藏时一并隐藏。 */
@Composable
private fun AudioSongTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun CoverImage(
    coverUrl: String?,
    accentColor: Color,
    onClick: () -> Unit,
    coverSlot: @Composable (String?, Modifier) -> Unit,
    size: Dp = COVER_MAX_SIZE,
    modifier: Modifier = Modifier,
) {
    coverSlot(
        coverUrl,
        modifier
            .size(size)
            .clip(CircleShape)
            .border(DesignTokens.strokeMedium, accentColor, CircleShape)
            .clickable(onClick = onClick),
    )
}

// ---- 定时/倍速回显标签 (圆角填充底 + 可选图标 + 白字) ----

@Composable
private fun FilletLabel(
    text: String,
    iconKey: String?,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(bgColor, DesignTokens.shapeDefault)
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

// ---- 进度条 (时间 + 自绘 SeekBar + 时间) ----

@Composable
private fun ProgressRow(
    progressMs: Int,
    bufferMs: Int,
    durationMs: Int,
    accentColor: Color,
    lrcActiveColor: Color?,
    lrcInactiveColor: Color?,
    onSeek: (Int) -> Unit,
) {
    // 拖动中的预览值 (拖动期间不回显事件进度)
    var dragValue by remember { mutableStateOf<Int?>(null) }
    // 时长未知 (流式资源 READY 前/无时长): 显示 --:-- 并禁用拖动, 避免“进度超过时长”的观感
    val durationKnown = durationMs > 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (dragValue ?: if (durationKnown) progressMs.coerceIn(0, durationMs) else progressMs)
                .toDurationTime(),
            color = Color.White,
            fontSize = 14.sp,
        )
        AudioSeekBar(
            value = dragValue ?: progressMs,
            secondary = bufferMs,
            max = durationMs,
            activeColor = lrcActiveColor ?: accentColor,
            bufferColor = lrcInactiveColor ?: accentColor.copy(alpha = 0.5f),
            enabled = durationKnown,
            onDrag = { dragValue = it },
            onDragFinished = {
                dragValue?.let { onSeek(it) }
                dragValue = null
            },
            modifier = Modifier
                .weight(1f)
                .height(25.dp),
        )
        Text(
            if (durationKnown) durationMs.toDurationTime() else "--:--",
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}

/** 自绘 SeekBar: 背景 + 缓冲层 + 已播层 + thumb, 支持点击/拖动 seek */
@Composable
private fun AudioSeekBar(
    value: Int,
    secondary: Int,
    max: Int,
    activeColor: Color,
    bufferColor: Color,
    enabled: Boolean = true,
    onDrag: (Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = max.coerceAtLeast(1)
    // 显示钳制: 兜住 READY 前 progressMs 瞬时超限, 避免文字/轨道溢出
    val clampedValue = value.coerceIn(0, range)
    val clampedSecondary = secondary.coerceIn(0, range)

    fun fractionToValue(fraction: Float): Int =
        (fraction * range).toInt().coerceIn(0, range)

    Box(
        modifier.then(
            if (enabled) {
                Modifier
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
                    }
            } else {
                Modifier
            }
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val thumbR = 8.dp.toPx()
            val trackH = 2.dp.toPx()
            val cy = size.height / 2
            val startX = thumbR
            val endX = size.width - thumbR
            val playFrac = (clampedValue.toFloat() / range).coerceIn(0f, 1f)
            val bufFrac = (clampedSecondary.toFloat() / range).coerceIn(0f, 1f)
            val playX = startX + (endX - startX) * playFrac
            val bufX = startX + (endX - startX) * bufFrac
            // 禁用态 (时长未知): 半透明呈现, 与可拖动态区分
            val layerColor = { color: Color -> if (enabled) color else color.copy(alpha = 0.35f) }
            // 进度背景
            drawLine(
                layerColor(Color(0xB3FFFFFF)),
                Offset(startX, cy),
                Offset(endX, cy),
                trackH,
                StrokeCap.Round
            )
            if (bufX > startX) {
                drawLine(
                    layerColor(bufferColor),
                    Offset(startX, cy),
                    Offset(bufX, cy),
                    trackH,
                    StrokeCap.Round,
                )
            }
            drawLine(
                layerColor(activeColor),
                Offset(startX, cy),
                Offset(playX, cy),
                trackH,
                StrokeCap.Round
            )
            drawCircle(layerColor(activeColor), thumbR, Offset(playX, cy))
        }
    }
}

// ---- 播放控制排 (定时 + 倍速 + 上一章 + 播放/暂停 + 下一章 + [停止] + 播放模式 + 目录) ----

@Composable
private fun PlayMenu(
    isPlaying: Boolean,
    loading: Boolean,
    playMode: AudioPlayShared.PlayMode,
    timerMinute: Int,
    speed: Float,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onStop: (() -> Unit)?,
    accentColor: Color,
    timerIconKey: String,
    speedIconKey: String,
    chapterListIconKey: String,
    playMenuButtonPressedBgEnabled: Boolean,
    playMenuAlpha: Float,
    playModeIconPadding: Dp,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangePlayMode: () -> Unit,
    onOpenToc: () -> Unit,
    onSetTimer: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    timerDialogSlot: @Composable (initial: Int, onProgressChanged: (Int) -> Unit, onDismiss: () -> Unit) -> Unit,
    speedDialogSlot: @Composable (initial: Float, onProgressChanged: (Float) -> Unit, onDismiss: () -> Unit) -> Unit,
) {
    var showTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .alpha(playMenuAlpha)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 定时
        Box {
            PlayMenuButton(
                iconKey = timerIconKey,
                contentDescription = stringResource(Res.string.set_timer),
                pressedBgEnabled = playMenuButtonPressedBgEnabled,
            ) { showTimer = true }
            if (showTimer) {
                timerDialogSlot(timerMinute, onSetTimer) { showTimer = false }
            }
        }
        Spacer(Modifier.weight(1f))
        // 倍速
        Box {
            PlayMenuButton(
                iconKey = speedIconKey,
                contentDescription = stringResource(Res.string.speed),
                pressedBgEnabled = playMenuButtonPressedBgEnabled,
            ) { showSpeed = true }
            if (showSpeed) {
                speedDialogSlot(speed, onSetSpeed) { showSpeed = false }
            }
        }
        Spacer(Modifier.weight(1f))
        // 上一章
        PlayMenuButton(
            iconKey = "ic_skip_previous",
            contentDescription = stringResource(Res.string.previous_chapter),
            enabled = prevEnabled,
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onPrev() }
        // 播放/暂停 (圆形白底)
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
                    contentDescription = stringResource(Res.string.audio_play),
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (loading) {
                // 64dp 视图 - 4dp stroke = 60dp 圈环, 内缘贴 56dp 按钮外缘
                CircularProgressIndicator(
                    color = accentColor,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        // 下一章
        PlayMenuButton(
            iconKey = "ic_skip_next",
            contentDescription = stringResource(Res.string.next_chapter),
            enabled = nextEnabled,
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onNext() }
        Spacer(Modifier.weight(1f))
        // 停止 (desktop 独有, app 端 onStop=null 不渲染)
        if (onStop != null) {
            PlayMenuButton(
                iconKey = "ic_stop_black_24dp",
                contentDescription = stringResource(Res.string.stop),
                pressedBgEnabled = playMenuButtonPressedBgEnabled,
            ) { onStop() }
            Spacer(Modifier.weight(1f))
        }
        // 播放模式
        PlayMenuButton(
            iconKey = playModeIconKey(playMode),
            contentDescription = stringResource(Res.string.play_mode),
            iconPadding = playModeIconPadding,
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onChangePlayMode() }
        Spacer(Modifier.weight(1f))
        // 目录
        PlayMenuButton(
            iconKey = chapterListIconKey,
            contentDescription = stringResource(Res.string.chapter_list),
            pressedBgEnabled = playMenuButtonPressedBgEnabled,
        ) { onOpenToc() }
    }
}

/** 46dp 圆钮: 按压态圆形底 (可选) + 白图标/禁用 25% 白 */
@Composable
private fun PlayMenuButton(
    iconKey: String,
    contentDescription: String,
    enabled: Boolean = true,
    iconPadding: Dp = 4.dp,
    pressedBgEnabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg = rememberColor("arco_fill_3")
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .then(
                if (pressedBgEnabled && pressed) Modifier.background(pressedBg)
                else Modifier,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color(0x3FFFFFFF),
            modifier = Modifier
                .fillMaxSize()
                .padding(iconPadding),
        )
    }
}

/** 播放模式 → 图标 key (各端 rememberPainter 解析: app→R.drawable.ic_play_mode_*; desktop→SVG) */
private fun playModeIconKey(mode: AudioPlayShared.PlayMode): String = when (mode) {
    AudioPlayShared.PlayMode.LIST_END_STOP -> "ic_play_mode_list_end_stop"
    AudioPlayShared.PlayMode.SINGLE_LOOP -> "ic_play_mode_single_loop"
    AudioPlayShared.PlayMode.RANDOM -> "ic_play_mode_random"
    AudioPlayShared.PlayMode.LIST_LOOP -> "ic_play_mode_list_loop"
}

// ===== @Preview 合并自 androidMain 的 book/audio/AudioPlayScreenPreviews.kt =====

/**
 * [AudioPlayScreenContent] 的 @Preview。
 *
 * coverSlot/lrcSlot/对话框 slot 均用占位实现 (真实实现依赖 Coil/平台歌词组件)。
 */

private val previewAudioCoverSlot: @Composable (String?, Modifier) -> Unit = { _, modifier ->
    Box(
        modifier.background(Color(0xFF34495E), DesignTokens.shapeDefault),
        contentAlignment = Alignment.Center,
    ) {
        Text("封面", color = Color.White)
    }
}

private val previewLrcSlot: @Composable (Modifier) -> Unit = { modifier ->
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("这里是歌词滚动区", color = Color(0xFF888888))
    }
}

@Preview
@Composable
fun AudioPlayScreenContentPlayingPreview() = LegadoThemePreview {
    AudioPlayScreenContent(
        title = "三体(有声剧)",
        subTitle = "第十二章 黑暗森林",
        coverUrl = null,
        coverVisible = true,
        timerMinute = 0,
        speed = 1.0f,
        progressMs = 754_000,
        durationMs = 1_800_000,
        bufferMs = 1_200_000,
        isPlaying = true,
        loading = false,
        playMode = AudioPlayShared.PlayMode.LIST_LOOP,
        prevEnabled = true,
        nextEnabled = true,
        accentColor = Color(0xFF165DFF),
        onBack = {},
        onOpenChangeSource = {},
        onCoverClick = {},
        onTogglePlay = {},
        onPrev = {},
        onNext = {},
        onChangePlayMode = {},
        onOpenToc = {},
        onSeek = {},
        onSetTimer = {},
        onSetSpeed = {},
        coverSlot = previewAudioCoverSlot,
        lrcSlot = previewLrcSlot,
        timerDialogSlot = { _, _, _ -> },
        speedDialogSlot = { _, _, _ -> },
    )
}

@Preview
@Composable
fun AudioPlayScreenContentPausedLoadingPreview() = LegadoThemePreview {
    AudioPlayScreenContent(
        title = "三体(有声剧)",
        subTitle = "第十三章 面壁者",
        coverUrl = null,
        coverVisible = true,
        timerMinute = 30,
        speed = 1.5f,
        progressMs = 0,
        durationMs = 0,
        bufferMs = 0,
        isPlaying = false,
        loading = true,
        playMode = AudioPlayShared.PlayMode.SINGLE_LOOP,
        prevEnabled = false,
        nextEnabled = true,
        accentColor = Color(0xFF165DFF),
        onBack = {},
        onOpenChangeSource = {},
        onCoverClick = {},
        onTogglePlay = {},
        onPrev = {},
        onNext = {},
        onChangePlayMode = {},
        onOpenToc = {},
        onSeek = {},
        onSetTimer = {},
        onSetSpeed = {},
        onStop = {},
        coverSlot = previewAudioCoverSlot,
        lrcSlot = previewLrcSlot,
        timerDialogSlot = { _, _, _ -> },
        speedDialogSlot = { _, _, _ -> },
    )
}

@Preview
@Composable
fun AudioPlayScreenContentDarkPreview() = LegadoThemePreview(dark = true) {
    AudioPlayScreenContent(
        title = "三体(有声剧)",
        subTitle = "第十二章 黑暗森林",
        coverUrl = null,
        coverVisible = true,
        timerMinute = 0,
        speed = 1.0f,
        progressMs = 754_000,
        durationMs = 1_800_000,
        bufferMs = 1_500_000,
        isPlaying = true,
        loading = false,
        playMode = AudioPlayShared.PlayMode.RANDOM,
        prevEnabled = true,
        nextEnabled = true,
        accentColor = Color(0xFF165DFF),
        onBack = {},
        onOpenChangeSource = {},
        onCoverClick = {},
        onTogglePlay = {},
        onPrev = {},
        onNext = {},
        onChangePlayMode = {},
        onOpenToc = {},
        onSeek = {},
        onSetTimer = {},
        onSetSpeed = {},
        coverSlot = previewAudioCoverSlot,
        lrcSlot = previewLrcSlot,
        timerDialogSlot = { _, _, _ -> },
        speedDialogSlot = { _, _, _ -> },
    )
}

/**
 * 只响应未被子级消费的点击 (子级按钮/歌词等消费后不触发); 用于"面板打开时点击外部关闭"。
 * 消费语义: down 被子级消费 (requireUnconsumed=true) 时直接跳过; up 未取消 (非拖动) 才回调。
 */
private fun Modifier.tapOnUnconsumed(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        if (down == null) return@awaitEachGesture
        val up = waitForUpOrCancellation()
        if (up != null) onTap()
    }
}
