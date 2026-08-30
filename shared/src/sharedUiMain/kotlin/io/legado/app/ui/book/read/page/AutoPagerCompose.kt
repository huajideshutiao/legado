package io.legado.app.ui.book.read.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.help.config.currentEInkMode
import io.legado.app.ui.book.read.ReadBookViewModelShared
import io.legado.app.ui.book.read.page.delegate.PageDelegateCompose
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegateCompose
import io.legado.app.utils.systemNanoTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * KMP 版自动翻页控制器（sharedUiMain，对照 app 端 `AutoPager`）。
 *
 * 由宿主（app 端 AndroidReaderMenuState 等）创建并 start/stop；渲染侧
 * [io.legado.app.ui.book.read.page.ReadViewComposable] 经
 * [PageDelegateCompose.autoPager] 取实例绘制揭示动画覆盖层。
 *
 * # 三种模式（对照原版 AutoPager）
 *
 * - **E-Ink**（[currentEInkMode]）：定时整页翻页，每 `autoReadSpeed` 秒一拍，
 *   无动画（原版 `run()` + postDelayed 分支）
 * - **非 E-Ink 翻页模式**（Cover/Slide/Simulation/NoAnim）：clip 揭示动画 +
 *   accent 色 1px 进度线。本控制器只推进 [progress]（0..1，每页耗时 autoReadSpeed 秒），
 *   覆盖层由 ReadViewComposable 绘制（对照原版 AutoPager.onDraw 的
 *   `canvas.withClip(0, 0, width, bottom)` + `drawRect` 进度线）
 * - **滚动模式**（pageAnim=scroll）：连续滚动语义——每拍按经过时间推进小步长位移
 *   （`视口高 / autoReadSpeed 秒` 的速率），经 [PageDelegateCompose.onAutoScrollBy]
 *   走行级 scroll 折算（跨页即时翻页、末页钳制，对照原版 AutoPager 每帧
 *   `curPage.scroll(-scrollOffset)` 的语义），命中硬边界（书首/书末）停止
 *
 * # 暂停/恢复/复位（对照原版 ReadView.onScrollAnimStart/Stop + onPageChange）
 *
 * 手势暂停/恢复钩子挂在 [PageDelegateCompose] 上：手势按下（onDown→abortAnim）时
 * [pause] 暂停推进，动画结束（onAnimStop/惯性结束）时 [resume] 恢复；
 * 手动翻页实际换页后（onAnimStop/abortAnim 的补页分支）[reset] 把揭示进度归零重开。
 * 滚动模式偏移是 delegate 共享状态（单一数据源），手动滚动后自动翻页从当前偏移继续，
 * 与"复位只清控制器内部计数"的原版语义一致。
 *
 * 本控制器不依赖帧时钟，用 delay + systemNanoTime 时间差推进（约 60fps 一拍，
 * 原版 AutoPager.computeOffset 同口径）。
 *
 * # 降级说明
 *
 * - 滚动模式驱动依赖 [viewModel.pageDelegate] 已注入滚动委托；未注入（阅读页未组合）
 *   或尺寸未就绪时退回按时间整页翻，无滑动动画
 * - 手势暂停采用"按下即暂停、动画/惯性结束恢复"（原版为 fling 开始才暂停），
 *   避免拖动过程中自动推进与手势争写滚动偏移
 *
 * @param viewModel 阅读 ViewModel
 * @param scope 宿主协程作用域（app 端 Dispatchers.Main）
 * @param autoReadSpeed 每页耗时（秒），每拍现取（对照原版每次 postDelayed 现读配置）
 */
class AutoPagerCompose(
    private val viewModel: ReadBookViewModelShared,
    private val scope: CoroutineScope,
    private val autoReadSpeed: () -> Int,
) {

    /** 是否运行中（渲染侧据此决定是否绘制揭示覆盖层） */
    var isRunning by mutableStateOf(false)
        private set

    /** 是否暂停推进（手动翻页手势期间由 delegate 钩子置位） */
    var isPausing by mutableStateOf(false)
        private set

    /** E-Ink 模式：定时整页翻，无揭示覆盖层 */
    var isEInkMode by mutableStateOf(false)
        private set

    /** 滚动模式：连续滚动驱动，不走揭示覆盖层 */
    var scrollMode by mutableStateOf(false)
        private set

    /** 揭示动画进度 0..1（非 E-Ink 非滚动模式），覆盖层按页高折算 clip 高度 */
    var progress by mutableFloatStateOf(0f)
        private set

    /** 自动翻页自然停止回调（翻到全书末尾时调用，宿主用它复位菜单状态） */
    var onEnd: (() -> Unit)? = null

    private var job: Job? = null
    private var lastNanos = 0L

    /** 每拍约 60fps（原版 computeScroll/invalidate 驱动同帧率量级） */
    private val frameDelayMs = 16L

    fun start() {
        stop()
        isEInkMode = currentEInkMode()
        scrollMode = viewModel.isScrollPageAnim
        isRunning = true
        isPausing = false
        progress = 0f
        attachToDelegate()
        job = scope.launch { tickLoop() }
    }

    fun stop() {
        if (!isRunning && job == null) {
            return
        }
        isRunning = false
        isPausing = false
        progress = 0f
        job?.cancel()
        job = null
        // 解绑 delegate 钩子，避免残留引用；滚动偏移保持当前位置（对照原版 stop 不清视图偏移）
        val delegate = viewModel.pageDelegate as? PageDelegateCompose
        if (delegate?.autoPager === this) {
            delegate.autoPager = null
            delegate.onAutoScrollEnd()
        }
    }

    /** 暂停推进（手动翻页手势期间调用；渲染保持当前画面） */
    fun pause() {
        if (!isRunning) return
        isPausing = true
    }

    /** 恢复推进；重置时间基准，暂停时长不计入进度（对照原版 resume 重置 lastTimeMillis） */
    fun resume() {
        if (!isRunning) return
        isPausing = false
        lastNanos = systemNanoTime()
    }

    /** 揭示进度归零重开（对照原版 onPageChange → autoPager.reset；手动翻页换页后由 delegate 调用） */
    fun reset() {
        if (isEInkMode) return
        progress = 0f
        lastNanos = systemNanoTime()
    }

    /** 每拍重挂 delegate（配置变更重建委托后自动恢复钩子与滚动驱动） */
    private fun attachToDelegate() {
        val delegate = viewModel.pageDelegate as? PageDelegateCompose ?: return
        if (delegate.autoPager !== this) {
            delegate.autoPager = this
        }
    }

    private suspend fun CoroutineScope.tickLoop() {
        lastNanos = systemNanoTime()
        if (isEInkMode) {
            eInkLoop()
        } else {
            smoothLoop()
        }
    }

    /** E-Ink：定时整页翻（对照原版 run() + postDelayed 分支） */
    private suspend fun CoroutineScope.eInkLoop() {
        while (isActive) {
            delay(autoReadSpeedMs())
            if (!isRunning) return
            if (isPausing) continue
            if (!turnPage()) return
        }
    }

    /** 非 E-Ink：按时间差推进揭示/滚动进度（对照原版 computeOffset 的像素/时间换算） */
    private suspend fun CoroutineScope.smoothLoop() {
        while (isActive) {
            delay(frameDelayMs)
            if (!isRunning) return
            attachToDelegate()
            val now = systemNanoTime()
            val elapsedMs = (now - lastNanos) / 1_000_000
            lastNanos = now
            if (isPausing) continue
            // 每页耗时 autoReadSpeed 秒 → 归一化步长
            val step = elapsedMs.toFloat() / autoReadSpeedMs()
            if (scrollMode) {
                // 切章装载守卫（对照 [turnPage]）：正文未跟上 durChapterIndex 时暂停推进，
                // 避免把未装载的占位章滚过去（滚动折算内部会跨章）
                val cur = viewModel.curTextChapter.value
                if (cur == null || cur.chapterIndex != viewModel.durChapterIndex.value) continue
                val delegate = viewModel.pageDelegate as? PageDelegateCompose
                val height = delegate?.autoScrollHeight ?: 0
                if (delegate !is ScrollPageDelegateCompose || height <= 0) {
                    // 滚动委托未就绪/非滚动委托（阅读页未组合、尺寸未定或配置变更）：
                    // 按时间整页翻兜底
                    progress += step
                    while (progress >= 1f) {
                        progress -= 1f
                        if (!turnPage()) return
                    }
                    continue
                }
                // 每拍推进 视口高/页时 的位移，行级折算/跨页/钳制由 delegate 完成
                // （对照原版 scrollOffsetRemain += height / readTime * elapsed → curPage.scroll）
                if (!delegate.onAutoScrollBy(height * step)) {
                    // 命中硬边界（书首/书末）：停止（对照原版边界分支 abortAnim + 自然停）
                    stop()
                    onEnd?.invoke()
                    return
                }
            } else {
                progress += step
                while (progress >= 1f) {
                    progress -= 1f
                    if (!turnPage()) return
                }
            }
        }
    }

    /** 翻页（对照原版 fillPage(NEXT)：章末由 moveToNextChapter 跨章，末章停止） */
    private fun turnPage(): Boolean {
        // 切章后正文装载中（curTextChapter 未跟上 durChapterIndex）：不翻页等下一拍，
        // 避免把未装载的占位页跳过去（对照 app 端 startAutoPage 的装载守卫）
        val cur = viewModel.curTextChapter.value
        if (cur == null || cur.chapterIndex != viewModel.durChapterIndex.value) return true
        if (viewModel.nextPage()) return true
        if (viewModel.moveToNextChapter()) return true
        // 全书末尾：复位状态再通知宿主（对照原版 fillPage 返回 false → autoPager.stop()）
        stop()
        onEnd?.invoke()
        return false
    }

    private fun autoReadSpeedMs(): Long = autoReadSpeed().coerceAtLeast(1) * 1000L
}
