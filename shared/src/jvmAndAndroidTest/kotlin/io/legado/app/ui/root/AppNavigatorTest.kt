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

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNullWithin(
        timeoutMillis: Long,
    ): T? = runCatching {
        withTimeout(timeoutMillis) { first() }
    }.getOrNull()
}
