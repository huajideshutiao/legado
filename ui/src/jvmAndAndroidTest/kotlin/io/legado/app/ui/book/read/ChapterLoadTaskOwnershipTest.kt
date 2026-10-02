package io.legado.app.ui.book.read

import io.legado.app.model.chapter.ChapterLoadingGuard
import io.legado.app.model.chapter.ChapterWindowSlot
import io.legado.app.model.chapter.chapterWindowSlotOf
import io.legado.app.model.chapter.isInChapterWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节装载任务的所有权契约（F7 保偏移 / F8 完成回调的公共机制）。
 *
 * 「保偏移」与「排版完成回调」不再走跨任务簿记，而是随本次装载任务传递，正确性依赖三条：
 * 1. 待办动作只在**它自己那次装载**跑完时执行；
 * 2. 该次装载被同章新任务替换时，旧任务的完成动作作废（不触发、也不影响新任务）；
 * 3. 装载失败时完成动作不执行，且不残留给同章的下一次装载。
 *
 * 测试直接驱动 [ChapterLoadingGuard] 的真实语义（装载权、任务身份、同章替换），
 * 复刻 `loadContent` 的判定顺序。
 */
class ChapterLoadTaskOwnershipTest {

    /** 复刻 `loadContent` 的装载段落：抢装载权 → 记任务身份 → 内容就绪 → 消费本次任务的意图。 */
    private class LoadOutcome {
        var keepScrollOffset: Boolean? = null
        var callbackCount = 0
    }

    private suspend fun CoroutineScope.loadLike(
        guard: ChapterLoadingGuard,
        index: Int,
        keepScrollOffset: Boolean,
        success: (() -> Unit)?,
        contentReady: CompletableDeferred<Unit>,
        started: CompletableDeferred<Unit>,
        outcome: LoadOutcome,
    ) {
        if (!guard.tryAdd(index)) return
        val loadJob = coroutineContext[Job]
        started.complete(Unit)
        try {
            contentReady.await()
            // 本次装载已被同章新任务替换: 旧装载不得消费自己的意图, 也不得触发完成动作
            if (!guard.isCurrentJob(index, loadJob)) return
            guard.release(index)
            outcome.keepScrollOffset = keepScrollOffset
            if (success != null && guard.isCurrentJob(index, loadJob)) {
                success()
                outcome.callbackCount++
            }
        } finally {
            guard.release(index)
        }
    }

    @Test
    fun `同章新任务替换旧任务时旧任务的完成回调与保偏移意图都不生效`() = runBlocking {
        val guard = ChapterLoadingGuard(this)
        val oldReady = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val oldOutcome = LoadOutcome()
        var newCallbackCount = 0
        var newKeepScrollOffset: Boolean? = null

        val oldJob = guard.launch(3) {
            loadLike(guard, 3, keepScrollOffset = true, success = { }, contentReady = oldReady,
                started = oldStarted, outcome = oldOutcome)
        }
        oldStarted.await()

        // 同章新任务替换旧任务 (loadGuard.launch 语义: 登记新任务并取消旧任务)
        guard.launch(3) {
            if (!guard.tryAdd(3)) return@launch
            val job = coroutineContext[Job]
            if (guard.isCurrentJob(3, job)) {
                newKeepScrollOffset = false
                newCallbackCount++
            }
            guard.release(3)
        }.join()

        // 旧装载此刻才拿到内容: 它必须发现已被替换并整体作废
        oldReady.complete(Unit)
        oldJob.join()

        assertNull("旧任务的完成回调必须作废", oldOutcome.keepScrollOffset)
        assertEquals("旧任务不得触发完成动作", 0, oldOutcome.callbackCount)
        assertEquals("新任务的意图生效", false, newKeepScrollOffset)
        assertEquals("新任务的完成动作触发", 1, newCallbackCount)
    }

    @Test
    fun `装载失败不执行完成动作且同章后续装载不继承`() = runBlocking {
        val guard = ChapterLoadingGuard(this)
        val failedOutcome = LoadOutcome()
        val laterOutcome = LoadOutcome()

        // 失败装载: 内容阶段抛错, 完成动作不执行
        guard.launch(5) {
            if (!guard.tryAdd(5)) return@launch
            val job = coroutineContext[Job]
            try {
                withContext(Dispatchers.Default) { throw IllegalStateException("download failed") }
            } catch (_: IllegalStateException) {
                // 失败路径: 完成动作不执行, 不留下任何待办
            } finally {
                guard.release(5)
            }
            assertTrue("标记释放不代表任务已经结束", guard.isCurrentJob(5, job))
        }.join()

        // 同章后续普通装载: 只带自己的意图, 不继承失败那次
        guard.launch(5) {
            loadLike(guard, 5, keepScrollOffset = false, success = { }, contentReady = CompletableDeferred(Unit),
                started = CompletableDeferred(Unit), outcome = laterOutcome)
        }.join()

        assertNull(failedOutcome.keepScrollOffset)
        assertEquals(0, failedOutcome.callbackCount)
        assertEquals("后续装载只消费自己的意图", false, laterOutcome.keepScrollOffset)
        assertEquals("后续装载带自己的完成动作", 1, laterOutcome.callbackCount)
    }

    @Test
    fun `保偏移意图随任务参数传递不跨任务共享`() = runBlocking {
        val guard = ChapterLoadingGuard(this)
        val crossing = LoadOutcome()
        val directoryUpdate = LoadOutcome()

        guard.launch(2) {
            loadLike(guard, 2, keepScrollOffset = true, success = null, contentReady = CompletableDeferred(Unit),
                started = CompletableDeferred(Unit), outcome = crossing)
        }.join()
        // 同章后续装载 (目录更新后整窗重载): 取自己的参数, 不继承上一次的保偏移
        guard.launch(2) {
            loadLike(guard, 2, keepScrollOffset = false, success = null, contentReady = CompletableDeferred(Unit),
                started = CompletableDeferred(Unit), outcome = directoryUpdate)
        }.join()

        assertEquals("滚动连续跨章装载保偏移", true, crossing.keepScrollOffset)
        assertEquals("目录更新后的重载归零偏移", false, directoryUpdate.keepScrollOffset)
    }

    @Test
    fun `已预载章不经过装载且窗口槽位判定正确`() {
        // 保偏移意图只随「真接手了占位页的那次装载」传递; 该章已排版时切章直接
        // applyCurChapterPages(resetOffset), 不经过装载, 因此不存在跨任务待办
        assertEquals(ChapterWindowSlot.CUR, chapterWindowSlotOf(4, 4))
        assertEquals(ChapterWindowSlot.NEXT, chapterWindowSlotOf(5, 4))
        assertEquals(ChapterWindowSlot.PREV, chapterWindowSlotOf(3, 4))
        assertTrue(isInChapterWindow(5, 4))
        assertFalse("窗口外的装载结果必须丢弃", isInChapterWindow(6, 4))
    }

    @Test
    fun `launchIfNoJob在任务在途时原子放弃且收尾后可再启`() = runBlocking {
        val guard = ChapterLoadingGuard(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = guard.launchIfNoJob(1) {
            started.complete(Unit)
            release.await()
        }
        assertNotNull(first)
        started.await()
        assertNull("任务在途时不得启动 (查与启原子, 无替换窗口)", guard.launchIfNoJob(1) {})
        assertEquals(first, guard.currentJob(1))
        release.complete(Unit)
        first!!.join()
        assertNull("任务收尾后不再登记", guard.currentJob(1))
        assertNotNull(guard.launchIfNoJob(1) {})
    }

    @Test
    fun `装载标记释放后任务仍登记到收尾`() = runBlocking {
        val guard = ChapterLoadingGuard(this)
        val released = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = guard.launch(2) {
            assertTrue(guard.tryAdd(2))
            // 对照 loadContent: 释放装载标记后排版/收尾仍在继续
            guard.release(2)
            released.complete(Unit)
            finish.await()
        }
        released.await()
        assertEquals("排版/收尾阶段任务仍可查 (launchRelayout 的跳过依据)", job, guard.currentJob(2))
        finish.complete(Unit)
        job.join()
        assertNull(guard.currentJob(2))
    }
}
