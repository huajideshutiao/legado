package io.legado.app.model

import io.legado.app.data.entities.BookSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCheckStateTest {

    @After
    fun tearDown() {
        Debug.finishChecking()
        Debug.clearCheckMessages()
    }

    @Test
    fun `校验生命周期发布不可变消息快照`() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "示例书源",
        )

        Debug.startChecking(source)

        val started = Debug.checkState.value
        assertTrue(started.isChecking)
        assertEquals("[00:00.000] 开始校验", started.messages[source.bookSourceUrl])

        Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")

        val finishedMessage = Debug.checkState.value.messages[source.bookSourceUrl].orEmpty()
        assertTrue(finishedMessage.endsWith(" 校验成功"))

        Debug.finishChecking()
        assertFalse(Debug.checkState.value.isChecking)
        assertEquals(finishedMessage, Debug.checkState.value.messages[source.bookSourceUrl])

        Debug.clearCheckMessages()
        assertTrue(Debug.checkState.value.messages.isEmpty())
    }
}
