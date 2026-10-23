package io.legado.app.help.tts

import io.legado.app.constant.AppLog
import io.legado.app.model.POSITION_PENDING_PAGE
import io.legado.app.model.PositionOrigin
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudPlanBuilder
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 章末等章的共享协调器: 把"等待正文 → 请求装载 → 拿到答复 → 续读或上报"这条链路收在一处。
 *
 * 跨平台朗读宿主与 Android 朗读服务都用这一份, 平台侧只提供 [ReadAloudHostPorts] 的事实
 * (正文是否到手、装载请求、装载答复、活动阅读页是否存在), 不再各自维护监听与重试。
 */
class ReadAloudChapterCoordinator(
    private val ports: ReadAloudHostPorts,
    private val controllerProvider: () -> ReadAloudControllerShared,
) {

    private val controller get() = controllerProvider()

    /** 已处理过的章节更新: 同一更新不重复驱动续读。 */
    private val handledUpdate = atomic<ReadAloudChapterUpdate?>(null)

    /** 续读互斥: 排版就绪与装载答复可能同时到达, 不能并发重建会话。 */
    private val retrying = atomic(false)

    /**
     * 当前朗读段在章内占据的字符区间。
     *
     * 几何一次取值: 段起点与裁剪偏移必须同源, 逐字段读会取到跨版本混合值。
     */
    fun paragraphRange(paragraphIndex: Int, geometry: ReadAloudPlanBuilder.Geometry): IntRange {
        if (paragraphIndex < 0) return IntRange.EMPTY
        val real = geometry.paragraphBase + paragraphIndex
        val start = geometry.paragraphStart(real) ?: return IntRange.EMPTY
        val length = geometry.paragraphs.getOrNull(real)?.length ?: return IntRange.EMPTY
        return start until (start + length + 1)
    }

    /**
     * 段落切换: 计算段起点 (几何一次取值, 段起点与裁剪偏移同源), 发布进度并联动翻页。
     *
     * 对照原版 `BaseReadAloudService.upTtsProgress` 的段级分支: 进度按段落点发布,
     * 阅读页的 TTS_PROGRESS 订阅负责写 durChapterPos 与朗读高亮。
     */
    fun syncParagraphStart(index: Int, geometry: ReadAloudPlanBuilder.Geometry) {
        if (index < 0) return
        val chapterIndex = controller.chapterIndex.value
        val position = ports.currentPosition ?: return
        if (position.chapterIndex != chapterIndex) return
        val real = geometry.paragraphBase + index
        var pos = geometry.paragraphStart(real) ?: return
        if (index == 0) pos += geometry.firstParagraphSkip
        ports.advanceReadAloudPage(chapterIndex, pos)
        ports.positionPublisher.publishPosition(pos)
    }

    /** 字级推进: 按念到的字发布进度并联动翻页, 长段跨页才能及时翻过去。 */
    fun syncSpeakPosition(pos: Int) {
        val chapterIndex = controller.chapterIndex.value
        val position = ports.currentPosition ?: return
        if (position.chapterIndex != chapterIndex) return
        ports.advanceReadAloudPage(chapterIndex, pos)
        ports.positionPublisher.publishPosition(pos)
    }

    /** 起监听: 装载答复与章节排版更新合并看。可重复调用, 已在跑时不会重建。 */
    suspend fun startWatch() {
        coroutineScope {
            launch {
                ports.chapterSignals.collect { signal -> onSignal(signal) }
            }
            launch {
                controller.chapterIndex.collect { index ->
                    if (index >= 0) {
                        // 进入一章就备好下一章正文: 章末换章不该再等一次网络往返
                        ports.prefetchChapter(index + 1)
                    }
                }
            }
            ports.chapterUpdates.collect { update -> onChapterUpdate(update) }
        }
    }

    /**
     * 位置跟随: 阅读位置被挪出当前朗读段时跟过去, 自己驱动的那几帧不跟。
     *
     * @param paragraphRange 当前朗读段在章内的字符区间
     * @param onFollow 需要跟随时的回调, 由平台决定如何重建
     * @param onPauseMove 暂停态下阅读位置被挪走时的回调, 由平台暂存
     */
    fun onPosition(
        position: ReadAloudPosition,
        paragraphRange: () -> IntRange,
        onFollow: (ReadAloudPosition) -> Unit,
        onPauseMove: (ReadAloudPosition) -> Unit,
    ) {
        val state = controller.state.value
        // 回上一章且该章未预载时位置是待定值 (排版完成后才归一为末页页首), 不可当真位置用
        if (position.chapterPosition == POSITION_PENDING_PAGE) return
        if (state == ReadAloudState.PAUSED) {
            if (position.chapterIndex != controller.chapterIndex.value ||
                position.chapterPosition !in paragraphRange()
            ) {
                onPauseMove(position)
            }
            return
        }
        if (state != ReadAloudState.PLAYING) {
            // 等章期间用户翻到别的章: 跟着过去 —— 那本就是他要听的地方
            if (state == ReadAloudState.WAITING &&
                position.chapterIndex != controller.chapterIndex.value &&
                position.origin == PositionOrigin.User
            ) {
                onFollow(position)
            }
            return
        }
        if (position.chapterIndex != controller.chapterIndex.value) {
            // 阅读页已到别的章: 朗读跟过去 (朗读自己换章时章号先变, 不会走到这里)
            onFollow(position)
            return
        }
        // 朗读自己推进写入的位置: 那正是朗读所在段, 不需跟随
        if (position.origin == PositionOrigin.ReadAloud) return
        // 用户写的同一段内位置 (或页内小范围拖动): 段区间内不重开
        if (position.chapterPosition in paragraphRange()) return
        onFollow(position)
    }

    /** 会话每次进入等待就调一次: 按当前事实对齐, 不轮询。 */
    fun onWaiting() {
        val chapterIndex = controller.chapterIndex.value
        if (chapterIndex < 0) return
        // 每次真正进入等待都重新评估: 同一章第二次等待不该被上次的信号去重挡住
        handledUpdate.value = null
        if (!ports.chapterSourceAvailable) {
            controller.reportError("朗读需要阅读页提供正文, 当前无活动阅读页")
            return
        }
        if (ports.isChapterTextReady(chapterIndex)) {
            retryByCurrentChapter(chapterIndex)
            return
        }
        AppLog.putDebug("朗读等待第 ${chapterIndex + 1} 章正文")
        ports.prefetchChapter(chapterIndex)
    }

    /** 装载结束或阅读实例已换: 能建出计划就续读, 建不出就上报。 */
    private fun onSignal(signal: ReadAloudChapterSignal) {
        if (controller.state.value != ReadAloudState.WAITING) return
        when (signal) {
            ReadAloudChapterSignal.SourceSwitched -> onWaiting()
            is ReadAloudChapterSignal.Settled -> {
                if (signal.chapterIndex == controller.chapterIndex.value) {
                    retryByCurrentChapter(signal.chapterIndex)
                }
            }
        }
    }

    /** 章节排版更新: 只停着等章时才有意义; 同一更新只处理一次。 */
    private fun onChapterUpdate(update: ReadAloudChapterUpdate) {
        if (controller.state.value != ReadAloudState.WAITING) return
        if (update.chapterIndex != controller.chapterIndex.value) return
        if (handledUpdate.getAndSet(update) == update) return
        if (update.error != null) {
            controller.reportError(update.error)
            return
        }
        retryByCurrentChapter(update.chapterIndex)
    }

    /**
     * 按当前章续读; 建不出计划且仍停在等待时先看控制器是否已顺延到别的章,
     * 顺延到的那一章只是还没装载, 不能判成读不了。
     */
    private fun retryByCurrentChapter(chapterIndex: Int) {
        // 用户已翻到别的章: 不续读这一章 (续读会把阅读页拽回原章), 也不判它读不了
        val readingChapter = ports.currentPosition?.chapterIndex
        if (readingChapter != null && readingChapter != chapterIndex) return
        if (!retrying.compareAndSet(false, true)) return
        try {
            var index = chapterIndex
            while (true) {
                if (controller.retryWaiting(index)) return
                if (controller.state.value != ReadAloudState.WAITING) return
                val current = controller.chapterIndex.value
                if (current < 0) return
                if (current == index) {
                    controller.reportError("第 ${index + 1} 章无法朗读")
                    return
                }
                // 已顺延到新章: 同一把锁内接着评估新章, 不能递归
                index = current
                if (!ports.isChapterTextReady(index)) {
                    AppLog.putDebug("朗读等待第 ${index + 1} 章正文")
                    ports.prefetchChapter(index)
                    return
                }
            }
        } finally {
            retrying.value = false
        }
    }
}
