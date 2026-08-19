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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.rememberPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirectionShared
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.compose.platform.rememberMandatoryGestureBottomPx
import io.legado.app.ui.compose.platform.rememberNavigationBarHidden
import io.legado.app.ui.compose.platform.rememberStatusBarHidden
import io.legado.app.ui.compose.platform.rememberVisibleNavigationBarHeightPx
import io.legado.app.ui.compose.platform.rememberVisibleStatusBarHeightPx
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.formatTimeOfDay
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.ic_cursor_left
import legado.shared.generated.resources.ic_cursor_right
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.math.roundToInt

// 长按触发时间（对照原版 ReadView.longPressTimeout = 600，postDelayed(longPressRunnable, 600)）
private const val LONG_PRESS_TIMEOUT = 600L

/**
 * KMP 版阅读视图：用 Compose 替代 app 端 `ReadView` (FrameLayout + 3 个 PageView)。
 *
 * # 渲染路径
 *
 * [rememberPageDelegate] 按 `ReadBookConfig.pageAnim` 取翻页委托（对照原版 `ReadView.upPageAnim`），
 * 调 [io.legado.app.ui.book.read.page.delegate.PageDelegateCompose.renderPageAnimation]，
 * 把 3 个 [PageViewComposable] 作为 `@Composable () -> Unit` lambda 传入，delegate 完全接管：
 * - 三页位置 / 偏移 / 动画（用 `Modifier.offset` + `Animatable`）
 * - 阴影等叠加层绘制（`Canvas` + `Brush.horizontalGradient`）
 *
 * # 手势分发（对照原版 ReadView.onTouchEvent）
 *
 * 触摸手势由本层唯一一个 `pointerInput` 分发器处理，状态机逐分支对照原版
 * `onTouchEvent`（DOWN/MOVE/UP/CANCEL + 四布尔位 + 长按定时），按下/拖动/抬手分别驱动
 * delegate 的命令式接口 [onDown] / [onScroll] / [onTouchUp]（对照原版
 * `pageDelegate.onTouch`）；单击先做页内列级命中（[dispatchColumnClick]），未消费回落
 * 九宫格 [onTapAt]（对照原版 `if (!curPage.onClick(startX, startY)) onSingleTapUp()`）。
 * 桌面端鼠标手势由 [readerMouseGestures] 单独一层接管，触摸分发器对鼠标零消费让位。
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
 * # 坐标空间（触摸/鼠标全窗坐标 → 内容区坐标）
 *
 * 触摸/鼠标坐标是全窗坐标（含状态栏区域），排版/绘制基准是正文区原点
 * （= 窗口原点 + 状态栏 + 页眉，正文区即页眉/页脚布局占位子节点之间的空间）。
 * 所有命中入口（列级点击/长按选词/扩选）先减
 * systemBarTopPx + 页眉实测高 折算到正文区坐标（对照原版
 * contentTextView.click(x, y - headerHeight)，headerHeight 含 vwStatusBar + llHeader）；九宫格分区同样按内容区坐标；弹菜单锚点加回
 * systemBarTopPx 还原为窗口坐标。x 无水平 inset 不折算；滚动折算两侧一致不变。
 *
 * # 与 app 端差异
 *
 * - app 端用 View 的 onTouchEvent 分发到 PageDelegate；KMP 版由本层单一分发器
 *   `pointerInput` 驱动 delegate 命令式接口，结构与状态机逐分支对照原版
 * - 列命中只查当前页（滚动模式行级滚动由 delegate 折算偏移，列坐标仍以当前页为基准）
 *
 * @param viewModel 阅读 ViewModel，提供 prevTextPage/curTextPage/nextTextPage 流
 * @param batteryLevel 电池电量 0-100 (读取失败回落 100 恒显示, 用户拍板 2026-08)
 * @param clockText 当前系统时间 HH:mm，随 timeChanged 刷新
 * @param onClick 单击回调（动作 0=菜单，由调用方处理；翻页/切章在本 Composable 内消费）
 * @param onLongClick 长按回调（仅非文字非图片区域回落：空白长按；文字长按由页内选择接管，
 *   图片长按走 [onImageLongPress]，均对照旧 ReadView.onLongPress 的列分发）
 * @param onImageLongPress 图片长按回调（命中图片列，携带 src 与长按点窗口坐标；对照旧
 *   ContentTextView.longPress 的 ImageColumn 分支 → ReadBookActivity.onImageLongPress 图片菜单）
 * @param onAction 非翻页类点击动作（书签/目录/搜索等），对照 app 端 ReadView.click 的 callBack 分支
 * @param onSelectionMenu 页内文字选择完成回调（选中文本 + 选区起点锚点（窗口坐标：页内坐标 +
 *   滚动折算 + 状态栏高度，平台浮动菜单按窗口定位）；
 *   对照旧 ReadView.CallBack.showTextActionMenu → 平台浮动菜单跟随选区）
 * @param onDismissSelectionMenu 同步关闭浮动文本操作菜单回调（点按取消选择等手势分支在
 *   选区清除的同帧同步直调，对照原版 ACTION_DOWN → textActionMenu.dismiss() 同步语义，
 *   避免事件链异步延迟造成的菜单"闪一下再消失"；平台 dismiss 幂等，事件链异步兜底
 *   重复调用安全）
 * @param menuVisible 阅读菜单是否可见（桌面端鼠标手势层让位用；菜单可见时点击由菜单 bg 收起）
 * @param onTextAreaMeasured 正文区实测尺寸上报（px，来自正文区布局占位子节点
 *   onSizeChanged）：排版视口的单一来源，透传 ReaderRoute（对照原版
 *   ContentTextView.onSizeChanged → ChapterProvider.upViewSize）
 * @param externalSelection 外部注入的页内文字选择状态（全文搜索跳转的程序化选区用）；
 *   默认 null 时内部 remember 自建，现有调用方零改动
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
    onDismissSelectionMenu: () -> Unit = {},
    menuVisible: () -> Boolean = { false },
    onTextAreaMeasured: ((IntSize) -> Unit)? = null,
    externalSelection: PageSelectionState? = null,
) {
    val prevTextPage by viewModel.prevTextPage.collectAsState()
    val curTextPage by viewModel.curTextPage.collectAsState()
    val nextTextPage by viewModel.nextTextPage.collectAsState()
    val nextPlusTextPage by viewModel.nextPlusTextPage.collectAsState()
    // 朗读高亮等页内容原地变更版本号：自增时强制 Canvas 重绘（见 PageContentCanvas.drawTick）
    val pageDrawTick by viewModel.pageContentVersion.collectAsState()
    // 按 ReadBookConfig.pageAnim 取翻页委托，配置变更时重建（对照原版 ReadView.upPageAnim）
    val composeDelegate = rememberPageDelegate(viewModel)
    val tapScope = rememberCoroutineScope()

    // 正文 layout 预热 + 滑出窗口页的缓存回收。窗口口径与原版 ReadBook.recycleRecorders
    // 一致（保留 [cur-1 .. cur+2]）；测量器取 ReaderScreen 提供的共享实例
    PageLayoutPrewarmEffect(
        pages = listOf(prevTextPage, curTextPage, nextTextPage, nextPlusTextPage),
        style = rememberReaderDrawStyle(),
        measurer = rememberReaderTextMeasurer(),
    )

    // 页眉/页脚实际测量高度（px，来自页面布局占位子节点 onSizeChanged 上报）。
    // 本层两处消费：
    // 1. 命中/选择坐标折算（窗口 y → 页内坐标需减状态栏 + 页眉，对照原版
    //    contentTextView.click(x, y - headerHeight) 的 headerHeight 含 vwStatusBar + llHeader）
    // （排版视口不再由页眉/页脚差值推导：ReaderRoute 直接消费正文区实测尺寸，见
    //   onTextAreaMeasured —— 对照原版 ContentTextView.onSizeChanged 单一来源。）
    var headerTipMeasured by remember { mutableIntStateOf(0) }
    var footerTipMeasured by remember { mutableIntStateOf(0) }

    // 页内文字选择状态（对照旧 ReadView.isTextSelected + ContentTextView.selectStart/selectEnd）
    // 全文搜索跳转时由外部（ReaderScreenModel）注入同一实例，程序化选区与手势选择共用
    val selection = externalSelection ?: remember { PageSelectionState() }
    // 滚动模式正文平移量（仅 ScrollPageDelegateCompose 写入, 非滚动模式恒 0）:
    // 选择命中坐标折算回页内坐标用（选中高亮随内容层平移, 见 ScrollPageView 的
    // graphicsLayer translationY）。
    // 不订阅 collectAsState：滚动每帧 updateScrollOffset 会触发整棵阅读树每帧重组（三页全部
    // 重建 contentTranslationY lambda → 整页重绘），改为在事件回调（长按/弹菜单/扩选）内直接读
    // StateFlow.value 取最新值，滚动热路径只走 graphicsLayer 绘制期订阅（只失效图层）。
    // 整页切换/重排时清除选择（对照旧版：翻页清选择来自 ReadBook.moveToNextPage 的显式
    // cancelSelect + setContent 换页实例后旧 selected 标志随实例废弃；upContent 链本身
    // 不含 cancelSelect）; 未激活时无操作。
    // 搜索跳转守卫：程序化 selectRange 作用于目标页（pageRef 与新页一致）时不清除——
    // KMP 组合观察（LaunchedEffect 重启）晚于 model 侧 selectRange（旧版跳转选区同样设置
    // 在 skipToPage 的 upContent 成功回调之后，但旧版无“页切换自动清选择”机制，故无此问题），
    // 无脑 cancel 会清掉刚设的搜索高亮。
    // 真实翻页/重排时 pageRef 仍指向旧页，与新页不一致，仍按原语义清除。
    LaunchedEffect(curTextPage) {
        if (selection.currentPage !== curTextPage) {
            selection.cancel()
        }
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
    // 选区消失 → 通知平台关闭浮动文本操作菜单（对照旧 ContentTextView.cancelSelect →
    // callBack.onCancelSelect → ReadBookActivity.onCancelSelect → textActionMenu.dismiss：
    // 选区与菜单强绑定，任何清除路径——点按取消/翻页/重排/菜单动作后/外部状态变化——
    // 都必须同步关菜单）。观察 isActive 下降沿（唯一清除入口是 [PageSelectionState.cancel]，
    // 覆盖全部调用点，避免逐处手动 post 漏路径）；图片长按菜单同样并入本条件
    // （旧 isImageMenuShowing，翻页/换章等非按下路径靠它关菜单）；平台侧收集见
    // ReaderRoute → [ReaderPlatformProvider.onTextSelectionDismissed]
    LaunchedEffect(Unit) {
        snapshotFlow { selection.isActive || selection.imageMenuShowing }
            .drop(1) // 初始 false，只观察 true→false 下降沿
            .filter { !it }
            .collect { ReadBookEvents.postSelectionDismissed() }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val pageWidthInt = pageWidthPx.roundToInt()
        val pageHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val pageHeightInt = pageHeightPx.roundToInt()
        // 系统栏 inset：正文内容层已整体避让（PageViewComposable 内 statusBarFixedPadding
        // + navigationBarFixedPadding），但九宫格/长按分区仍按全窗坐标判定，须排除系统栏区域
        // （对照原版 contentTextView 被 vwStatusBar/vwNavigationBar 占位挤小后的 bounds）。
        // 与正文避让同源取值: 隐藏时 0, 显示时缓存可见高度 (事件化, 不逐帧跟随)
        val density = LocalDensity.current
        val systemBarTopPx = if (rememberStatusBarHidden()) 0 else rememberVisibleStatusBarHeightPx()
        val systemBarBottomPx =
            if (rememberNavigationBarHidden()) 0 else rememberVisibleNavigationBarHeightPx()
        // 选区手柄尺寸（px，对照原版 cursorWidth = 24.dpToPx：手柄 24dp 方形）
        val handleSizePx = with(density) { 24.dp.toPx() }
        // 内容区高度（全窗高 - 状态栏 - 导航栏）：九宫格分区与命中判定统一按内容区坐标
        val contentHeightPx = pageHeightPx - systemBarTopPx - systemBarBottomPx
        // 底部强制系统手势区高度（px）：Android 30+ 手势导航时非 0，其余平台/三键导航恒 0
        // （见 MandatoryGestureInsets）。对照原版 ReadView.onTouchEvent 开头的
        // mandatorySystemGestures 拦截：落在该带子内的触摸不参与阅读手势
        val mandatoryGestureBottomPx = rememberMandatoryGestureBottomPx()
        // 拦截阈值（全窗坐标）：y 超过 全窗高 - 手势区高 即落在带子内（对照原版
        // event.y > windowManager.currentWindowMetrics.bounds.height() - insets.bottom）
        val mandatoryGestureBoundPx = pageHeightPx - mandatoryGestureBottomPx.toFloat()
        // 坐标基准: 触摸/鼠标坐标是全窗坐标（含状态栏区域），排版/绘制基准是正文区原点
        // = 窗口原点 + 状态栏 + 页眉（正文区即页眉/页脚布局占位子节点之间的空间），
        // 所有命中入口先减 systemBarTopPx + 页眉实测高再判行/列（对照原版
        // contentTextView.click(x, y - headerHeight)，headerHeight 含 vwStatusBar + llHeader）。rememberUpdatedState：pointerInput(Unit)
        // 长驻协程不随重组重启，经 State 间接读保证取到最新 inset。
        val latestSystemBarTopPx by rememberUpdatedState(systemBarTopPx)
        // 强制手势区拦截阈值（rememberUpdatedState：手势长驻协程不随重组重启，经 State 间接读）
        val latestMandatoryGestureBoundPx by rememberUpdatedState(mandatoryGestureBoundPx)
        // 页眉实测高（rememberUpdatedState：手势长驻协程不随重组重启，经 State 间接读）
        val latestHeaderTipPx by rememberUpdatedState(headerTipMeasured)

        // 手势长驻协程（pointerInput）不随重组重启，经 rememberUpdatedState 间接读保证
        // 取到最新 delegate/回调/落点（delegate 在翻页动画配置变更时重建）
        val latestDelegate by rememberUpdatedState(composeDelegate)

        // 九宫格点击动作分发（对照 app 端 ReadView.onSingleTapUp → click(action)）。
        // 先做页内列级点击分发（对照原版 ACTION_UP 先 curPage.onClick 再 onSingleTapUp），
        // 列命中并消费（图片/段评）后不再走九宫格动作。
        val onTapAt: (Float, Float) -> Unit = onTapAt@{ x, y ->
            // 系统栏区域点击不响应（原版状态栏/导航栏占位区域点击落在 contentTextView
            // bounds 外无动作，此处等价过滤，避免点状态栏/导航条误翻页/误开菜单）
            if (y < systemBarTopPx || y >= systemBarTopPx + contentHeightPx) return@onTapAt
            // 命中坐标折算为内容区坐标（全窗 y - 状态栏高度；x 无水平 inset 不动）。
            // 列级命中与九宫格分区共用同一坐标系（对照原版 contentTextView 被
            // vwStatusBar/vwNavigationBar/llHeader/llFooter 挤小后的 bounds）
            val contentY = y - latestSystemBarTopPx - latestHeaderTipPx
            if (dispatchColumnClick(viewModel, tapScope, x, contentY)) {
                return@onTapAt
            }
            // 动画被打断后点击中心格忽略（对照原版 onSingleTapUp 的
            // clickArea.isCenter && isAbortAnim → return）
            if (isClickCenter(x, contentY, pageWidthInt, contentHeightPx.roundToInt()) &&
                latestDelegate.isAbortAnim
            ) {
                return@onTapAt
            }
            when (val action = readClickActionConfig().actionAt(
                    x, contentY, pageWidthInt, contentHeightPx.roundToInt()
                )) {
                0 -> onClick(null)
                1 -> viewModel.turnPageByClick(PageDirectionShared.NEXT)
                2 -> viewModel.turnPageByClick(PageDirectionShared.PREV)
                3 -> viewModel.moveToNextChapter()
                // 原版 moveToPrevChapter(toLast = false)：切上一章后落到章首而非章末
                4 -> viewModel.moveToPrevChapter(toLast = false)
                else -> onAction(action)
            }
        }

        // 点击分区由本层决定，delegate 只负责动画（对照原版 ReadView 持有 ClickArea）
        composeDelegate.onTapAt = onTapAt
        // 滚动 delegate（滚动模式渲染由 ScrollPageView 接管；非滚动模式为 null）
        val scrollDelegate = composeDelegate as? ScrollPageDelegateCompose

        // 长按落点回调（分发器长按定时触发）: 命中文字列 → 词级选中（对照旧
        // ReadView.onLongPress → ContentTextView.longPress + BreakIterator 词边界）;
        // 命中图片列 → onImageLongPress（对照旧 ImageColumn 分支 → 图片长按菜单）;
        // 空白 → onLongClick(null)（原版空白长按无动作，四端一致无动作）。
        // pointerInput(Unit) 不随重组重启, 用 rememberUpdatedState 取最新页/宽度/回调。
        val latestPageWidth by rememberUpdatedState(pageWidthPx)
        val latestOnLongClick by rememberUpdatedState(onLongClick)
        val latestOnImageLongPress by rememberUpdatedState(onImageLongPress)
        val onPageLongPress: (Float, Float) -> Unit = onPageLongPress@{ x, y ->
            // 同上：系统栏区域长按无动作（原版占位区域不触发长按选择/空白长按）
            if (y < systemBarTopPx || y >= systemBarTopPx + contentHeightPx) return@onPageLongPress
            // 命中坐标折算为内容区坐标（同 onTapAt；选词/图片列命中共用同一坐标系）
            val contentY = y - latestSystemBarTopPx - latestHeaderTipPx
            // 三页相对命中（对照原版 ContentTextView.longPress → touch 遍历三页）：
            // 滚动模式视口内可能显示下一页的行，长按命中同样按三页连排坐标系折算
            val hit = hitColumn(viewModel, x, contentY)
            if (hit != null && hit.column is TextColumn) {
                if (selection.longPressStart(
                        hit.page, x, contentY, hit.relativeOffset, latestPageWidth
                    )
                ) {
                    // 选择激活期间暂停自动翻页（对照旧手势按下 → autoPager.pause），
                    // 避免翻页打断选择；选择取消时在分发器/页切换处恢复
                    latestDelegate.autoPager?.pause()
                } else {
                    // 词展开失败（异常路径）：回落空白长按
                    latestOnLongClick(null)
                }
            } else if (hit?.column is ImageColumn) {
                // 标记菜单显示中（对照旧 ReadBookActivity.onImageLongPress →
                // readView.isImageMenuShowing = true）：下次按下/翻页走取消链路关菜单
                selection.imageMenuShowing = true
                // 菜单定位坐标保持窗口坐标（平台浮动菜单按窗口定位）
                latestOnImageLongPress(hit.column.src, x, y)
            } else {
                latestOnLongClick(null)
            }
        }

        if (scrollDelegate != null) {
            // 滚动模式：单画布三页连排渲染（见 ScrollPageView 注释——跨页错帧闪烁根治）
            scrollDelegate.setViewSize(pageWidthInt, pageHeightInt)
            ScrollPageView(
                scrollDelegate = scrollDelegate,
                viewModel = viewModel,
                textPage = curTextPage,
                modifier = Modifier.fillMaxSize(),
                batteryLevel = batteryLevel,
                clockText = clockText,
                drawTick = pageDrawTick,
                selection = selection,
                onHeaderMeasured = { headerTipMeasured = it },
                onFooterMeasured = { footerTipMeasured = it },
                onTextAreaMeasured = onTextAreaMeasured,
            )
        } else {
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
                            // 页眉/页脚实测高度上报（布局占位子节点 onSizeChanged）：
                            // 滚动连排占位与坐标折算共用；排版视口走 onTextAreaMeasured 单一来源
                            onHeaderMeasured = { headerTipMeasured = it },
                            onFooterMeasured = { footerTipMeasured = it },
                            onTextAreaMeasured = onTextAreaMeasured,
                        )
                    }
                },
                curContent = {
                    // 当前页恒组合（textPage 可空）：正文区 Box 在无页首帧也参与测量，排版视口
                    // （onTextAreaMeasured）才有第一个稳定值——initBook 依赖它启动，见 ReaderRoute
                    PageViewComposable(
                        textPage = curTextPage,
                        modifier = Modifier.fillMaxSize(),
                        batteryLevel = batteryLevel,
                        clockText = clockText,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        drawTick = pageDrawTick,
                        selection = selection,
                        onHeaderMeasured = { headerTipMeasured = it },
                        onFooterMeasured = { footerTipMeasured = it },
                        onTextAreaMeasured = onTextAreaMeasured,
                    )
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
                            selection = selection,
                        )
                    }
                },
                // 横向翻页模式各页自带完整背景，第 3 页连排仅滚动模式使用（由
                // ScrollPageView 承担），本 lambda 不再被任何路径调用
                nextPlusContent = {},
            )
        }

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
                    onHeaderMeasured = { headerTipMeasured = it },
                    onFooterMeasured = { footerTipMeasured = it },
                )
            }
        }

        // 选区手柄覆盖层（对照原版 activity_book_read.xml 的 cursor_left/cursor_right
        // ImageView：24dp 半圆光标 + accentColor 着色）。绘制期读 selection.tick 建立
        // 快照订阅（同 PageContentCanvas 的 draw 阶段读法），拖拽热路径只重绘不重组；
        // 坐标折算与 selectionMenuAnchor 同套（页内坐标 + 滚动折算 + 页眉 + 状态栏）
        SelectionHandleOverlay(
            selection = selection,
            handleSizePx = handleSizePx,
            scrollOffsetY = { relativeOffsetOf(viewModel, selection.currentPage) },
            headerTipPx = latestHeaderTipPx.toFloat(),
            systemBarTopPx = latestSystemBarTopPx.toFloat(),
        )

        // 统一触摸分发层 + 桌面鼠标手势层（同一 Layout 的多个 pointerInput 需
        // sharePointerInputWithSiblings 共享命中路径，否则后挂的鼠标层收不到事件）。
        // 触摸手势全部由本层单一分发器处理（对照原版 ReadView.onTouchEvent 状态机），
        // delegate 不再挂任何手势；鼠标手势由 readerMouseGestures 全权接管。
        val latestOnSelectionMenu by rememberUpdatedState(onSelectionMenu)
        // 弹菜单时的选区锚点（窗口坐标 = 页内坐标 + 滚动折算 + 页眉 + 状态栏高度；
        // 滚动模式内容下移锚点同步下移，再加回 systemBarTopPx 供平台浮动菜单按窗口定位）。
        // 直接读最新值不订阅：避免滚动每帧触发重组（同上方 scrollOffset 说明）
        val selectionMenuAnchor: () -> Offset? = {
            selection.selectionAnchor()?.let {
                Offset(
                    it.x,
                    it.y + relativeOffsetOf(viewModel, selection.currentPage) +
                        latestHeaderTipPx + latestSystemBarTopPx
                )
            }
        }
        // 抬手弹选择菜单：空选区时取消而非弹空菜单（有意的 UX 修正，保留）
        val showSelectionMenu: () -> Unit = {
            val text = selection.selectedText()
            if (text.isBlank()) {
                selection.cancel()
                latestDelegate.autoPager?.resume()
            } else {
                latestOnSelectionMenu(text, selectionMenuAnchor())
            }
        }
        // 分发器长驻协程读最新回调/落点（同 latestDelegate 理由）
        val latestOnTapAt by rememberUpdatedState(onTapAt)
        val latestOnPageLongPress by rememberUpdatedState(onPageLongPress)
        val latestShowSelectionMenu by rememberUpdatedState(showSelectionMenu)
        // 同步关菜单回调（rememberUpdatedState：pointerInput(Unit) 长驻协程不随重组重启，
        // 经 State 间接读保证取到最新回调）
        val latestOnDismissSelectionMenu by rememberUpdatedState(onDismissSelectionMenu)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .sharePointerInputWithSiblings()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        // 鼠标手势由 readerMouseGestures 全权接管，本层对鼠标零消费让位
                        if (down.type == PointerType.Mouse) return@awaitEachGesture
                        val downX = down.position.x
                        val downY = down.position.y
                        // 手势起点（多指切换时更新，对照原版 ReadView.startX/startY 的
                        // setStartPoint 语义：slop/长按/单击落点都随最新起点）
                        var startX = down.position.x
                        var startY = down.position.y
                        // 当前跟踪的手指 id（多指切换：对照原版 POINTER_DOWN 切到
                        // 最后按下的手指、POINTER_UP 切回最早按下的手指）
                        var trackedId = down.id
                        // 底部强制系统手势带子内按下 → 本次手势整体放弃（对照原版
                        // onTouchEvent 开头 API>=R 的 mandatorySystemGestures 判据）。
                        // 等价性：原版吞掉 DOWN 后 pressDown=false，后续 MOVE 首行
                        // !pressDown 早退、UP/CANCEL 首行 !pressDown return，状态机零动作；
                        // 本层 return 后 awaitEachGesture 不再处理本手势（内部等全部抬起
                        // 再等下一个 DOWN），净效果一致
                        if (downY > latestMandatoryGestureBoundPx) return@awaitEachGesture
                        val slop = viewConfiguration.touchSlop
                        // 第二道 slop 阈值（平方）：自定义 pageTouchSlop，0 = 未自定义 → 回退
                        // 平台 slop（对照原版 ReadView.upPageSlopSquare 的
                        // pageTouchSlop == 0 ? slopSquare : pageTouchSlop，slopSquare =
                        // ViewConfiguration.scaledTouchSlop）。每手势读一次（idiom 同
                        // HorizontalPageDelegateCompose.onDown / ReadViewComposable 内
                        // previewImageByClick 的 PreferenceProviders 读法），阈值在手势内恒定
                        val pageTouchSlop = runCatching { PreferenceProviders.get() }
                            .getOrNull()?.getInt(PreferKey.pageTouchSlop, 0) ?: 0
                        val slopSquare = if (pageTouchSlop == 0) slop else pageTouchSlop.toFloat()
                        val slopSquare2 = slopSquare * slopSquare
                        // 对照原版 ReadView.onTouchEvent 的状态机：
                        // pressDown（本层 awaitFirstDown 后恒真，省略）/ isMove / longPressed /
                        // pressOnTextSelected 四布尔位 + 长按定时。
                        // 两道 slop 判定各自独立（对照原版 ReadView.isMove 与
                        // ScrollPageDelegate.isMoved 双标志）：
                        // - firstSlopPassed = 第一道：平台 1-D slop（absX>slop || absY>slop），
                        //   只置 true 不回退（原版 isMove 语义）；决定"进入 MOVE 处理/取消长按"；
                        // - isMove = 第二道：欧氏距离平方（dx²+dy² > 自定义slop²，0 回退平台），
                        //   决定"真的翻页/滚动"（原版 delegate.isMoved 语义）。
                        // UP 单击要求两道都没过（原版 if (!isMoved && !isMove)）。
                        var firstSlopPassed = false
                        var isMove = false
                        var longPressed = false
                        var pressOnTextSelected = false
                        // 长按定时（对照原版 postDelayed(longPressRunnable, 600)）。
                        // 挂 tapScope（rememberCoroutineScope）：pointerInput 的
                        // PointerInputScope/AwaitPointerEventScope 均非 CoroutineScope
                        // （官方声明 interface PointerInputScope : Density），launch 只能
                        // 挂组合层 scope；长按协程与下方 MOVE 事件循环并行，互不阻塞
                        // 长按落点读最新手势起点（对照原版 longPressRunnable 读
                        // setStartPoint 后的 startX/startY，多指切换时随最新手指）
                        val longPressJob = tapScope.launch {
                            delay(LONG_PRESS_TIMEOUT)
                            longPressed = true
                            latestOnPageLongPress(startX, startY)
                        }
                        // 手柄抓取标志（对照原版 cursor_left/cursor_right ImageView 的
                        // OnTouchListener：手柄消费 DOWN 后 ReadView 收不到事件，不触发
                        // cancelSelect/onDown/长按定时）
                        var grabbingLeftHandle = false
                        var grabbingRightHandle = false
                        // ACTION_DOWN 分支：先判是否落在任一手柄矩形内（命中判定对照原版
                        // 手柄 ImageView bounds）——命中则本次手势抓取手柄，跳过"点按取消
                        // 选区"；未命中维持原行为（取消选择 + 抑制点击）。
                        // 图片长按菜单显示中同样进本分支（对照原版 onImageLongPress 置
                        // isTextSelected=true 借道本链路关菜单）：无选区时手柄命中自然落空，
                        // 走 else 取消 + 关菜单 + 抑制这一次点击（原版同样不弹阅读菜单）
                        if (selection.isActive || selection.imageMenuShowing) {
                            // 手柄锚点窗口坐标折算同 selectionMenuAnchor（页内坐标 +
                            // 滚动折算 + 页眉 + 状态栏）
                            val handleOffsetY = relativeOffsetOf(viewModel, selection.currentPage) +
                                latestHeaderTipPx.toFloat() + latestSystemBarTopPx.toFloat()
                            val startHandle = selection.startHandleOffset()?.let {
                                // 左手柄右缘对齐起点锚点（对照原版 cursorLeft.x = x - width）
                                Offset(it.x - handleSizePx, it.y + handleOffsetY)
                            }
                            val endHandle = selection.endHandleOffset()?.let {
                                Offset(it.x, it.y + handleOffsetY)
                            }
                            if (startHandle != null && downX >= startHandle.x &&
                                downX <= startHandle.x + handleSizePx &&
                                downY >= startHandle.y && downY <= startHandle.y + handleSizePx
                            ) {
                                grabbingLeftHandle = true
                            } else if (endHandle != null && downX >= endHandle.x &&
                                downX <= endHandle.x + handleSizePx &&
                                downY >= endHandle.y && downY <= endHandle.y + handleSizePx
                            ) {
                                grabbingRightHandle = true
                            }
                            if (grabbingLeftHandle || grabbingRightHandle) {
                                // 手柄按下：关菜单但保留选区（对照原版手柄 DOWN →
                                // textActionMenu.dismiss，同步直调平台关菜单；
                                // 手柄不改变选区激活态，事件链不会触发，必须走同步通道）；
                                // 取消长按定时（对照原版手柄 DOWN 不启动长按定时）
                                latestOnDismissSelectionMenu()
                                longPressJob.cancel()
                                longPressed = false
                                pressOnTextSelected = true
                            } else {
                                selection.cancel()
                                // 点按取消选择：同步直调平台关菜单（对照原版 ACTION_DOWN →
                                // textActionMenu.dismiss() 同步语义，避免事件链异步延迟
                                // 2~5 帧的"菜单完整闪一下再消失"；事件链兜底仍会再触发一次，
                                // 平台 dismiss 幂等，重复调用安全）
                                latestOnDismissSelectionMenu()
                                latestDelegate.autoPager?.resume()
                                pressOnTextSelected = true
                            }
                        } else {
                            pressOnTextSelected = false
                        }
                        // 对照原版 DOWN 分支：pageDelegate.onTouch + onDown + setStartPoint
                        // （手柄抓取时原版 ReadView 收不到 DOWN，不触发 onDown，跳过）
                        if (!grabbingLeftHandle && !grabbingRightHandle) {
                            latestDelegate.onDown(downX, downY)
                        }
                        val tracker = VelocityTracker()
                        // 对照原版 ScrollPageDelegate.onTouch ACTION_DOWN: mVelocity.clear()
                        // (DOWN 事件不入速度追踪)
                        // UP/CANCEL 传给 onTouchUp 的坐标取最后一次事件位置
                        // （对照原版 UP 分支 pageDelegate?.onTouch(event) 传 UP 事件坐标）
                        var lastX = downX
                        var lastY = downY
                        var gestureCancelled = false
                        // ACTION_MOVE 分支：越过 touchSlop 后消费并分流
                        // （选择激活 → 扩选；否则 → delegate 翻页/滚动拖动）
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val tracked = event.changes.firstOrNull { it.id == trackedId } ?: break
                            // 多指触控（对照原版 ACTION_POINTER_DOWN 分支）：新手指按下时
                            // 切换跟踪到最后按下的手指并重置起点（旧 setStartPoint 语义，
                            // 只更新起点不打断动画），本次事件不滚动
                            val pressedNow = event.changes.filter { it.pressed }
                            if (pressedNow.size > 1) {
                                val newest = pressedNow.last()
                                if (newest.id != trackedId) {
                                    trackedId = newest.id
                                    startX = newest.position.x
                                    startY = newest.position.y
                                    latestDelegate.setStartPoint(
                                        newest.position.x, newest.position.y
                                    )
                                    continue
                                }
                            }
                            val change = tracked
                            // 取消检测（对照官方 awaitDragOrCancellation / awaitTouchSlopOrCancellation
                            // 的 change.isConsumed 惯例）：Android ACTION_CANCEL 不生成 PointerEvent
                            // —— MotionEventAdapter 对 ACTION_CANCEL 返回 null，AndroidComposeView
                            // 改走 processCancel()，由 SuspendingPointerInputFilter 合成"全部抬起且
                            // isInitiallyConsumed=true"的 cancel 事件派发，对应原版 ACTION_CANCEL
                            // 分支；普通 UP 的 change 是 isConsumed=false，不会误判成取消
                            if (change.isConsumed) {
                                gestureCancelled = true
                                break
                            }
                            if (!change.pressed) {
                                // 跟踪手指抬起（对照原版 ACTION_POINTER_UP 分支）：
                                // 其余手指继续时切回最早按下的手指并重置起点，
                                // 本次事件不滚动（旧 POINTER_UP → setStartPoint + return）
                                val remaining = pressedNow.filter { it.id != trackedId }
                                if (remaining.isNotEmpty()) {
                                    val first = remaining.first()
                                    trackedId = first.id
                                    startX = first.position.x
                                    startY = first.position.y
                                    latestDelegate.setStartPoint(
                                        first.position.x, first.position.y
                                    )
                                    continue
                                }
                                break
                            }
                            // 原版逐事件判据：MOVE 滑进底部强制手势带子 → 吞掉（状态机不动、
                            // 不消费、速度不更新），移出带子恢复；UP/CANCEL 不受此判据限制
                            // （break 后走收尾分支，原版同样放行）。不能只判 DOWN：从正文区
                            // 按下拖进带子的翻页必须停住，防翻页动画与系统返回手势打架
                            if (change.position.y > latestMandatoryGestureBoundPx) continue
                            lastX = change.position.x
                            lastY = change.position.y
                            if (!firstSlopPassed) {
                                // 第一道：平台 1-D slop（对照原版 ReadView.onTouchEvent
                                // ACTION_MOVE 的 absX > slopSquare || absY > slopSquare），
                                // 只置 true 不回退。原版第一道过即取消长按定时
                                // （removeCallbacks(longPressRunnable)）并进入 MOVE 处理；
                                // 迁移版把"取消长按"也挂在这里（下方 isMove 分支不再重复），
                                // 保证与点击判定同源：第一道过 → 不再可能走单击
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (abs(dx) > slop || abs(dy) > slop) {
                                    firstSlopPassed = true
                                    longPressJob.cancel()
                                    longPressed = false
                                }
                            }
                            if (firstSlopPassed) {
                                // 对照原版 ScrollPageDelegate.onScroll: mVelocity.addMovement(event)
                                // 在每次 MOVE 都执行（第一道过即进 delegate.onTouch，与第二道
                                // isMoved 无关）——速度窗口含 slop 后全部移动；扩选/手柄
                                // 拖动不走 delegate.onTouch，原版不追踪速度，此处同样排除
                                if (!selection.isActive && !grabbingLeftHandle && !grabbingRightHandle) {
                                    tracker.addPointerInputChange(change)
                                }
                            }
                            if (firstSlopPassed && !isMove) {
                                // 第二道：欧氏距离平方判定，dx/dy 取从按下点累计的总位移
                                // （对照原版 ScrollPageDelegate.onScroll 的
                                // distance = dx²+dy² > pageSlopSquare2；scroll 与横向
                                // delegate 共用本判定，横向 delegate 不再重复计算），
                                // 越过阈值才首次调 delegate.onScroll（首次调用即携带
                                // 从按下点起的完整位移，delegate 内据此定方向/重置起点）
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                isMove = dx * dx + dy * dy > slopSquare2
                            }
                            if (grabbingLeftHandle || grabbingRightHandle) {
                                // 手柄拖动：无 slop 直接生效（对照原版手柄 OnTouchListener
                                // 的 MOVE 分支无条件 selectStartMove/selectEndMove）
                                longPressJob.cancel()
                                longPressed = false
                                change.consume()
                                // 坐标折算同 extendTo（全窗 y 减状态栏 + 页眉），再按原版
                                // Activity 折算补手柄宽（rawX ± width / rawY - height）
                                val handleY = change.position.y - handleSizePx -
                                    latestSystemBarTopPx.toFloat() - latestHeaderTipPx.toFloat()
                                val scrollOffset = relativeOffsetOf(viewModel, selection.currentPage)
                                if (grabbingLeftHandle) {
                                    // 反转后左手柄改驱动终点（对照原版 getReverseStartCursor 分流）
                                    if (selection.reverseStartCursor) {
                                        selection.moveEndTo(
                                            change.position.x - handleSizePx, handleY,
                                            handleSizePx, scrollOffset, latestPageWidth,
                                        )
                                    } else {
                                        selection.moveStartTo(
                                            change.position.x + handleSizePx, handleY,
                                            handleSizePx, scrollOffset, latestPageWidth,
                                        )
                                    }
                                } else {
                                    // 反转后右手柄改驱动起点（对照原版 getReverseEndCursor 分流）
                                    if (selection.reverseEndCursor) {
                                        selection.moveStartTo(
                                            change.position.x + handleSizePx, handleY,
                                            handleSizePx, scrollOffset, latestPageWidth,
                                        )
                                    } else {
                                        selection.moveEndTo(
                                            change.position.x - handleSizePx, handleY,
                                            handleSizePx, scrollOffset, latestPageWidth,
                                        )
                                    }
                                }
                            } else if (isMove) {
                                // 第二道已过：消费并进入翻页/滚动（对照原版 isMove 后
                                // pageDelegate.onTouch；长按定时已在第一道过时取消，
                                // 这里不重复）
                                change.consume()
                                if (selection.isActive) {
                                    // 长按选中后拖动扩选（对照原版 selectText）
                                    selection.extendTo(
                                        change.position.x,
                                        change.position.y - latestSystemBarTopPx - latestHeaderTipPx,
                                        relativeOffsetOf(viewModel, selection.currentPage),
                                        latestPageWidth,
                                    )
                                } else {
                                    latestDelegate.onScroll(change.position.x, change.position.y)
                                }
                            }
                        }
                        // 抬手/取消：取消长按定时（对照原版 removeCallbacks）
                        longPressJob.cancel()
                        val grabbedHandle = grabbingLeftHandle || grabbingRightHandle
                        if (gestureCancelled) {
                            if (grabbedHandle) {
                                // 手柄抓取时 CANCEL：原版手柄 OnTouchListener 无 CANCEL
                                // 分支（无操作；反转标志留待下次手柄 UP 复位），照搬
                            } else {
                                // ACTION_CANCEL 分支：无单击路径，只走选择菜单/翻页后半段，
                                // 并恢复自动翻页（对照原版 CANCEL 分支 + autoPager.resume；
                                // 原版本就无 else 互斥，此分支结构正确，仅传参坐标一并修正）
                                if (selection.isActive) {
                                    latestShowSelectionMenu()
                                } else if (latestDelegate.isMoved) {
                                    latestDelegate.onTouchUp(lastX, lastY, tracker.calculateVelocity().y)
                                }
                                pressOnTextSelected = false
                                latestDelegate.autoPager?.resume()
                            }
                        } else {
                            // ACTION_UP 分支（对照原版 UP 分支的贯穿结构）：
                            // "未移动且未长按且未抑制" → 点击并结束本次处理（原版内层 return true）；
                            // 其余情况一律贯穿到选择菜单/翻页收尾——原版外层 if 无 else，
                            // 长按选词后不拖动抬手（longPressed==true 时点击路径被跳过）
                            // 必须能落到下方 showTextActionMenu 对应物，不能互斥截断。
                            var handledAsTap = false
                            // 单击要求两道都没过（对照原版 UP 分支的
                            // if (!pageDelegate.isMoved && !isMove)：isMove 是第一道、
                            // isMoved 是第二道；迁移版 firstSlopPassed/isMove 与之对应）
                            if (!latestDelegate.isMoved && !isMove && !firstSlopPassed) {
                                // 未移动的抬手：长按/选择抑制后先走页内列级命中，未消费
                                // 回落九宫格（对照原版 if (!curPage.onClick(startX, startY))
                                // onSingleTapUp()；onTapAt 已封装列命中 + 九宫格分发）
                                if (!longPressed && !pressOnTextSelected) {
                                    latestOnTapAt(startX, startY)
                                    handledAsTap = true
                                }
                            }
                            if (!handledAsTap) {
                                if (selection.isActive) {
                                    if (grabbedHandle) {
                                        // 对照原版手柄 UP：resetReverseCursor + showTextActionMenu
                                        selection.resetReverseCursor()
                                    }
                                    latestShowSelectionMenu()
                                } else if (latestDelegate.isMoved) {
                                    latestDelegate.onTouchUp(lastX, lastY, tracker.calculateVelocity().y)
                                }
                                pressOnTextSelected = false
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
                        // 选择激活期间拖动扩选：坐标换算与统一分发器触摸扩选段完全一致（原样照搬）
                        onSelectionExtend = { x, y ->
                            selection.extendTo(
                                x,
                                y - latestSystemBarTopPx - latestHeaderTipPx,
                                relativeOffsetOf(viewModel, selection.currentPage),
                                latestPageWidth,
                            )
                        },
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
 * 命中规则与 ContentTextView.touch 一致：三页相对遍历（[hitColumn]），第一个命中的
 * 列生效；[ReviewColumn] → 段评对话框，[ImageColumn] → 书源点击规则/图片预览。
 *
 * @param x/y 正文区坐标（调用方已减状态栏 + 页眉折算，对照原版
 *        contentTextView.click(x, y - headerHeight)）
 *
 * @return true 已消费（图片/段评动作），false 未消费（回落九宫格动作）
 */
private fun dispatchColumnClick(
    viewModel: ReadBookViewModelShared,
    scope: kotlinx.coroutines.CoroutineScope,
    x: Float,
    y: Float,
): Boolean {
    val hit = hitColumn(viewModel, x, y) ?: return false
    val column = hit.column
    when (column) {
        is ReviewColumn -> {
            // 对照原版 onReviewClick: chapterList[textPage.chapterIndex] + ReviewListDialog
            val book = viewModel.book.value ?: return false
            val chapter =
                viewModel.chapterList.value.getOrNull(hit.page.chapterIndex) ?: return false
            PlatformCapabilityProviders.getOrNull()
                ?.showReviewListDialog(book, chapter, column.paragraphIndex)
            return true
        }

        is ImageColumn -> {
            if (column.onClick.isNotEmpty()) {
                // 对照原版 onImageClick: AnalyzeRule.evalJS(onClick) 执行书源点击规则
                val book = viewModel.book.value ?: return false
                val chapter =
                    viewModel.chapterList.value.getOrNull(hit.page.chapterIndex) ?: return false
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
                // 携带命中页章节索引：实现端据此优先查阅读时已落盘的章节图片缓存
                // （BookImageStorage，对照原版 PhotoDialog.loadPhoto 的章节缓存文件分支）
                PlatformCapabilityProviders.getOrNull()
                    ?.showImagePreview(column.src, hit.page.chapterIndex)
                return true
            }
            return false
        }

        // TextColumn 及未下沉列不消费（对照原版 TextColumn 无 click 分支，
        // ButtonColumn 由 app 端旧排版产生，shared ColumnFactory 不产出）
        else -> return false
    }
}

/** 三页相对命中结果（复刻原版 ContentTextView.touch 的 relativePos 0..2 遍历） */
private class ColumnHit(
    val page: TextPage,
    val column: BaseColumn,
    val relativeOffset: Float,
)

/**
 * 三页相对命中（复刻原版 ContentTextView.touch：滚动模式视口内可能显示下一页/下下页
 * 的行，点击/长按/列命中共用本遍历——relativePos 0..2，第 i 页相对视口偏移 = 滚动
 * 偏移 + 前面页高之和；下一页顶已到视口底之下即止（旧
 * `relativeOffset >= visibleHeight` return）。行命中但列未命中即止（旧 touch 语义）。
 *
 * @param x/y 正文区坐标（调用方已减状态栏 + 页眉折算）
 */
private fun hitColumn(
    viewModel: ReadBookViewModelShared,
    x: Float,
    y: Float,
): ColumnHit? {
    val pages = arrayOf(
        viewModel.curTextPage.value,
        viewModel.nextTextPage.value,
        viewModel.nextPlusTextPage.value,
    )
    val visibleHeight = pages[0]?.visibleHeight ?: 0
    val offset = viewModel.scrollOffset.value.toFloat()
    var rel = offset
    for (i in pages.indices) {
        val page = pages[i] ?: continue
        if (i > 0 && rel >= visibleHeight) return null
        for (line in page.lines) {
            if (!line.isTouch(x, y, rel)) continue
            for (column in line.columns) {
                if (column.isTouch(x)) return ColumnHit(page, column, rel)
            }
            // 行命中但列未命中：不消费（对照旧 touch 行内未命中即 return）
            return null
        }
        rel += page.height
    }
    return null
}

/**
 * 页相对视口偏移（复刻原版 ContentTextView.relativeOffset）：选择相关坐标折算
 * （扩选/手柄/菜单锚点）按选区所在页折算——滚动模式选区可落在下一页，
 * 相对偏移 = 滚动偏移 + 前面页高之和。
 */
private fun relativeOffsetOf(viewModel: ReadBookViewModelShared, page: TextPage?): Float {
    val offset = viewModel.scrollOffset.value.toFloat()
    return when (page) {
        null -> offset
        viewModel.curTextPage.value -> offset
        viewModel.nextTextPage.value -> offset + (viewModel.curTextPage.value?.height ?: 0f)
        viewModel.nextPlusTextPage.value -> offset + (viewModel.curTextPage.value?.height ?: 0f) +
            (viewModel.nextTextPage.value?.height ?: 0f)
        else -> offset
    }
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
    onHeaderMeasured: ((Int) -> Unit)? = null,
    onFooterMeasured: ((Int) -> Unit)? = null,
) {
    val progress = autoPager.progress // 读取状态触发重组（仅本覆盖层范围）
    val revealHeight = pageHeightPx * progress
    val style = rememberReaderDrawStyle()

    Box(modifier = Modifier.fillMaxSize()) {
        // 下一页：自顶向下 clip 揭示（未到末页时才有内容；末页时下一页为 null 只走进度线）。
        // 2026-08 回退: 曾加 revealLayer 录制缓存 (record), 但 record 内执行子节点
        // PageViewComposable 的 Canvas onDraw 会与外层 scope 的 fontScale 委托互相递归
        // → StackOverflowError (同 PageContentCanvas 白屏根因), 恢复直接绘制 + clipRect
        nextTextPage?.let { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(bottom = revealHeight.coerceIn(0f, size.height)) {
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
                    onHeaderMeasured = onHeaderMeasured,
                    onFooterMeasured = onFooterMeasured,
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

/**
 * 选区手柄覆盖层（对照原版 activity_book_read.xml 的 cursor_left/cursor_right ImageView：
 * 24dp 半圆光标 + accentColor 着色；左手柄右缘对齐起点锚点，右手柄左缘对齐终点锚点）。
 *
 * 组合期读 [PageSelectionState.isActive] 决定显隐，绘制期读 [PageSelectionState.tick]
 * 建立快照订阅（同 [PageContentCanvas] 的 draw 阶段读法）：拖拽热路径只重绘不重组。
 *
 * @param scrollOffsetY 滚动模式正文平移量提供者（绘制期调用读最新值，不触发重组；
 *        对照 selectionMenuAnchor 的滚动折算）
 */
@Composable
private fun SelectionHandleOverlay(
    selection: PageSelectionState,
    handleSizePx: Float,
    scrollOffsetY: () -> Float,
    headerTipPx: Float,
    systemBarTopPx: Float,
) {
    if (!selection.isActive) return
    val cursorLeftPainter = painterResource(Res.drawable.ic_cursor_left)
    val cursorRightPainter = painterResource(Res.drawable.ic_cursor_right)
    val accentColor = rememberReaderDrawStyle().accentColor
    Canvas(modifier = Modifier.fillMaxSize()) {
        // draw 阶段读 tick 建立快照订阅（同 PageContentCanvas）：拖拽热路径只重绘不重组
        if (selection.tick < 0) return@Canvas
        val start = selection.startHandleOffset() ?: return@Canvas
        val end = selection.endHandleOffset() ?: return@Canvas
        // 窗口坐标折算与 selectionMenuAnchor 同套（页内坐标 + 滚动折算 + 页眉 + 状态栏）
        val offsetY = scrollOffsetY() + headerTipPx + systemBarTopPx
        val tint = ColorFilter.tint(accentColor)
        val handleSize = Size(handleSizePx, handleSizePx)
        // 左手柄右缘对齐起点锚点（对照原版 cursorLeft.x = x - cursorLeft.width）
        translate(left = start.x - handleSizePx, top = start.y + offsetY) {
            with(cursorLeftPainter) { draw(handleSize, colorFilter = tint) }
        }
        // 右手柄左缘对齐终点锚点（对照原版 cursorRight.x = x）
        translate(left = end.x, top = end.y + offsetY) {
            with(cursorRightPainter) { draw(handleSize, colorFilter = tint) }
        }
    }
}
