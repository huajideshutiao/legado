package io.legado.app.ui.book.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.manga.render.MangaRenderState
import io.legado.app.ui.book.manga.render.webtoonGestures
import io.legado.app.ui.compose.platform.handleReadPageKeys
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 漫画阅读 Screen 主体内容（各端共享，由 desktop 调用）。
 *
 * 布局对齐 desktop 原 MangaReaderScreen：顶部标题栏 + 中间渲染区 + 底部章节控制栏，
 * 黑底 (#1A1A1A) 白字视觉风格完全保留。
 *
 * 渲染区复用 app 端 [MangaRenderState] + [webtoonGestures]（缩放/平移/双击锚定/边界拖动翻页），
 * 图片加载经 [imageSlot] 注入：desktop 用 ImageIO，app 用 Coil3+AndroidView（后续接入）。
 *
 * @param bookName 书名（标题栏主标题）
 * @param chapterTitle 章节标题（标题栏副标题）
 * @param images 当前章节图片 URL 列表
 * @param curChapterIndex 当前章节序号 (0-based)
 * @param chapterSize 总章节数
 * @param horizontal 横向翻页模式（true=LazyRow 整页，false=LazyColumn webtoon）
 * @param autoPageSpeed 自动翻页速度（横向=秒/页，纵向=滚动速度系数）
 * @param loading 加载中标记（覆盖层）
 * @param error 错误消息（null=无错误）
 * @param onBack 返回回调
 * @param onPrevChapter 上一章
 * @param onNextChapter 下一章
 * @param onPrevPage 上一页（提供后键盘上翻键优先调用，否则回落到 onPrevChapter）
 * @param onNextPage 下一页（提供后键盘下翻键/空格优先调用，否则回落到 onNextChapter）
 * @param onRetry 错误重试
 * @param onMenuToggle 菜单切换（居中点击触发，desktop 暂未接入菜单 Overlay）
 * @param onOpenToc 打开目录回调
 * @param onOpenChangeSource 打开换源回调
 * @param imageSlot 平台图片渲染插槽：(url, modifier, horizontal) -> Compose 图片组件
 */
@Composable
fun MangaReaderScreenContent(
    bookName: String,
    chapterTitle: String,
    images: List<String>,
    curChapterIndex: Int,
    chapterSize: Int,
    horizontal: Boolean,
    autoPageSpeed: Int,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPrevPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onMenuToggle: () -> Unit = {},
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
    imageSlot: @Composable (String, Modifier, Boolean) -> Unit,
) {
    // 键盘事件焦点: onPreviewKeyEvent 需节点持有焦点才触发, 进入即取焦点
    // (对照 desktop VideoPlayerScreen 焦点接线)
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyFocusRequester.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            // 键盘翻页/返回: 消费共享 handleReadPageKeys (方向/PageUp/PageDown/空格/Esc)
            .handleReadPageKeys(
                onPrevPage = { onPrevPage?.invoke() ?: onPrevChapter() },
                onNextPage = { onNextPage?.invoke() ?: onNextChapter() },
                onBack = onBack,
            )
            .focusRequester(keyFocusRequester)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            MangaTitleBar(
                bookName = bookName,
                chapterTitle = chapterTitle,
                onBack = onBack,
                onOpenToc = onOpenToc,
                onOpenChangeSource = onOpenChangeSource,
            )
            Box(Modifier.fillMaxSize().weight(1f)) {
                MangaRenderArea(
                    images = images,
                    horizontal = horizontal,
                    autoPageSpeed = autoPageSpeed,
                    loading = loading,
                    error = error,
                    onRetry = onRetry,
                    imageSlot = imageSlot,
                )
            }
            MangaControlBar(
                curIndex = curChapterIndex,
                size = chapterSize,
                onPrev = onPrevChapter,
                onNext = onNextChapter,
            )
        }
    }
}

// ---- 顶部标题栏 ----

@Composable
private fun MangaTitleBar(
    bookName: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenChangeSource: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = rememberPainter("ic_arrow_back"),
                contentDescription = rememberString("back"),
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = bookName,
                color = Color.White,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chapterTitle.isNotEmpty()) {
                Text(
                    text = chapterTitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onOpenToc) {
            Icon(
                painter = rememberPainter("ic_toc"),
                contentDescription = rememberString("chapter_list"),
                tint = Color.White,
            )
        }
        IconButton(onClick = onOpenChangeSource) {
            Icon(
                painter = rememberPainter("ic_exchange"),
                contentDescription = rememberString("change_source"),
                tint = Color.White,
            )
        }
    }
}

// ---- 渲染区 (复用 shared MangaRenderState + webtoonGestures) ----

@Composable
private fun MangaRenderArea(
    images: List<String>,
    horizontal: Boolean,
    autoPageSpeed: Int,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    imageSlot: @Composable (String, Modifier, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 渲染状态：缩放/平移/手势语义复用 app 端 MangaRenderState
    val state = remember { MangaRenderState() }
    state.scope = scope
    state.horizontal = horizontal
    // 横向翻页 snap 归位（对照 app 端 PagerSnapHelper）；纵向普通衰减 fling
    val fling = if (horizontal) {
        rememberSnapFlingBehavior(state.listState)
    } else {
        ScrollableDefaults.flingBehavior()
    }
    state.flingBehavior = fling

    // 自动翻页开关（默认关，由悬浮按钮切换）
    var autoPageEnabled by remember { mutableStateOf(false) }

    // 自动翻页: 水平模式按页定时翻; 垂直模式持续滚动
    LaunchedEffect(autoPageEnabled, horizontal, autoPageSpeed) {
        if (!autoPageEnabled) return@LaunchedEffect
        if (horizontal) {
            while (isActive) {
                kotlinx.coroutines.delay(autoPageSpeed * 1000L)
                if (!state.listState.canScrollForward) {
                    autoPageEnabled = false
                    break
                }
                val viewport = state.listState.layoutInfo.viewportSize.width
                scope.launch {
                    state.listState.scrollBy(viewport.toFloat())
                }
            }
        } else {
            while (isActive) {
                if (!state.listState.canScrollForward) {
                    autoPageEnabled = false
                    break
                }
                runCatching {
                    state.listState.scrollBy(10000f)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .onSizeChanged(state::onContainerSize)
            .pointerInput(state) { webtoonGestures(state) }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { state.onSingleTap(it) },
                    onDoubleTap = { state.onDoubleTap(it) },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.transX
                    translationY = state.transY
                },
        ) {
            if (images.isNotEmpty()) {
                if (horizontal) {
                    LazyRow(
                        state = state.listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it }) { url ->
                            MangaPageCell(
                                url = url,
                                horizontal = true,
                                imageSlot = imageSlot,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = state.listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it }) { url ->
                            MangaPageCell(
                                url = url,
                                horizontal = false,
                                imageSlot = imageSlot,
                            )
                        }
                    }
                }
            }
        }

        if (loading) {
            LoadingOverlay()
        }
        if (error != null && !loading) {
            ErrorOverlay(error = error, onRetry = onRetry)
        }
        AutoPageToggleButton(
            enabled = autoPageEnabled,
            onToggle = { autoPageEnabled = !autoPageEnabled },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun LazyItemScope.MangaPageCell(
    url: String,
    horizontal: Boolean,
    imageSlot: @Composable (String, Modifier, Boolean) -> Unit,
) {
    val cellModifier = if (horizontal) Modifier.fillParentMaxSize() else Modifier.fillMaxWidth()
    Box(
        cellModifier.background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        imageSlot(
            url,
            if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            horizontal,
        )
    }
}

// ---- 加载/错误覆盖层 ----

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = rememberString("loading"),
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ErrorOverlay(error: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = error, color = Color.White, textAlign = TextAlign.Center)
            Text(
                text = rememberString("reload"),
                color = Color(0xFF165DFF),
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- 自动翻页切换按钮 ----

@Composable
private fun AutoPageToggleButton(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF165DFF)
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) accent else Color(0x66000000))
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = rememberPainter("ic_auto_page"),
            contentDescription = rememberString("auto_next_page"),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ---- 底部章节控制栏 ----

@Composable
private fun MangaControlBar(
    curIndex: Int,
    size: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = curIndex > 0) {
            Icon(
                painter = rememberPainter("ic_skip_previous"),
                contentDescription = rememberString("previous_chapter"),
                tint = if (curIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
        Text(
            text = "${curIndex + 1}/${size}",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = curIndex < size - 1) {
            Icon(
                painter = rememberPainter("ic_skip_next"),
                contentDescription = rememberString("next_chapter"),
                tint = if (curIndex < size - 1) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
    }
}
