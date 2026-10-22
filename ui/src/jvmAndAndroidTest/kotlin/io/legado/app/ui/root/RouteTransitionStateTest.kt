package io.legado.app.ui.root

import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [RouteTransitionState] / [RouteTransitionSegment] 的契约测试。
 *
 * 守住的契约:
 * 1. 静止态组合整条栈 (被盖页留在组合中): 被盖页的页面级副作用与 UI 节点树不随压栈销毁,
 *    返回时零重建。这是共享元素回程飞行与页面订阅存活的前提。
 * 2. 转场段内所有参与页 (含正在离场的那一页) 都在组合中; 一次移除多页时只有栈顶那页
 *    播退场动画, 其余直接离场。
 * 3. 角色判定与播放方向无关 (倒放只沿同一段轨迹退回, 不改角色)。
 * 4. [RouteTransitionState.resolve] 是纯函数: 同一输入恒等输出, 不修改状态。
 * 5. 跟手返回手势 (待出栈段): 页面栈在手势期间不动, 段的两端与真实栈的关系由
 *    [RouteTransitionState.beginInteractiveBack] / [RouteTransitionState.dragInteractiveBack] /
 *    [RouteTransitionState.cancelInteractiveBack] 维护; 出栈后 [RouteTransitionState.resolve] 必须
 *    原样返回同一份段 (动画驱动据此不回跳); 页面栈被外部改动后本段作废, 写入与取消都不再作用
 *    于它 (见 [RouteTransitionState.isInteractiveBackPending])。
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

    /** 取消收尾用的作用域: Unconfined 让收尾在 launch 调用内就跑完, 断言不必等待调度。 */
    private fun cleanupScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

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
    fun `multi page removal exits only the top page`() {
        val a = entry(1)
        val b = entry(2)
        val c = entry(3)
        // 单例页复用: 一次清掉两层, 只让栈顶那页播退场, 其余直接离场
        val segment = segment(from = listOf(a, b, c), to = listOf(a))

        assertEquals(listOf(a, c), segment.displayEntries)
        assertEquals(TransitionRole.TargetPage, segment.roleOf(a))
        assertEquals(TransitionRole.OutgoingPage, segment.roleOf(c))
        assertNull(segment.roleOf(b))
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
    fun `settled segment roles hide every non top page`() {
        val a = entry(1)
        val b = entry(2)
        val c = entry(3)
        val settled = segment(listOf(a, b, c), listOf(a, b, c))

        // 静止态没有让位/进场: 非栈顶页不参与本段 (移出屏幕 + alpha=0 + clip),
        // 否则倒数第二页拿 OldPage, 仍铺在屏幕上并抢走栈顶页空白处的指针事件
        assertEquals(TransitionRole.NewPage, settled.roleOf(c))
        assertNull(settled.roleOf(a))
        assertNull(settled.roleOf(b))
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

        runBlocking { state.settle(listOf(a, b)) }
        assertEquals(listOf(a, b), state.segment.from)
        assertEquals(listOf(a, b), state.segment.to)
        assertEquals(1f, state.progress.value, 0f)
        assertNull(state.resolve(listOf(a, b)))
    }

    @Test
    fun `interactive back begins pending pop segment`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)

        assertTrue(state.beginInteractiveBack(stack, 0.25f))

        // 段 = "待出栈": 终点是当前栈去掉栈顶, 页面栈本身没变
        assertEquals(stack, state.segment.from)
        assertEquals(listOf(a), state.segment.to)
        assertEquals(0.25f, state.progress.value, 0f)
        // 组合期用的是 resolve 返回的倒放副本, 角色必须与普通返回段一致 (目标页 + 出栈页),
        // 两页都在场 —— 跟手到任意进度都不会空屏
        val rendered = requireNotNull(state.resolve(stack))
        assertEquals(listOf(a, b), rendered.displayEntries)
        assertEquals(TransitionRole.TargetPage, rendered.roleOf(a))
        assertEquals(TransitionRole.OutgoingPage, rendered.roleOf(b))
        assertEquals(state.segment.roleOf(a), rendered.roleOf(a))
        assertEquals(state.segment.roleOf(b), rendered.roleOf(b))
        // 栈没变但段未落定: 仍算转场中 (转场圆角/状态栏冻结跟着手走), 且按真实进度渲染
        assertTrue(state.animating(stack))
        assertTrue(state.rendersLiveProgress(stack))
        assertTrue(state.isInteractiveBackPending(stack))
    }

    @Test
    fun `interactive back refuses root stack and unsettled segment`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val state = RouteTransitionState(listOf(a))

        // 栈内不足两页: 没有可返回的上一页
        assertFalse(state.beginInteractiveBack(listOf(a), 0.2f))
        assertFalse(state.isInteractiveBackPending(listOf(a)))

        // 上一段 (前进动画) 尚未落定: 不接管, 避免两段抢同一份进度
        state.applySegment(requireNotNull(state.resolve(listOf(a, b))))
        assertFalse(state.beginInteractiveBack(listOf(a, b), 0.2f))
        assertEquals(listOf(a), state.segment.from)
    }

    @Test
    fun `interactive back drag writes progress while stack matches`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        // 起手进度越界也钳制
        state.beginInteractiveBack(stack, -0.3f)
        assertEquals(0f, state.progress.value, 0f)

        state.dragInteractiveBack(stack, 0.6f)
        assertEquals(0.6f, state.progress.value, 0f)

        // 手指可以拖出视图边界, 进度钳制在 0f~1f
        state.dragInteractiveBack(stack, 1.4f)
        assertEquals(1f, state.progress.value, 0f)

        // 栈被别处改动: 本段作废, 写入忽略, 取消也不再把它当自己的段来弹
        state.dragInteractiveBack(listOf(a), 0.1f)
        state.cancelInteractiveBack(listOf(a), durationMillis = 300, easing = LinearEasing)
        assertEquals(1f, state.progress.value, 0f)
        // 本段仍然挂在原来的栈上 (取消没有抢走它)
        assertTrue(state.isInteractiveBackPending(stack))
        assertNotNull(state.resolve(stack))
    }

    @Test
    fun `interactive back commit continues from dragged progress`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        state.beginInteractiveBack(stack, 0.5f)

        // 松手判定完成 → 调用方真的出栈: 段两端已与真实栈一致, resolve 必须原样返回同一份段
        // (动画驱动据此判定"已在途中"而不把进度归零), 且仍按真实进度渲染
        val committed = listOf(a)
        assertEquals(state.segment, state.resolve(committed))
        assertTrue(state.rendersLiveProgress(committed))
        // 出栈已提交, 本段不再算"正在跟手"
        assertFalse(state.isInteractiveBackPending(committed))

        state.settle(committed)
        assertEquals(1f, state.progress.value, 0f)
        assertNull(state.resolve(committed))
    }

    @Test
    fun `interactive back cancel settles back to unchanged stack`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        state.beginInteractiveBack(stack, 0.3f)
        // 手指拖回起点后松手: 框架判定取消, 剩余路程为 0 时直接落定
        state.dragInteractiveBack(stack, 0f)

        state.cancelInteractiveBack(stack, durationMillis = 300, easing = LinearEasing)

        assertEquals(1f, state.progress.value, 0f)
        assertEquals(stack, state.segment.from)
        assertNull(state.resolve(stack))
    }

    @Test
    fun `interactive driver ignores gesture without drag and returns on drag`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        var completed = false

        // 空流 = 边缘单击 (框架把识别器失败也当完成上报): 不是返回手势, 状态机不被写坏
        val emptyWasGesture = state.driveInteractiveBack(
            gesture = emptyFlow(),
            entries = { stack },
            scope = cleanupScope(),
            cancelDurationMillis = 300,
            cancelEasing = LinearEasing,
        ) { completed = true }
        assertFalse(emptyWasGesture)
        assertFalse(completed)
        assertNull(state.resolve(stack))

        // 有进度 = 真的滑动过: 跟手到底后回调返回, 进度停在手指位置等出栈后续播
        state.driveInteractiveBack(
            gesture = flow {
                emit(0.2f)
                emit(0.7f)
            },
            entries = { stack },
            scope = cleanupScope(),
            cancelDurationMillis = 300,
            cancelEasing = LinearEasing,
        ) { completed = true }
        assertTrue(completed)
        assertEquals(0.7f, state.progress.value, 0f)

        // 栈内没有上一页 (根页) 的滑动: 接管被拒 (栈里没有可出栈的页), 但滑动过就必须返回 ——
        // 边缘滑动不允许被吞掉 (Android 主界面就是滑动切回标签/退出)
        val rootState = RouteTransitionState(listOf(a))
        var rootCompleted = false
        val rootWasGesture = rootState.driveInteractiveBack(
            gesture = flow { emit(0.4f) },
            entries = { listOf(a) },
            scope = cleanupScope(),
            cancelDurationMillis = 300,
            cancelEasing = LinearEasing,
        ) { rootCompleted = true }
        assertTrue(rootWasGesture)
        assertTrue(rootCompleted)
        // 被拒的会话不写状态机: 段仍落在真实栈上, 也没留下待出栈段
        assertEquals(listOf(a), rootState.segment.from)
        assertFalse(rootState.isInteractiveBackPending(listOf(a)))
        assertNull(rootState.resolve(listOf(a)))
    }

    @Test
    fun `interactive driver leaves state alone when refused then cancelled`() = runBlocking {
        val a = entry(1)
        val state = RouteTransitionState(listOf(a))

        // 单页栈: 接管被拒; 随后框架取消 —— 既不得写状态机, 也不得拉起收尾
        val failure = runCatching {
            state.driveInteractiveBack(
                gesture = flow {
                    emit(0.4f)
                    throw CancellationException("cancelled")
                },
                entries = { listOf(a) },
                scope = cleanupScope(),
                cancelDurationMillis = 300,
                cancelEasing = LinearEasing,
            ) { fail("被拒的会话不允许回调返回") }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf(a), state.segment.from)
        assertEquals(listOf(a), state.segment.to)
        assertEquals(1f, state.progress.value, 0f)
        assertNull(state.resolve(listOf(a)))
    }

    @Test
    fun `interactive driver keeps dragged progress when back chain pops`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        var live = stack

        state.driveInteractiveBack(
            gesture = flow {
                emit(0.2f)
                emit(0.6f)
            },
            entries = { live },
            scope = cleanupScope(),
            cancelDurationMillis = 300,
            cancelEasing = LinearEasing,
            // 返回链真的出栈: 栈变成手势段早就写好的终点
            onComplete = { live = listOf(a) },
        )

        // 进度留在手指位置 (由动画驱动续播到终位), 不被当成"没出栈"而弹回
        assertEquals(0.6f, state.progress.value, 0f)
        assertEquals(state.segment, state.resolve(live))
        assertFalse(state.isInteractiveBackPending(live))
    }

    @Test
    fun `interactive back accepts after previous segment settles`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(listOf(a))
        // 上一段 (前进) 尚未落定: 首个事件被拒
        state.applySegment(requireNotNull(state.resolve(stack)))
        assertFalse(state.beginInteractiveBack(stack, 0.3f))

        // 上一段落定后再次尝试: 接管成功, 页面从手指当前位置开始跟手
        state.settle(stack)
        assertTrue(state.beginInteractiveBack(stack, 0.5f))

        assertEquals(0.5f, state.progress.value, 0f)
        assertTrue(state.isInteractiveBackPending(stack))
    }

    @Test
    fun `interactive driver bounces back when back chain did not pop`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        var completed = false

        // 松手后统一返回链被别的层消费掉 (没真的出栈): 进度停在手指位置没人接管,
        // 驱动必须自己弹回落定
        state.driveInteractiveBack(
            gesture = flow { emit(0f) },
            entries = { stack },
            scope = cleanupScope(),
            cancelDurationMillis = 300,
            cancelEasing = LinearEasing,
        ) { completed = true }

        assertTrue(completed)
        assertEquals(1f, state.progress.value, 0f)
        assertEquals(stack, state.segment.from)
        assertNull(state.resolve(stack))
    }

    @Test
    fun `interactive driver cancels without leaving half transition`() = runBlocking {
        val a = entry(1)
        val b = entry(2)
        val stack = listOf(a, b)
        val state = RouteTransitionState(stack)
        var completed = false

        // 滑动后拖回起点再松手: 框架以取消收尾 —— 取消异常照常抛出 (平台实现据此区分取消与"无进度的完成"),
        // 收尾 (进度回 0 后落定) 交给传入的作用域执行: Android 的框架会取消本次手势的协程。
        val failure = runCatching {
            state.driveInteractiveBack(
                gesture = flow {
                    emit(0.4f)
                    emit(0f)
                    throw CancellationException("cancelled")
                },
                entries = { stack },
                scope = cleanupScope(),
                cancelDurationMillis = 300,
                cancelEasing = LinearEasing,
            ) { completed = true }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse(completed)
        assertEquals(1f, state.progress.value, 0f)
        assertEquals(stack, state.segment.from)
        assertNull(state.resolve(stack))
    }
}
