package io.legado.app.ui.root

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RouteLifecycleStore] 的契约测试。
 *
 * 守住的契约 (对照原版一页一 Activity 的生命周期语义):
 * 1. 栈顶且前台 = RESUMED (页面可见, 用户正在交互);
 * 2. 在渲染中但被盖住 / 退到后台 = STARTED (在栈内存活, 不可见);
 * 3. 离开渲染 = DESTROYED (副作用收尾);
 * 4. 载体与 entry 同命: 同一 entry 反复取用恒为同一实例, 出栈后销毁。
 */
class RouteLifecycleStoreTest {

    private fun entry(id: Long) = RouteEntry(
        id = RouteEntryId(id),
        route = AppRoute.Main(),
    )

    private fun stateOf(store: RouteLifecycleStore, composed: List<RouteEntry>, e: RouteEntry) =
        store.ownerOf(composed, e)?.lifecycle?.currentState

    @Test
    fun `stack top in foreground is resumed`() {
        val a = entry(1)
        val b = entry(2)
        val store = RouteLifecycleStore()

        store.sync(listOf(a, b), stackTopId = b.id, foreground = true)

        assertEquals(Lifecycle.State.RESUMED, stateOf(store, listOf(a, b), b))
    }

    @Test
    fun `covered page stays started not destroyed`() {
        val a = entry(1)
        val b = entry(2)
        val store = RouteLifecycleStore()

        store.sync(listOf(a, b), stackTopId = b.id, foreground = true)

        // 被盖住的页在栈内存活 (对照原版 Activity 被盖住不销毁), 只是不可见
        assertEquals(Lifecycle.State.STARTED, stateOf(store, listOf(a, b), a))
    }

    @Test
    fun `background demotes stack top to started`() {
        val a = entry(1)
        val store = RouteLifecycleStore()

        store.sync(listOf(a), stackTopId = a.id, foreground = false)

        assertEquals(Lifecycle.State.STARTED, stateOf(store, listOf(a), a))
    }

    @Test
    fun `popped entry is destroyed`() {
        val a = entry(1)
        val b = entry(2)
        val store = RouteLifecycleStore()
        store.sync(listOf(a, b), stackTopId = b.id, foreground = true)
        val ownerOfB = store.ownerOf(listOf(a, b), b)!!

        // b 出栈: 只剩 a 参与渲染
        store.sync(listOf(a), stackTopId = a.id, foreground = true)

        assertEquals(Lifecycle.State.DESTROYED, ownerOfB.lifecycle.currentState)
        assertNull(store.ownerOf(listOf(a), b))
    }

    @Test
    fun `owner identity is stable across recomposition`() {
        val a = entry(1)
        val store = RouteLifecycleStore()

        store.sync(listOf(a), stackTopId = a.id, foreground = true)
        val first = store.ownerOf(listOf(a), a)!!
        store.sync(listOf(a), stackTopId = a.id, foreground = true)
        val second = store.ownerOf(listOf(a), a)!!

        // 同一 entry 恒同一载体: 否则挂在它上面的副作用会被反复重建
        assertEquals(first, second)
    }

    @Test
    fun `outgoing page keeps owner while still rendered`() {
        val a = entry(1)
        val b = entry(2)
        val store = RouteLifecycleStore()
        store.sync(listOf(a, b), stackTopId = b.id, foreground = true)

        // b 已出栈但仍在退场动画的渲染列表里: 其副作用活到离开组合那一刻
        store.sync(listOf(a, b), stackTopId = a.id, foreground = true)

        // 出栈页不再是栈顶, 故降到 STARTED (可见性相关的副作用收尾), 但载体仍在
        assertEquals(Lifecycle.State.STARTED, stateOf(store, listOf(a, b), b))
    }

    @Test
    fun `owner is not created for entry outside render list`() {
        val a = entry(1)
        val b = entry(2)
        val store = RouteLifecycleStore()

        assertNull(store.ownerOf(listOf(a), b))
    }

    @Test
    fun `resumed requires both foreground and stack top`() {
        val a = entry(1)
        val b = entry(2)
        val store = RouteLifecycleStore()

        // 前台但非栈顶
        store.sync(listOf(a, b), stackTopId = a.id, foreground = true)
        assertEquals(Lifecycle.State.STARTED, stateOf(store, listOf(a, b), b))

        // 栈顶但非前台
        store.sync(listOf(a, b), stackTopId = b.id, foreground = false)
        assertEquals(Lifecycle.State.STARTED, stateOf(store, listOf(a, b), b))

        // 两者兼具
        store.sync(listOf(a, b), stackTopId = b.id, foreground = true)
        assertEquals(Lifecycle.State.RESUMED, stateOf(store, listOf(a, b), b))
    }

    @Test
    fun `new entry receives target state immediately on creation`() {
        val a = entry(1)
        val store = RouteLifecycleStore()

        // 模拟 LegadoApp: 在渲染前 sync
        store.sync(listOf(a), stackTopId = a.id, foreground = true)
        val owner = requireNotNull(store.ownerOf(listOf(a), a))

        // 首帧即 RESUMED, 严禁落入 INITIALIZED 导致第一帧副作用丢失并引发额外重组
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }
}
