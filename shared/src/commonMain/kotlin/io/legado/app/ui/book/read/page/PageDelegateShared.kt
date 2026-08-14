package io.legado.app.ui.book.read.page

import io.legado.app.ui.book.read.page.entities.PageDirectionShared

/**
 * 翻页动画委托接口（KMP 共用，commonMain 版）。
 *
 * 与 app 端 `io.legado.app.ui.book.read.page.delegate.PageDelegate` 对应，
 * 但去除 Android View / Canvas / MotionEvent 依赖，改用纯 Kotlin 类型表达
 * 手势事件与状态字段，便于 commonMain / jvmMain / iosMain 共用。
 *
 * # 职责拆分
 *
 * 本接口仅承载 **平台无关** 的状态与事件 API：
 * - 状态字段：[isRunning] / [isCancel] / [isStarted] / [isMoved] / [mDirection]
 * - 视图尺寸：[setViewSize]
 * - 手势事件：[onDown] / [onScroll] / [onTap] / [computeScroll]
 * - 动画控制：[nextPageByAnim] / [prevPageByAnim] / [abortAnim] / [onAnimStart] / [onAnimStop]
 * - 章节边界判定：[hasPrev] / [hasNext]
 * - 生命周期：[onDestroy]
 *
 * Compose 相关 API（如 `@Composable` 叠加层绘制、`Modifier` 手势挂载）不放在本接口，
 * 由 [io.legado.app.ui.book.read.page.delegate.PageDelegate]（sharedUiMain 抽象基类）
 * 继承本接口后扩展，避免 commonMain 引入 Compose 类型（鸿蒙 ohosMain 不继承 sharedUiMain）。
 *
 * # actual 实现路线
 *
 * - CoverPageDelegate: 覆盖翻页（sharedUiMain，Compose Animatable + Modifier.offset）
 * - SlidePageDelegate: 滑动翻页
 * - SimulationPageDelegate: 仿真翻页（3D 翻转 + 贝塞尔阴影）
 * - ScrollPageDelegate: 滚动翻页（垂直滑动切换）
 * - NoAnimPageDelegate: 无动画翻页
 *
 * # 与 app 端差异
 *
 * - app 端 `onDraw(canvas: Canvas)` 抽象方法 → KMP 版改由 Compose 子接口的 `@Composable PageOverlay()` 承载
 * - app 端 `onTouch(event: MotionEvent)` → KMP 版拆为 [onDown] / [onScroll] / [onTap] 三个语义化回调
 *   （调用方在 Compose `detectDragGestures` / `detectTapGestures` 中转发）
 * - app 端 `Scroller` → KMP 版 actual 用 `Animatable<Float>`（Compose Multiplatform 跨平台动画）
 * - app 端 `curPage/prevPage/nextPage` 三个 `PageView` 截图 → KMP 版直接渲染三个 `PageViewComposable`
 *   并用 `Modifier.offset` 控制位置，不再需要 `CanvasRecorder` 截图（Compose 自动 recomposition）
 */
interface PageDelegateShared {

    // region 状态字段（对应 app 端 PageDelegate 的 var 字段）
    /** 是否已移动（手势判定超过 slop 阈值） */
    var isMoved: Boolean

    /** 是否没有下一页 / 上一页（边界标记，阻止动画继续） */
    var noNext: Boolean

    /** 当前翻页方向 */
    var mDirection: PageDirectionShared

    /** 是否取消动画（手势反向判定，松手时回弹到原位） */
    var isCancel: Boolean

    /** 是否正在执行动画 */
    var isRunning: Boolean

    /** 动画是否已启动（与 isRunning 配合区分 scroller 启动 / 停止两阶段） */
    var isStarted: Boolean
    // endregion

    // region 视图尺寸
    /**
     * 设置视图尺寸（对应 app 端 `PageDelegate.setViewSize`）。
     *
     * actual 实现需在尺寸变化时重置内部偏移 / 阴影 drawable 边界等。
     *
     * @param width 视图宽度（px）
     * @param height 视图高度（px）
     */
    fun setViewSize(width: Int, height: Int)
    // endregion

    // region 手势事件（与 app 端 onTouch / onScroll 对应，参数纯 Float 避免 Compose 依赖）

    /**
     * 按下（对应 app 端 `PageDelegate.onDown`）。
     *
     * 重置 [isMoved] / [noNext] / [isRunning] / [isCancel] / [mDirection]，
     * 并记录起始触碰点供 [onScroll] 判定方向。
     *
     * @param x 按下 x（px，相对视图）
     * @param y 按下 y（px，相对视图）
     */
    fun onDown(x: Float, y: Float)

    /**
     * 滑动（对应 app 端 `HorizontalPageDelegate.onScroll`）。
     *
     * 内部维护 startX / lastX，按以下逻辑判定：
     * 1. 首次超过 slop 阈值时设 [isMoved]=true，按 sumX - startX 正负判定 [mDirection]
     *    （正 → PREV，负 → NEXT），并校验 [hasPrev] / [hasNext]
     * 2. 后续滑动按 mDirection 反向移动判定 [isCancel]
     * 3. 更新 [isRunning]=true，把触碰点写入 touchX/touchY 供 [computeScroll] 使用
     *
     * @param x 当前触碰点 x（px）
     * @param y 当前触碰点 y（px）
     */
    fun onScroll(x: Float, y: Float)

    /**
     * 计算滚动动画（对应 app 端 `PageDelegate.computeScroll`）。
     *
     * 由 actual 平台的 `LaunchedEffect` 周期性调用（替代 Android View.invalidate 驱动），
     * 内部推进 `Animatable` / `Scroller` 状态，动画结束时调用 [onAnimStop]。
     *
     * @return true 表示动画进行中，需继续调用；false 表示动画已停止
     */
    fun computeScroll(): Boolean

    /**
     * 单击事件（对应 app 端 `ReadView.onSingleTapUp` 转发到 PageDelegate 的逻辑）。
     *
     * 按 x 位置区分中心菜单 / 左右翻页：
     * - x < viewWidth / 3 → 上一页
     * - x > viewWidth * 2 / 3 → 下一页
     * - 中间区域 → 交由上层处理菜单显隐
     *
     * @param x 点击 x（px）
     * @param y 点击 y（px）
     * @return true 表示已消费事件（不再触发其他 onClick 回调）
     */
    fun onTap(x: Float, y: Float): Boolean

    /**
     * 设置方向（对应 app 端 `PageDelegate.setDirection`）。
     *
     * actual 实现可能在方向变化时触发 setBitmap（截图当前页 / 相邻页）。
     */
    fun setDirection(direction: PageDirectionShared)

    /**
     * 中止当前动画（对应 app 端 `PageDelegate.abortAnim`）。
     *
     * 取消正在执行的 `Animatable` / `Scroller`，重置 [isStarted] / [isMoved] / [isRunning]。
     */
    fun abortAnim()

    /**
     * 动画启动（对应 app 端 `PageDelegate.onAnimStart`）。
     *
     * 根据 [mDirection] 与 [isCancel] 计算目标偏移 distanceX，启动滚动动画。
     *
     * @param animationSpeed 动画速度（与 app 端 defaultAnimationSpeed=300 对应，单位 ms/页宽）
     */
    fun onAnimStart(animationSpeed: Int)

    /**
     * 动画停止（对应 app 端 `PageDelegate.onAnimStop`）。
     *
     * 非取消状态下，调上层 fillPage(direction) 实际翻页。
     */
    fun onAnimStop()
    // endregion

    // region 翻页 API（带动画）
    /** 翻到下一页（带动画） */
    fun nextPageByAnim(animDurationMs: Int)

    /** 翻到上一页（带动画） */
    fun prevPageByAnim(animDurationMs: Int)

    /**
     * 键盘翻页（对应 app 端 `PageDelegate.keyTurnPage`）。
     *
     * [isRunning] 时直接返回；否则按 direction 调用 [nextPageByAnim] / [prevPageByAnim]。
     *
     * @param direction [PageDirectionShared.NEXT] 或 [PageDirectionShared.PREV]
     */
    fun keyTurnPage(direction: PageDirectionShared)

    /**
     * 点击翻页（对应 app 端 `ReadView.click` → `nextPageByAnim(defaultAnimationSpeed)`）。
     *
     * 与 [keyTurnPage] 的按键快速动画区分：点击翻页走常规动画速度
     * （对照原版 ReadView.click → nextPageByAnim(defaultAnimationSpeed)=300ms）。
     *
     * @param direction [PageDirectionShared.NEXT] 或 [PageDirectionShared.PREV]
     */
    fun clickTurnPage(direction: PageDirectionShared)
    // endregion

    // region 章节边界判定（对应 app 端 PageDelegate.hasPrev / hasNext）
    /** 是否有上一页（无上一页时由 actual 弹 toast / 提示） */
    fun hasPrev(): Boolean

    /** 是否有下一页（无下一页时由 actual 弹 toast / 提示） */
    fun hasNext(): Boolean
    // endregion

    // region 生命周期
    /** 释放资源（对应 app 端 `PageDelegate.onDestroy`） */
    fun onDestroy()
    // endregion
}
