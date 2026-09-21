package io.legado.app.ui.book.manga.render

/**
 * GIF「播完一轮自动翻页」状态机 (原 `MangaPageImageView.onAnimEnded` 与 skiko
 * `MangaAnimatedImageRenderer.onFrameLoopFinished` 的合流，一份实现两端共用)。
 *
 * 平台只提供三个原子操作：
 * - [enterPlayOnce]：设为单轮播放（播完一轮触发结束回调）
 * - [enterLoopForever]：设为无限循环（结束回调永不触发）
 * - [restartFromFirstFrame]：从第一帧重播
 *
 * 播完回调由平台在动画结束时调 [onPlayOnceFinished]，其余流程（是否翻页、翻页受阻重播、
 * 滑走恢复循环）全部由本机判定，与各端渲染方式无关。
 */
abstract class MangaGifAutoNextPlayer {

    /** 是否启用播完翻页（横向模式且设置开启） */
    var enabled: () -> Boolean = { false }

    /** 此页是否为当前停稳的居中页（播完翻页只装填当前页） */
    var isArmTarget: () -> Boolean = { false }

    /** 触发翻到下一页，返回是否真的翻动（受阻时自首帧重播下轮再试） */
    var onTurnPage: () -> Boolean = { false }

    // 是否处于单轮装填态；armed 时播完回调才参与翻页判定
    private var armed = false

    // 本次停留期间是否已翻过一次，避免无限循环下每播完一轮就重复翻页
    private var turnConsumed = false

    /** 新图就绪：复位为无限循环并从首帧播（覆盖「停稳回调先于加载完成」的时序） */
    fun onNewImageLoaded() {
        armed = false
        turnConsumed = false
        enterLoopForever()
        restartFromFirstFrame()
    }

    /** 加载完成时是否应立即装填单轮（此页已是停稳的居中页，如直接打开到此页） */
    fun shouldArmOnLoaded(): Boolean = enabled() && isArmTarget()

    /** 此页停稳为当前居中页：设单轮并从首帧播放，播完一轮触发 [onPlayOnceFinished] */
    fun playForCurrentPage() {
        if (!enabled()) return
        armed = true
        turnConsumed = false
        enterPlayOnce()
        // 必须重置到第一帧：此页在居中前可能已可见而"偷偷"播放过若干帧甚至播完
        restartFromFirstFrame()
    }

    /** 此页离开当前居中位置、或关闭 GIF 自动翻页：恢复无限循环（不重置当前帧） */
    fun stopAutoNext() {
        armed = false
        enterLoopForever()
    }

    /**
     * 单轮播完（armed 时由平台动画结束回调调用）：
     * - 翻页成功：记 turnConsumed，恢复无限循环避免停在末帧
     * - 播放途中被滑走或本次停留已翻过：恢复无限循环继续播
     * - 翻页受阻（已到末页/下一章加载中）：保持单轮，从首帧重播，下一轮再试
     */
    fun onPlayOnceFinished() {
        if (!armed) return
        val turned = isArmTarget() && !turnConsumed && onTurnPage()
        if (turned) turnConsumed = true
        if (turned || !isArmTarget()) {
            armed = false
            enterLoopForever()
        }
        restartFromFirstFrame()
    }

    protected abstract fun enterPlayOnce()
    protected abstract fun enterLoopForever()
    protected abstract fun restartFromFirstFrame()
}
