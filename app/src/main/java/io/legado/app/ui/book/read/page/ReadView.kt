package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ClickArea
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.invisible
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * 阅读视图
 *
 * ===========================================================================
 * TODO(KP2-H TTS 阶段统一处理): Compose 化改造——统一 app / shared 阅读层
 * ====================================================================================
 *
 * # 背景
 *
 * shared/sharedUiMain/.../page/ 下已有 Compose 版阅读层：
 * - [io.legado.app.ui.book.read.page.PageViewComposable]：单页 Composable
 *   （对应 app 端 [PageView]，渲染背景 + 文字 + 顶部/底部 tip）
 * - [io.legado.app.ui.book.read.page.ReadViewComposable]：三页容器
 *   （对应本类 [ReadView]，封装 prev/cur/next + 翻页 delegate）
 * - delegate 目录下 *Compose.kt：5 个翻页 delegate 的 Compose 版
 *   （Cover/Slide/NoAnim/Scroll/Simulation + 基类 PageDelegateCompose）
 *
 * 目标：让 app 端阅读页使用 shared 端 PageViewComposable delegate，统一阅读层实现。
 *
 * # 现状调研结论（2026-07-21）
 *
 * 1. 本类共 695 行，深度耦合 Android View 体系（FrameLayout + 3 个 [PageView] +
 *    [PageDelegate] + [AutoPager]）。
 * 2. 调用点：仅 [io.legado.app.ui.book.read.ReadBookActivity] 一处构造
 *    `ReadView(this, Xml.asAttributeSet(parser))`（ReadBookActivity.kt:145），
 *    但通过 `readView.xxx` 调用的方法 **60+ 处**，遍布 Activity 全身。
 * 3. 5 个 app 端 delegate（[CoverPageDelegate]/[SlidePageDelegate]/
 *    [NoAnimPageDelegate]/[ScrollPageDelegate]/[SimulationPageDelegate]）
 *    构造签名均为 `(readView: ReadView)`，直接读 [startX]/[touchY]/[curPage]/
 *    [nextPage]/[prevPage]/[pageSlopSquare2] 等内部状态，并回调 [fillPage]/
 *    [setStartPoint]/[onScrollAnimStart] 等方法。
 *
 * # 三方案评估
 *
 * ## 方案 A：完全替换
 * 在 ReadBookActivity 中用 `AndroidView { ComposeView() }` 包装
 * [ReadViewComposable]，删除本类。
 * - 不可行：[ReadViewComposable] 接受 [io.legado.app.ui.book.read.ReadBookViewModelShared]
 *   作为入参，app 端**未注入**此 ViewModel（app 端用 [io.legado.app.ui.book.read.ReadBookViewModel]，
 *   是两套独立实现，Grep `ReadBookViewModelShared` 在 app/ 下 0 命中）。
 * - 不可行：ReadBookActivity 60+ 处 `readView.xxx()` 调用中，大量能力 shared 端**没有**：
 *   文字选择 (`curPage.selectStartMoveIndex`/`selectEndMoveIndex`/`selectText`/
 *   `cancelSelect`/`getReverseStartCursor`)、自动翻页 (`autoPager.start/stop/pause/resume`)、
 *   朗读位置 (`getReadAloudPos`/`aloudStartSelect`)、cursor 视图 (`cursorLeft`/`cursorRight`)、
 *   长按选择 (`onLongPress`)、Bitmap 渲染优化 (`invalidateTextPage`/`submitRenderTask`/
 *   `isLongScreenShot`)、TextActionMenu/popupAction 集成等。
 * - 违反约束：会改变实现逻辑 + 改变 UI 样式（shared 端 tip 用 `padding(horizontal=16.dp,
 *   vertical=8.dp)`，app 端用 ViewBookPageBinding 布局，宽高边距不同）。
 *
 * ## 方案 B：渐进式兼容层
 * 保留本类作为兼容层，内部用 `AndroidView { ComposeView() }` 委托给
 * [PageViewComposable] / [ReadViewComposable]。
 * - 不可行：[PageViewComposable] 是**单页** Composable，对应 [PageView] 而非本类；
 *   [ReadViewComposable] 才对应本类，但同样依赖 [ReadBookViewModelShared]。
 * - 不可行：[PageViewComposable] 内部用 [io.legado.app.help.config.LocalReadConfigProviders]
 *   读取 `ReadBookConfigShared` / `ReadTipConfigShared`，app 端这套 CompositionLocal
 *   **未提供**（app 端用 [io.legado.app.help.config.ReadBookConfig] /
 *   [ReadTipConfig]，单例式 API，非 Compose 状态）。
 * - 不可行：app 端 [PageView] 是 `FrameLayout + ViewBookPageBinding`，承担进度条/
 *   状态栏/选区高亮/cursor/scroll 偏移/autoPager 集成/Bitmap 渲染等大量职责，
 *   [PageViewComposable] 仅渲染背景+文字+tip，能力差距过大；即使只替换 [PageView]
 *   内部也会破坏其全部 public API（`selectStartMoveIndex`/`scroll`/`setProgress`/
 *   `upBattery`/`upTime`/`getReadAloudPos` 等），从而连锁影响本类和 delegate。
 *
 * ## 方案 C：暂不改造（已选定）
 * 在本文件顶部记录 KDoc TODO，等 KP2-H TTS 阶段统一处理。
 * - 理由 1：shared 端 Composable 是 KMP 跨平台（desktop/iOS/ohos）的简化版，
 *   能力是 app 端 [PageView]/[ReadView] 的**严格子集**；强行接入等于 app 端降级。
 * - 理由 2：真正的"统一阅读层"应反向进行——把 app 端的高级能力（文字选择/cursor/
 *   autoPager/朗读位置/Bitmap 渲染/状态栏等）下沉到 shared 端 Composable，再让
 *   app 端接入；而非把 app 端降级到 shared 端当前能力。
 * - 理由 3：本类与 [PageView]/[PageDelegate]（5 子类）/[AutoPager]/
 *   TextActionMenu/cursorLeft/cursorRight/[io.legado.app.ui.book.read.ReadBookViewModel]
 *   紧密耦合，改造需整体设计，不能单点替换。
 * - 理由 4：任务约束"严禁擅自修改 ui 样式（宽高边距）"+"修改时不得改变实现逻辑或偷懒"，
 *   方案 A/B 都必然涉及这两项。
 *
 * # KP2-H 阶段需配套完成的事项（移交清单）
 *
 * 在 TTS 改造时一并处理本类 Compose 化，需先完成以下前置工作：
 *
 * 1. shared 端补齐 [PageViewComposable] 能力，至少对齐 app 端 [PageView] public API：
 *    - 文字选择：`selectStartMove/selectStartMoveIndex/selectEndMove/selectEndMoveIndex/
 *      selectText/cancelSelect/getReverseStartCursor/getReverseEndCursor/resetReverseCursor/
 *      selectedText/selectStartPos/selectEndPos`
 *    - 自动翻页：`AutoPager` 集成（start/stop/pause/resume/reset/upRecorder）
 *    - 长按：`longPress(x, y, callback)` + `onClick(x, y)`
 *    - 滚动：`scroll(offset)` / `setIsScroll` / `setAutoPager`
 *    - 状态：`upBg/upBgAlpha/upTime/upBattery/upStatusBar/upStyle/setProgress`
 *    - 朗读位置：`getReadAloudPos/getCurVisiblePage/textPage`
 *    - 渲染优化：`invalidateAll/invalidateContentView/submitRenderTask/isLongScreenShot/
 *      markAsMainPage`（对应 app 端 `optimizeRender` 路径）
 *    - 布局参数：`headerHeight`（被 ReadBookActivity:280 引用）
 * 2. shared 端补齐 [ReadViewComposable] 能力，对齐本类 public API：
 *    - `upContent/upPageAnim/upPageSlopSquare/upBg/upBgAlpha/upStyle/upTime/upBattery/
 *      upStatusBar/onDestroy/onPageChange/fillPage/setStartPoint/setTouchPoint/
 *      cancelSelect/invalidateTextPage/submitRenderTask/getSelectText/getReadAloudPos/
 *      getCurVisiblePage/aloudStartSelect/isLongScreenShot/onLayoutPageCompleted`
 *    - `pageFactory/pageDelegate/autoPager/isScroll/isAutoPage/isTextSelected/
 *      isImageMenuShowing` 属性
 *    - `CallBack` 接口（showActionMenu/screenOffTimerStart/showTextActionMenu/
 *      autoPageStop/openChapterList/addBookmark/changeReplaceRuleState/
 *      openSearchActivity/upSystemUiVisibility）
 * 3. app 端注入 shared 端依赖：
 *    - 在 ReadBookActivity 的 `Content()` 中用 `CompositionLocalProvider(
 *      LocalReadConfigProviders provides appReadConfigProviders)` 包一层
 *    - 实现 `ReadConfigProviders` 的 app 端 actual（桥接 [ReadBookConfig]/
 *      [ReadTipConfig] 单例到 Shared 配置流）
 *    - 让 [io.legado.app.ui.book.read.ReadBookViewModel] 实现/桥接
 *      [io.legado.app.ui.book.read.ReadBookViewModelShared] 接口
 *      （prevTextPage/curTextPage/nextTextPage StateFlow + pageDelegate +
 *      nextPage/prevPage/moveToNextChapter/moveToPrevChapter/canMoveToNextChapter/
 *      canMoveToPrevChapter）
 * 4. 替换 5 个 app 端 delegate 为 shared 端 *Compose delegate：
 *    - [CoverPageDelegate] → [io.legado.app.ui.book.read.page.delegate.CoverPageDelegateCompose]
 *    - [SlidePageDelegate] → SlidePageDelegateCompose
 *    - [NoAnimPageDelegate] → NoAnimPageDelegateCompose
 *    - [ScrollPageDelegate] → ScrollPageDelegateCompose
 *    - [SimulationPageDelegate] → SimulationPageDelegateCompose
 *    （5 个 app 端 delegate + 基类 [PageDelegate] 共 ~1000 行可删除）
 * 5. 适配 ReadBookActivity 60+ 处 `readView.xxx` 调用：
 *    - 大部分改为 `viewModel.xxx`（通过桥接后的 [ReadBookViewModelShared]）
 *    - cursorLeft/cursorRight 仍在 Activity 层用 Android View（Compose 内部不接管）
 *    - TextActionMenu/popupAction 仍在 Activity 层
 * 6. 删除本类（[ReadView]）+ [PageView] + [AutoPager] + app 端 delegate 全部
 *    （共 ~2000 行 Android View 代码可清理）
 *
 * # 验证检查项
 *
 * 改造完成后用 Grep 验证：
 * - `ReadView\(` 在 app/ 下应 0 命中（已删除）
 * - `: ReadView|readView\.|@BindView.*ReadView` 在 app/ 下应 0 命中
 * - `LocalReadConfigProviders` 在 app/ 下应有命中（已注入）
 * - `ReadBookViewModelShared` 在 app/ 下应有命中（已桥接）
 *
 * ===========================================================================
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    val callBack: CallBack get() = activity as CallBack
    var pageFactory: TextPageFactory = TextPageFactory(this)
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            upContent()
        }
    override var isScroll = false
    val prevPage by lazy { PageView(context) }
    val curPage by lazy { PageView(context) }
    val nextPage by lazy { PageView(context) }
    val defaultAnimationSpeed = 300
    private var pressDown = false
    private var isMove = false

    //起始点
    internal var startX: Float = 0f
    internal var startY: Float = 0f

    //上一个触碰点
    internal var lastX: Float = 0f
    internal var lastY: Float = 0f

    //触碰点
    internal var touchX: Float = 0f
    internal var touchY: Float = 0f

    //是否停止动画动作
    internal var isAbortAnim = false

    //长按
    private var longPressed = false
    private val longPressTimeout = 600L
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress()
    }
    internal var isTextSelected = false
    internal var isImageMenuShowing = false
    private var pressOnTextSelected = false
    private val initialTextPos = TextPos(0, 0, 0)

    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var pageSlopSquare: Int = slopSquare
    internal var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare
    private val clickArea = ClickArea()
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    private val upProgressThrottle = throttle(200) { post { upProgress() } }
    val autoPager = AutoPager(this)
    val isAutoPage get() = autoPager.isRunning

    init {
        addView(nextPage)
        addView(curPage)
        addView(prevPage)
        prevPage.invisible()
        nextPage.invisible()
        curPage.markAsMainView()
        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
    }

    private fun setRect9x() {
        clickArea.setRect(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            callBack.upSystemUiVisibility()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * 触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = this.rootWindowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.mandatorySystemGestures()
            )
            val height = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
            if (height != null) {
                if (event.y > height.minus(insets.bottom)
                    && event.action != MotionEvent.ACTION_UP
                    && event.action != MotionEvent.ACTION_CANCEL
                ) {
                    return true
                }
            }
        }

        //在多点触控时，事件不走ACTION_DOWN分支而产生的特殊事件处理
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pageDelegate?.onTouch(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                callBack.screenOffTimerStart()
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    if (isTextSelected) {
                        selectText(event.x, event.y)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        if (!curPage.onClick(startX, startY)) {
                            onSingleTapUp()
                        }
                        return true
                    }
                }
                if (isTextSelected && !isImageMenuShowing) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (isTextSelected && !isImageMenuShowing) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
            }
        }
        return true
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        if (isTextSelected) {
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    /**
     * 更新状态栏
     */
    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    /**
     * 保存开始位置
     */
    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y

        if (invalidate) {
            invalidate()
        }
    }

    /**
     * 保存当前位置
     */
    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        if (invalidate) {
            invalidate()
        }
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    /**
     * 长按选择
     */
    private fun onLongPress() {
        kotlin.runCatching {
            curPage.longPress(startX, startY) { textPos: TextPos ->
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            if (column is TextColumn) {
                                ci += column.charData.length
                            } else {
                                ci++
                            }
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
            }
        }
    }

    /**
     * 单击
     */
    private fun onSingleTapUp() {
        if (isTextSelected) return
        if (clickArea.isCenter(startX, startY) && isAbortAnim) return
        val action = clickArea.getAction(startX, startY)
        click(action)
    }

    /**
     * 点击
     */
    private fun click(action: Int) {
        when (action) {
            0 -> {
                callBack.showActionMenu()
            }

            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }
        }
    }

    /**
     * 选择文本
     */
    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            when {
                compare > 0 -> {
                    curPage.selectStartMoveIndex(textPos)
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                }

                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
        }
    }

    /**
     * 销毁事件
     */
    fun onDestroy() {
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    /**
     * 翻页动画完成后事件
     * @param direction 翻页方向
     */
    fun fillPage(direction: PageDirection): Boolean {
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }

            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }

            else -> false
        }
    }

    /**
     * 更新翻页动画
     */
    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = ReadBook.pageAnim() == 3
        ChapterProvider.upLayout()
        when (ReadBook.pageAnim()) {
            PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) {
                pageDelegate = CoverPageDelegate(this)
            }

            PageAnim.slidePageAnim -> if (pageDelegate !is SlidePageDelegate) {
                pageDelegate = SlidePageDelegate(this)
            }

            PageAnim.simulationPageAnim -> if (pageDelegate !is SimulationPageDelegate) {
                pageDelegate = SimulationPageDelegate(this)
            }

            PageAnim.scrollPageAnim -> if (pageDelegate !is ScrollPageDelegate) {
                pageDelegate = ScrollPageDelegate(this)
            }

            else -> if (pageDelegate !is NoAnimPageDelegate) {
                pageDelegate = NoAnimPageDelegate(this)
            }
        }
        if (upRecorder) {
            (pageDelegate as? HorizontalPageDelegate)?.upRecorder()
            autoPager.upRecorder()
        }
        pageDelegate?.setViewSize(width, height)
        if (isScroll) {
            curPage.setAutoPager(autoPager)
        } else {
            curPage.setAutoPager(null)
        }
        curPage.setIsScroll(isScroll)
    }

    /**
     * 更新阅读内容
     * @param relativePosition 相对位置 -1 上一页 0 当前页 1 下一页
     * @param resetPageOffset 滚动阅读是是否重置位置
     */
    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(pageFactory.curPage, resetPageOffset)
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage)
                1 -> nextPage.setContent(pageFactory.nextPage)
                else -> {
                    curPage.setContent(pageFactory.curPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage)
                    prevPage.setContent(pageFactory.prevPage)
                }
            }
        }
        callBack.screenOffTimerStart()
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    /**
     * 更新滑动距离
     */
    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * this.pageSlopSquare
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
    }

    /**
     * 更新背景
     */
    fun upBg() {
        ReadBookConfig.upBg(width, height)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    /**
     * 更新电量信息
     */
    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    /**
     * 从选择位置开始朗读
     */
    suspend fun aloudStartSelect() {
        val selectStartPos = curPage.selectStartPos
        var pagePos = selectStartPos.relativePagePos
        val line = selectStartPos.lineIndex
        val column = selectStartPos.columnIndex
        while (pagePos > 0) {
            if (!ReadBook.moveToNextPage()) {
                ReadBook.moveToNextChapterAwait(false)
            }
            pagePos--
        }
        val startPos = curPage.textPage.getPosByLineColumn(line, column)
        ReadBook.readAloud(startPos = startPos)
    }

    /**
     * @return 选择的文本
     */
    fun getSelectText(): String {
        return curPage.selectedText
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (AppConfig.optimizeRender) {
            pageFactory.run {
                prevPage.invalidateAll()
                curPage.invalidateAll()
                nextPage.invalidateAll()
                nextPlusPage.invalidateAll()
            }
        }
        curPage.invalidateContentView()
    }

    fun onScrollAnimStart() {
        autoPager.pause()
    }

    fun onScrollAnimStop() {
        autoPager.resume()
    }

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val currentChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(0) else null
        }

    override val nextChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(1) else null
        }

    override val prevChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(-1) else null
        }

    override fun hasNextChapter(): Boolean {
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun changeReplaceRuleState()
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
    }
}
