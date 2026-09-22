package io.legado.app.ui.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteTransitionState] / [RouteTransitionSegment] 的契约测试。
 *
 * 守住的契约:
 * 1. 静止态组合整条栈 (被盖页留在组合中): 被盖页的页面级副作用与 UI 节点树不随压栈销毁,
 *    返回时零重建。这是共享元素回程飞行与页面订阅存活的前提。
 * 2. 转场段内所有参与页 (含正在离场的) 都在组合中。
 * 3. 角色判定与播放方向无关 (倒放只沿同一段轨迹退回, 不改角色)。
 * 4. [RouteTransitionState.resolve] 是纯函数: 同一输入恒等输出, 不修改状态。
 */
class RouteTransitionStateTest {

    private fun entry(id: Long) = RouteEntry(
        id = RouteEntryId(id),
        route = AppRoute.Main(),
    )

    private fun segment(
        from: List<RouteEntry>,
        to: List<RouteEntry>,
        reversing: Boolean = false,
    ) = RouteTransitionSegment(from = from, to = to, reversing = reversing)

    @Test
    fun `settled segment keeps whole stack composed`() {
        val a = entry(1)
        val b = entry(2)
        val c = entry(3)
        val stack = listOf(a, b, c)

        val settled = segment(stack, stack)

        // 静止态两端相等: 整条栈都参与组合 (被盖页保留组合树与副作用)
        assertEquals(stack, settled.displayEntries)
    }

    @Test
    fun `settled single entry stack composes that entry`() {
        val a = entry(1)
        val settled = segment(listOf(a), listOf(a))

        assertEquals(listOf(a), settled.displayEntries)
    }

    @Test
    fun `forward segment composes all target entries`() {
        val a = entry(1)
        val b = entry(2)
        val push = segment(from = listOf(a), to = listOf(a, b))

        assertEquals(listOf(a, b), push.displayEntries)
    }

    @Test
    fun `pop segment keeps outgoing page composed until animation ends`() {
        val a = entry(1)
        val b = entry(2)
        val pop = segment(from = listOf(a, b), to = listOf(a))

        // 出栈页仍须在组合中播完滑出, 否则返回时会先空一格
        assertEquals(listOf(a, b), pop.displayEntries)
    }

    @Test
    fun `overOutgoing segment keeps dropped page below new top`() {
        val a = entry(1)
        val b = entry(2)
        val c = entry(3)
        // 单段前进: 详情(a,b) → 阅读(a,c), 目录 b 被弹出
        val overOutgoing = segment(from = listOf(a, b), to = listOf(a, c))

        assertTrue(overOutgoing.overOutgoing)
        // 新页 (c) 必须在最后: 前进的 z 序要求新页在上
        assertEquals(c, overOutgoing.displayEntries.last())
        // 出栈页 (b) 也留在组合里
        assertTrue(overOutgoing.displayEntries.contains(b))
        assertEquals(3, overOutgoing.displayEntries.size)
    }

    @Test
    fun `reversing segment keeps from stack plus retreating entries`() {
        val a = entry(1)
        val b = entry(2)
        val reversing = segment(from = listOf(a), to = listOf(a, b), reversing = true)

        assertEquals(listOf(a, b), reversing.displayEntries)
    }

    @Test
    fun `roles are properties of the segment not the playback direction`() {
        val a = entry(1)
        val b = entry(2)
        val forward = segment(from = listOf(a), to = listOf(a, b))
        val reversed = forward.copy(reversing = true)

        // 同一段轨迹, 正放与倒放必须给出同一份角色, 倒放首帧才与正放当前帧位移连续
        assertEquals(forward.roleOf(a), reversed.roleOf(a))
        assertEquals(forward.roleOf(b), reversed.roleOf(b))
    }

    @Test
    fun `forward segment roles are new page and old page`() {
        val a = entry(1)
        val b = entry(2)
        val push = segment(from = listOf(a), to = listOf(a, b))

        assertEquals(TransitionRole.NewPage, push.roleOf(b))
        assertEquals(TransitionRole.OldPage, push.roleOf(a))
    }

    @Test
    fun `pop segment roles are target page and outgoing page`() {
        val a = entry(1)
        val b = entry(2)
        val pop = segment(from = listOf(a, b), to = listOf(a))

        assertEquals(TransitionRole.TargetPage, pop.roleOf(a))
        assertEquals(TransitionRole.OutgoingPage, pop.roleOf(b))
    }

    @Test
    fun `resolve returns null when settled`() {
        val a = entry(1)
        val state = RouteTransitionState(listOf(a))

        assertNull(state.resolve(listOf(a)))
    }

    @Test
    fun `resolve is pure and does not mutate state`() {
        val a = entry(1)
        val b = entry(2)
        val state = RouteTransitionState(listOf(a))

        val first = state.resolve(listOf(a, b))
        val second = state.resolve(listOf(a, b))

        assertNotNull(first)
        // 纯函数: 同一输入恒等输出, 且不改变已应用的段
        assertEquals(first, second)
        assertEquals(listOf(a), state.segment.to)
    }

    @Test
    fun `resolve detects reversal back to segment start`() {
        val a = entry(1)
        val b = entry(2)
        val state = RouteTransitionState(listOf(a))
        val push = requireNotNull(state.resolve(listOf(a, b)))
        state.applySegment(push)

        // 进场动画中途按返回: 页面栈退回本段起点 → 沿本段轨迹倒放
        val reversal = requireNotNull(state.resolve(listOf(a)))
        assertTrue(reversal.reversing)
        assertEquals(push.from, reversal.from)
        assertEquals(push.to, reversal.to)
    }

    @Test
    fun `animating follows segment settlement`() {
        val a = entry(1)
        val b = entry(2)
        val state = RouteTransitionState(listOf(a))

        assertTrue(state.animating(listOf(a, b)))
        state.applySegment(requireNotNull(state.resolve(listOf(a, b))))
        assertTrue(state.animating(listOf(a, b)))
    }

    @Test
    fun `settle collapses segment and resets progress`() {
        val a = entry(1)
        val b = entry(2)
        val state = RouteTransitionState(listOf(a))
        state.applySegment(requireNotNull(state.resolve(listOf(a, b))))

        kotlinx.coroutines.runBlocking { state.settle(listOf(a, b)) }

        assertEquals(listOf(a, b), state.segment.from)
        assertEquals(listOf(a, b), state.segment.to)
        assertEquals(1f, state.progress.value, 0f)
        assertNull(state.resolve(listOf(a, b)))
    }
}
