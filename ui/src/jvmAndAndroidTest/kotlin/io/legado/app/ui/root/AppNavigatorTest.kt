package io.legado.app.ui.root

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppNavigatorTest {

    @Test
    fun `push records caller as result target`() {
        val navigator = AppNavigator()
        val callerId = navigator.currentEntry.id

        navigator.push(AppRoute.Search(), RouteResults.OK)

        assertEquals(callerId, navigator.currentEntry.resultTargetEntryId)
    }

    @Test
    fun `pop delivers result only to caller`() = runBlocking {
        val navigator = AppNavigator()
        val rootId = navigator.currentEntry.id
        navigator.push(AppRoute.Search(), RouteResults.OK)
        navigator.push(AppRoute.About)
        navigator.pop()

        val rootResult = async { navigator.resultsFor(rootId).first() }
        navigator.pop(RouteResultPayload.Ok)

        assertEquals(RouteResults.OK, withTimeout(1_000) { rootResult.await() }.key)
    }

    @Test
    fun `same key from different callers is isolated`() = runBlocking {
        val navigator = AppNavigator()
        val rootId = navigator.currentEntry.id
        navigator.push(AppRoute.Search(), RouteResults.OK)
        val searchId = navigator.currentEntry.id
        navigator.push(AppRoute.About, RouteResults.OK)

        navigator.pop(RouteResultPayload.Ok)
        val searchResult = withTimeout(1_000) { navigator.resultsFor(searchId).first() }
        assertEquals(RouteResults.OK, searchResult.key)
        assertEquals(null, navigator.resultsFor(rootId).firstOrNullWithin(100))
    }

    @Test
    fun `result survives subscription gap`() = runBlocking {
        val navigator = AppNavigator()
        val callerId = navigator.currentEntry.id
        navigator.push(AppRoute.Search(), RouteResults.OK)

        navigator.pop(RouteResultPayload.Ok)

        val result = withTimeout(1_000) { navigator.resultsFor(callerId).first() }
        assertEquals(RouteResultPayload.Ok, result.payload)
    }

    @Test
    fun `single top includes result target`() {
        val navigator = AppNavigator()
        navigator.push(AppRoute.Search(), RouteResults.OK)
        val firstId = navigator.currentEntry.id
        val duplicateId = navigator.push(AppRoute.Search(), RouteResults.OK)

        assertEquals(firstId, duplicateId)

        navigator.pop()
        navigator.push(AppRoute.Search(), RouteResults.OK)
        val secondId = navigator.currentEntry.id
        assertNotEquals(firstId, secondId)
    }

    /**
     * 对话框默认叠放 (对照原版 DialogFragment 父弹子不 dismiss 自己):
     * 段评列表上弹图片查看器, 两者同时在栈, 后弹的在栈顶 (渲染在上层)。
     */
    @Test
    fun `showing a dialog keeps existing dialogs stacked`() {
        val navigator = AppNavigator()
        navigator.showOverlay(AppOverlay.Dialog(key = "review_list"))

        navigator.showOverlay(AppOverlay.Dialog(key = "photo"))

        assertEquals(
            listOf("review_list", "photo"),
            navigator.overlays.value.map { it.key },
        )
    }

    /** 叠放后关顶层 (看完大图) 回到下面的段评列表, 而不是两个一起消失。 */
    @Test
    fun `dismissing top dialog returns to the one below`() {
        val navigator = AppNavigator()
        navigator.showOverlay(AppOverlay.Dialog(key = "review_list"))
        navigator.showOverlay(AppOverlay.Dialog(key = "photo"))

        assertEquals(true, navigator.dismissTopOverlay())

        assertEquals(listOf("review_list"), navigator.overlays.value.map { it.key })
    }

    /**
     * 路由 push 仍关全部对话框 (单页导航下路由渲染在 Overlay 之下, 不关会被遮住);
     * Sheet (半屏界面) 与 keepOnPush 对话框 (书源登录, 自管挂起/恢复) 保留。
     */
    @Test
    fun `push dismisses dialogs but keeps sheet and keepOnPush`() {
        val navigator = AppNavigator()
        navigator.showOverlay(AppOverlay.Dialog(key = "review_list"))
        navigator.showOverlay(AppOverlay.Dialog(key = "photo"))
        navigator.showOverlay(AppOverlay.Dialog(key = "sourceLogin", keepOnPush = true))
        navigator.showOverlay(AppOverlay.Sheet(key = "web_view"))

        navigator.push(AppRoute.About)

        assertEquals(
            listOf("sourceLogin", "web_view"),
            navigator.overlays.value.map { it.key },
        )
    }

    /**
     * 单例页 (singleTask) 复用: 栈中已有该页时复用它、清掉它上面的页, 页面身份不变
     * (页面数据与界面状态以身份为键, 不重建)。
     */
    @Test
    fun `single task page is reused and pages above are removed`() {
        val navigator = AppNavigator()
        val rootId = navigator.currentEntry.id
        navigator.push(AppRoute.Search())
        val searchId = navigator.currentEntry.id
        navigator.push(AppRoute.About)
        val aboutId = navigator.currentEntry.id
        navigator.push(AppRoute.Search())
        assertEquals(4, navigator.backStack.value.size)

        val reusedId = navigator.push(AppRoute.About)

        assertEquals(aboutId, reusedId)
        assertEquals(listOf(rootId, searchId, aboutId), navigator.backStack.value.map { it.id })
    }

    /** 单例页复用换参数: 同一页面被换成另一个参数时复用原页面, 身份不变。 */
    @Test
    fun `single task reuse keeps page identity across parameter change`() {
        val navigator = AppNavigator()
        navigator.push(AppRoute.BookSourceEdit("u1"))
        val pageId = navigator.currentEntry.id
        navigator.push(AppRoute.Search())

        val reusedId = navigator.push(AppRoute.BookSourceEdit("u2"))

        assertEquals(pageId, reusedId)
        assertEquals(AppRoute.BookSourceEdit("u2"), navigator.currentEntry.route)
        assertEquals(2, navigator.backStack.value.size)
    }

    /** 栈顶同类页 (singleTop) 复用并换参数。 */
    @Test
    fun `single top reuses top page of same type with new parameters`() {
        val navigator = AppNavigator()
        navigator.push(AppRoute.BookshelfManage(1L))
        val pageId = navigator.currentEntry.id

        val reusedId = navigator.push(AppRoute.BookshelfManage(2L))

        assertEquals(pageId, reusedId)
        assertEquals(AppRoute.BookshelfManage(2L), navigator.currentEntry.route)
        assertEquals(2, navigator.backStack.value.size)
    }

    /** 同类页不在栈顶时 singleTop 不复用, 而是新建 (对照原版 singleTop 语义)。 */
    @Test
    fun `single top does not reuse page below top`() {
        val navigator = AppNavigator()
        navigator.push(AppRoute.BookshelfManage(1L))
        navigator.push(AppRoute.Search())

        navigator.push(AppRoute.BookshelfManage(2L))

        assertEquals(4, navigator.backStack.value.size)
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNullWithin(
        timeoutMillis: Long,
    ): T? = runCatching {
        withTimeout(timeoutMillis) { first() }
    }.getOrNull()
}
