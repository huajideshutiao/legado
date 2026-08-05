package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.rememberPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * KMP 版阅读视图：用 Compose 替代 app 端 `ReadView` (FrameLayout + 3 个 PageView)。
 *
 * # 渲染路径
 *
 * [rememberPageDelegate] 按 `ReadBookConfig.pageAnim` 取翻页委托（对照原版 `ReadView.upPageAnim`），
 * 调 [io.legado.app.ui.book.read.page.delegate.PageDelegateCompose.renderPageAnimation]，
 * 把 3 个 [PageViewComposable] 作为 `@Composable () -> Unit` lambda 传入，delegate 完全接管：
 * - 三页位置 / 偏移 / 动画（用 `Modifier.offset` + `Animatable`）
 * - 手势检测（`detectDragGestures` / `detectTapGestures` 转发到 onDown/onScroll/onTap）
 * - 阴影等叠加层绘制（`Canvas` + `Brush.horizontalGradient`）
 *
 * 九宫格点击分区由本层判定后经 `onTapAt` 注入 delegate：拖动手势与点击动作互不干扰
 * （对照原版 ReadView 持有 `ClickArea`、delegate 只管动画的分工）。
 *
 * # 页内列级点击（对照原版 ReadView ACTION_UP → `curPage.onClick`）
 *
 * 单击先做列命中判定（[TextLine.isTouch] + [BaseColumn.isTouch]，与 ContentTextView.touch
 * 同口径），按列类型分发；未命中任何可点击列才回落九宫格动作：
 * - [ReviewColumn] → 平台 `showReviewListDialog`（对照原版 onReviewClick → ReviewListDialog）
 * - [ImageColumn] → onClick 规则非空时执行书源 JS（对照原版 onImageClick → AnalyzeRule.evalJS），
 *   否则按 `previewImageByClick` 配置走平台图片预览（对照原版 PhotoDialog）
 * - [TextColumn] 及其余列不消费，回落九宫格动作（对照原版 TextColumn 无 click 分支；
 *   ButtonColumn 未下沉 shared 排版，ColumnFactory 不会产出）
 *
 * # 自动翻页揭示动画覆盖层（对照原版 AutoPager.onDraw）
 *
 * 非 E-Ink 非滚动模式时，[AutoPagerCompose] 推进 [AutoPagerCompose.progress]，
 * 本层把下一页自顶向下 clip 揭示（graphicsLayer clipRect 只更新层属性、不重绘页内容）
 * 并叠加 accent 色 1px 进度线；滚动模式由委托的 [onAutoScrollBy] 连续滚动，
 * 无需覆盖层。手势翻页期间的暂停/恢复/复位由 delegate 钩子完成。
 *
 * # 与 app 端差异
 *
 * - app 端用 View 的 onTouchEvent 分发到 PageDelegate；KMP 版把手势下沉到 delegate 自身的
 *   `pointerInput`，点击落点仍回调本层
 * - 列命中只查当前页（滚动模式行级滚动由 delegate 折算偏移，列坐标仍以当前页为基准）
 *
 * @param viewModel 阅读 ViewModel，提供 prevTextPage/curTextPage/nextTextPage 流
 * @param batteryLevel 电池电量 0-100，传 -1 表示不显示
 * @param clockText 当前系统时间 HH:mm，随 timeChanged 刷新
 * @param onClick 单击回调（动作 0=菜单，由调用方处理；翻页/切章在本 Composable 内消费）
 * @param onLongClick 长按回调（仅非文字非图片区域回落：空白长按；文字长按由页内选择接管，
 *   图片长按走 [onImageLongPress]，均对照旧 ReadView.onLongPress 的列分发）
 * @param onImageLongPress 图片长按回调（命中图片列，携带 src 与长按点坐标；对照旧
 *   ContentTextView.longPress 的 ImageColumn 分支 → ReadBookActivity.onImageLongPress 图片菜单）
 * @param onAction 非翻页类点击动作（书签/目录/搜索等），对照 app 端 ReadView.click 的 callBack 分支
 * @param onSelectionMenu 页内文字选择完成回调（选中文本 + 选区起点锚点（页内坐标，含滚动折算）；
 *   对照旧 ReadView.CallBack.showTextActionMenu → 平台浮动菜单跟随选区）
 * @param menuVisible 阅读菜单是否可见（桌面端鼠标手势层让位用；菜单可见时点击由菜单 bg 收起）
 */
@Composable
fun ReadViewComposable(
    viewModel: ReadBookViewModelShared,
    modifier: Modifier = Modifier,
    batteryLevel: Int = -1,
    clockText: String = formatTimeOfDay(systemCurrentTimeMillis()),
    onClick: (TextColumn?) -> Unit = {},
    onLongClick: (TextColumn?) -> Unit = {},
    onImageLongPress: (String, Float, Float) -> Unit = { _, _, _ -> },
    onAction: (Int) -> Unit = {},
    onSelectionMenu: (String, Offset?) -> Unit = { _, _ -> },
    menuVisible: () -> Boolean = { false },
) {
    val prevTextPage by viewModel.prevTextPage.collectAsState()
    val curTextPage by viewModel.curTextPage.collectAsState()
    val nextTextPage by viewModel.nextTextPage.collectAsState()
    // 朗读高亮等页内容原地变更版本号：自增时强制 Canvas 重绘（见 PageContentCanvas.drawTick）
    val pageDrawTick by viewModel.pageContentVersion.collectAsState()
    // 按 ReadBookConfig.pageAnim 取翻页委托，配置变更时重建（对照原版 ReadView.upPageAnim）
    val composeDelegate = rememberPageDelegate(viewModel)
    val tapScope = rememberCoroutineScope()

    // 页内文字选择状态（对照旧 ReadView.isTextSelected + ContentTextView.selectStart/selectEnd）
    val selection = remember { PageSelectionState() }
    // 滚动模式正文平移量（仅 ScrollPageDelegateCompose 写入, 非滚动模式恒 0）:
    // 选择命中坐标折算回页内坐标用（选中高亮随内容层平移, 见 PageViewComposable contentTranslationY）
    val scrollOffset by viewModel.scrollOffset.collectAsState()
    // 整页切换/重排时清除选择（对照旧 upContent → cancelSelect）; 未激活时无操作
    LaunchedEffect(curTextPage) {
        selection.cancel()
        // 选择被页切换中断后恢复自动翻页（激活选择时已暂停）
        composeDelegate.autoPager?.resume()
    }
    // 平台侧文本操作菜单关闭/动作完成后取消选择（对照旧 TextActionMenu.onMenuActionFinally
    // → readView.cancelSelect()）；未激活时无操作
    LaunchedEffect(Unit) {
        ReadBookEvents.selectionCancel.collect {
            selection.cancel()
            composeDelegate.autoPager?.resume()
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val pageWidthInt = pageWidthPx.roundToInt()
        val pageHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val pageHeightInt = pageHeightPx.roundToInt()

        // 九宫格点击动作分发（对照 app 端 ReadView.onSingleTapUp → click(action)）。
        // 先做页内列级点击分发（对照原版 ACTION_UP 先 curPage.onClick 再 onSingleTapUp），
        // 列命中并消费（图片/段评）后不再走九宫格动作。
        val onTapAt: (Float, Float) -> Unit = onTapAt@{ x, y ->
            if (dispatchColumnClick(viewModel, curTextPage, tapScope, x, y)) {
                return@onTapAt
            }
            when (val action = readClickActionConfig().actionAt(x, y, pageWidthInt, pageHeightInt)) {
                0 -> onClick(null)
                1 -> viewModel.turnPage(PageDirectionShared.NEXT)
                2 -> viewModel.turnPage(PageDirectionShared.PREV)
                3 -> viewModel.moveToNextChapter()
                // 原版 moveToPrevChapter(toLast = false)：切上一章后落到章首而非章末
                4 -> viewModel.moveToPrevChapter(toLast = false)
                else -> onAction(action)
            }
        }

        // 点击分区由本层决定，delegate 只负责动画（对照原版 ReadView 持有 ClickArea）
        composeDelegate.onTapAt = onTapAt
        // 滚动模式行级平移提供者（对照旧 drawPage 的 translate + clipRect；
        // 非滚动模式为 null，PageViewComposable 走零开销原样渲染路径）
        val scrollDelegate = composeDelegate as? ScrollPageDelegateCompose
        // 下一页是否带完整页面装饰（背景/页眉/页脚）：仅滚动模式连排时由固定层提供背景
        // （showChrome=false, 对照旧 drawPage 只画 TextPage 内容）；横向翻页模式（覆盖/滑动/
        // 仿真/无动画）下一页是完整页面, 必须自带不透明背景, 否则翻到下一页时翻起区
        // 透出窗口背景（2026-08-04 用户反馈: 向后翻页背景透明）
        val nextPageShowChrome = scrollDelegate == null

        // 长按落点回调（供 delegate 手势转发）: 命中文字列 → 词级选中（对照旧
        // ReadView.onLongPress → ContentTextView.longPress + BreakIterator 词边界）;
        // 命中图片列 → onImageLongPress（对照旧 ImageColumn 分支 → 图片长按菜单）;
        // 空白 → onLongClick(null)（原版空白长按无动作，桌面端回落整章选择对话框）。
        // pointerInput(Unit) 不随重组重启, 用 rememberUpdatedState 取最新页/宽度/回调。
        val latestCurPage by rememberUpdatedState(curTextPage)
        val latestPageWidth by rememberUpdatedState(pageWidthPx)
        val latestOnLongClick by rememberUpdatedState(onLongClick)
        val latestOnImageLongPress by rememberUpdatedState(onImageLongPress)
        val onPageLongPress: (Float, Float) -> Unit = { x, y ->
            if (selection.longPressStart(
                    latestCurPage, x, y, scrollOffset.toFloat(), latestPageWidth
                )
            ) {
                // 选择激活期间暂停自动翻页（对照旧手势按下 → autoPager.pause），
                // 避免翻页打断选择；选择取消时在下方手势层/页切换处恢复
                composeDelegate.autoPager?.pause()
            } else {
                // 未命中文字列: 图片列 → 图片长按; 其余空白 → 回落
                val column = selection.columnAt(
                    latestCurPage, x, y, scrollOffset.toFloat()
                )
                if (column is ImageColumn) {
                    latestOnImageLongPress(column.src, x, y)
                } else {
                    latestOnLongClick(null)
                }
            }
        }

        composeDelegate.renderPageAnimation(
            pageWidthPx = pageWidthInt,
            pageHeightPx = pageHeightInt,
            prevContent = {
                prevTextPage?.let { page ->
                    PageViewComposable(
                        textPage = page,
                        modifier = Modifier.fillMaxSize(),
                        batteryLevel = batteryLevel,
                        clockText = clockText,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        drawTick = pageDrawTick,
                        selection = selection,
                    )
                }
            },
            curContent = {
                curTextPage?.let { page ->
                    PageViewComposable(
                        textPage = page,
                        modifier = Modifier.fillMaxSize(),
                        batteryLevel = batteryLevel,
                        clockText = clockText,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        drawTick = pageDrawTick,
                        // 滚动模式：正文随行级偏移平移（绘制阶段读取，不触发重组）
                        contentTranslationY = scrollDelegate?.let { sd -> { sd.contentOffset } },
                        selection = selection,
                    )
                }
            },
            nextContent = {
                nextTextPage?.let { page ->
                    PageViewComposable(
                        textPage = page,
                        modifier = Modifier.fillMaxSize(),
                        batteryLevel = batteryLevel,
                        clockText = clockText,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        drawTick = pageDrawTick,
                        // 滚动模式：下一页连排在当前页内容之后（旧 relativeOffset(1)），纯正文无装饰；
                        // 横向翻页模式：下一页是完整页面，需自带不透明背景（见 nextPageShowChrome）
                        contentTranslationY = scrollDelegate?.let { sd -> { sd.nextContentOffset } },
                        showChrome = nextPageShowChrome,
                        selection = selection,
                    )
                }
            },
            onClick = onClick,
            onLongClick = onPageLongPress,
        )

        // 自动翻页揭示动画覆盖层（对照原版 AutoPager.onDraw 的非 E-Ink 分支）
        composeDelegate.autoPager?.let { autoPager ->
            if (autoPager.isRunning && !autoPager.isEInkMode && !autoPager.scrollMode) {
                AutoPageRevealOverlay(
                    autoPager = autoPager,
                    nextTextPage = nextTextPage,
                    pageHeightPx = pageHeightPx,
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    drawTick = pageDrawTick,
                    selection = selection,
                )
            }
        }

        // 文字选择 + 桌面鼠标手势合并层（同一 Layout 的多个 pointerInput 共享同一命中路径;
        // sharePointerInputWithSiblings 让下层 delegate 手势层继续收到事件）。
        //
        // 背景 (2026-08-04 实测): CMP 命中测试默认在顶层兄弟布局命中后即阻断下层 —— 鼠标手势层
        // 叠在 selection 层之上时, selection 层完全收不到事件, 表现为长按能上色（激活在鼠标层）
        // 但扩选与弹菜单（selection 层职责）全部失效。两个手势合并到同一 Box 并开启共享后:
        // - 鼠标: 本层 Initial pass 统一消费, selection 层正常收事件（扩选/菜单）,
        //   delegate 手势层见 isConsumed 让位
        // - 触摸: 鼠标层不消费, delegate 手势链正常接管（触摸路径此前同样被阻断, 一并修复）
        //
        // 文字选择手势层职责（选择未激活时零消费、不干扰任何手势）:
        // - 按下时选择已激活 → 立即取消选择（对照旧 ACTION_DOWN → cancelSelect）
        // - 选择激活期间消费拖动 → 更新终点（对照旧 ACTION_MOVE → selectText）;
        //   消费后 delegate 的翻页/点击手势被取消 → 选择激活时禁止翻页（对照旧 isTextSelected 分流）
        // - 手势结束（抬起/取消）时选择已激活 → 弹选择菜单（触摸路径; 鼠标路径由鼠标层弹,
        //   本层对鼠标抬起跳过避免重复, 见下方 down.type 判定）; 若按下时取消了选择且本手势
        //   未重新选中 → 消费抬起事件抑制本次点击（对照旧 pressOnTextSelected 抑制单击）
        val latestOnSelectionMenu by rememberUpdatedState(onSelectionMenu)
        // 弹菜单时的选区锚点（页内坐标 + 滚动折算；滚动模式内容下移锚点同步下移）
        val latestScrollOffset by rememberUpdatedState(scrollOffset)
        val selectionMenuAnchor: () -> Offset? = {
            selection.selectionAnchor()?.let { Offset(it.x, it.y + latestScrollOffset) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .sharePointerInputWithSiblings()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downId = down.id
                        // 鼠标手势由下方 readerMouseGestures 全权接管 (含长按抬手弹菜单),
                        // 本层只对触摸路径弹菜单, 避免双弹
                        val isMouseGesture = down.type == PointerType.Mouse
                        val suppressedTap = if (selection.isActive) {
                            selection.cancel()
                            // 点按取消选择 → 恢复自动翻页（对照旧 ACTION_DOWN → cancelSelect）
                            composeDelegate.autoPager?.resume()
                            true
                        } else {
                            false
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == downId }
                            if (change == null || !change.pressed) {
                                if (suppressedTap) change?.consume()
                                break
                            }
                            if (selection.isActive) {
                                // 选择激活期间消费拖动 → 扩选终点（拖拽热路径只改选区数据 + tick）
                                change.consume()
                                selection.extendTo(
                                    change.position.x,
                                    change.position.y,
                                    scrollOffset.toFloat(),
                                    latestPageWidth,
                                )
                            }
                        }
                        // 触摸路径: 抬手弹选择菜单 (鼠标路径由鼠标手势层在抬起时弹)
                        if (selection.isActive && !isMouseGesture) {
                            val text = selection.selectedText()
                            if (text.isBlank()) {
                                // 拖回起点导致空选区：取消而非弹空菜单（对照旧版会弹空文本菜单,
                                // 此处为有意的 UX 修正）
                                selection.cancel()
                                composeDelegate.autoPager?.resume()
                            } else {
                                latestOnSelectionMenu(text, selectionMenuAnchor())
                            }
                        }
                    }
                }
                // 桌面端鼠标手势接管层（仅 PointerType.Mouse 生效；触摸零影响）:
                // 鼠标 单击/长按/拖拽 全部经本层转发 delegate 并统一消费, 修复桌面端阅读页
                // 鼠标拖拽翻页/点击无效 (2026-08-04, 参照 F68 漫画页 MangaMouseGestures 模式)。
                // key 用 composeDelegate: 翻页动画配置变更重建 delegate 时手势层同步重启。
                .pointerInput(composeDelegate) {
                    readerMouseGestures(
                        delegate = composeDelegate,
                        onClickFallback = onClick,
                        onLongPressAt = onPageLongPress,
                        isSelectionActive = { selection.isActive },
                        cancelSelection = {
                            selection.cancel()
                            // 点按取消选择 → 恢复自动翻页（对照 selection 层同款处理）
                            composeDelegate.autoPager?.resume()
                        },
                        menuVisible = menuVisible,
                        onLongPressMenu = { text ->
                            if (text.isNotBlank()) latestOnSelectionMenu(
                                text,
                                selectionMenuAnchor()
                            )
                        },
                        selectionText = { selection.selectedText() },
                    )
                },
        ) {}
    }
}

/**
 * 页内列级点击分发（对照原版 ContentTextView.click → touch 命中 + 列类型分发）。
 *
 * 命中规则与 ContentTextView.touch 一致：行内 `isTouch(x, y, 0)`（当前页无相对偏移），
 * 列内 `isTouch(x)`；第一个命中的列生效。
 *
 * @return true 已消费（图片/段评动作），false 未消费（回落九宫格动作）
 */
private fun dispatchColumnClick(
    viewModel: ReadBookViewModelShared,
    page: TextPage?,
    scope: kotlinx.coroutines.CoroutineScope,
    x: Float,
    y: Float,
): Boolean {
    if (page == null) return false
    // 滚动模式行级偏移: 行几何随 offset 平移 (对照旧 ContentTextView.touch 的 relativeOffset;
    // 非滚动模式 offset 恒 0, 不影响其他翻页模式)
    val relativeOffset = viewModel.scrollOffset.value.toFloat()
    for (textLine in page.lines) {
        if (!textLine.isTouch(x, y, relativeOffset)) continue
        for (column in textLine.columns) {
            if (!column.isTouch(x)) continue
            when (column) {
                is ReviewColumn -> {
                    // 对照原版 onReviewClick: chapterList[textPage.chapterIndex] + ReviewListDialog
                    val book = viewModel.book.value ?: return false
                    val chapter =
                        viewModel.chapterList.value.getOrNull(page.chapterIndex) ?: return false
                    PlatformCapabilityProviders.getOrNull()
                        ?.showReviewListDialog(book, chapter, column.paragraphIndex)
                    return true
                }

                is ImageColumn -> {
                    if (column.onClick.isNotEmpty()) {
                        // 对照原版 onImageClick: AnalyzeRule.evalJS(onClick) 执行书源点击规则
                        val book = viewModel.book.value ?: return false
                        val chapter =
                            viewModel.chapterList.value.getOrNull(page.chapterIndex) ?: return false
                        val source = viewModel.bookSource.value ?: return false
                        scope.launch {
                            runCatching {
                                val rule = AnalyzeRuleFactories.create(book, source)
                                rule.setBaseUrl(chapter.url)
                                rule.chapter = chapter
                                rule.evalJS(column.onClick)
                            }
                        }
                        return true
                    }
                    // 未配置点击规则：按 previewImageByClick 配置走平台图片预览（对照原版 PhotoDialog）
                    val preview = runCatching {
                        PreferenceProviders.get().getBoolean(PreferKey.previewImageByClick, false)
                    }.getOrDefault(false)
                    if (preview) {
                        PlatformCapabilityProviders.getOrNull()?.showImagePreview(column.src)
                        return true
                    }
                    return false
                }

                // TextColumn 及未下沉列不消费（对照原版 TextColumn 无 click 分支，
                // ButtonColumn 由 app 端旧排版产生，shared ColumnFactory 不产出）
                is TextColumn -> return false
                else -> return false
            }
        }
    }
    return false
}

/**
 * 自动翻页揭示动画覆盖层（对照原版 AutoPager.onDraw 非 E-Ink 分支）：
 *
 * - 下一页自顶向下 clip 揭示：`drawWithContent { clipRect(bottom = height * progress) }`
 *   绘制期裁剪（Compose 1.9 的 GraphicsLayerScope 无 clipRect API，无法走层属性裁剪）
 * - accent 色 1px 进度线（原版 `paint.color = ThemeStore.accentColor` + drawRect）
 *
 * progress 每帧由 [AutoPagerCompose] 推进（约 60fps），本层仅在 progress 变化时重组。
 */
@Composable
private fun AutoPageRevealOverlay(
    autoPager: AutoPagerCompose,
    nextTextPage: TextPage?,
    pageHeightPx: Float,
    batteryLevel: Int,
    clockText: String,
    onClick: (TextColumn?) -> Unit,
    onLongClick: (TextColumn?) -> Unit,
    drawTick: Int,
    selection: PageSelectionState? = null,
) {
    val progress = autoPager.progress // 读取状态触发重组（仅本覆盖层范围）
    val revealHeight = pageHeightPx * progress
    val style = rememberReaderDrawStyle()

    Box(modifier = Modifier.fillMaxSize()) {
        // 下一页：自顶向下 clip 揭示（未到末页时才有内容；末页时下一页为 null 只走进度线）。
        // 注: Compose 1.9 的 GraphicsLayerScope 无 clipRect API, 改用绘制期裁剪
        // (drawWithContent + clipRect), 语义不变但每帧重绘页内容
        nextTextPage?.let { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(bottom = revealHeight.coerceIn(0f, size.height.toFloat())) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                PageViewComposable(
                    textPage = page,
                    modifier = Modifier.fillMaxSize(),
                    batteryLevel = batteryLevel,
                    clockText = clockText,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    drawTick = drawTick,
                    selection = selection,
                )
            }
        }
        // accent 色 1px 进度线（对照原版 drawRect(0, bottom-1, width, bottom)）
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (revealHeight > 0f && revealHeight <= size.height) {
                val lineWidth = 1.dp.toPx()
                drawRect(
                    color = style.accentColor,
                    topLeft = Offset(0f, revealHeight - lineWidth),
                    size = Size(size.width, lineWidth),
                )
            }
        }
    }
}
