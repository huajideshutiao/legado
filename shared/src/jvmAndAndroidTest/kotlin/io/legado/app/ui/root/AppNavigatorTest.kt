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

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNullWithin(
        timeoutMillis: Long,
    ): T? = runCatching {
        withTimeout(timeoutMillis) { first() }
    }.getOrNull()
}
