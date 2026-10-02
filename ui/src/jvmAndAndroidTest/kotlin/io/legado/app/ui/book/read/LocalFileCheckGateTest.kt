package io.legado.app.ui.book.read

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import kotlin.coroutines.cancellation.CancellationException

/**
 * [LocalFileCheckGate] 单飞轮语义: 同一本书同一时刻只有一轮检查, 并发到达共享该轮结果
 * (成功全过、失败只上报一次权限请求的源头); 成功才备忘; reset/取消的正确交错。
 */
class LocalFileCheckGateTest {

    @Test
    fun `并发到达共享一轮检查且成功后备忘生效`() = runBlocking {
        val gate = LocalFileCheckGate()
        var checkCount = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.checkOnce("book-a") {
                checkCount++
                entered.complete(Unit)
                release.await()
                true
            }
        }
        entered.await()
        // 检查在途时另外两章到达: 入轮共享结果, 不再各自执行检查
        val second = async { gate.checkOnce("book-a") { checkCount++; true } }
        val third = async { gate.checkOnce("book-a") { checkCount++; true } }
        yield(); yield()
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(third.await())
        assertEquals("并发到达只执行一轮检查", 1, checkCount)
        // 备忘生效: 后续装载直达
        assertTrue(gate.checkOnce("book-a") { checkCount++; true })
        assertEquals(1, checkCount)
    }

    @Test
    fun `失败轮共享一次结果且新装载会重新检查`() = runBlocking {
        val gate = LocalFileCheckGate()
        var checkCount = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rounds = listOf(
            async {
                gate.checkOnce("book-a") {
                    checkCount++
                    entered.complete(Unit)
                    release.await()
                    false
                }
            },
            async { gate.checkOnce("book-a") { checkCount++; false } },
            async { gate.checkOnce("book-a") { checkCount++; false } },
        )
        entered.await()
        yield(); yield()
        release.complete(Unit)
        rounds.forEach { assertEquals(false, it.await()) }
        assertEquals("失败轮只执行一次检查 (一次权限请求)", 1, checkCount)
        // 失败不备忘: 之后的新装载开新一轮重新检查 (文件可能已被找回)
        assertTrue(gate.checkOnce("book-a") { checkCount++; true })
        assertEquals(2, checkCount)
    }

    @Test
    fun `轮主取消时等待者随轮中止且轮次清场`() = runBlocking {
        val gate = LocalFileCheckGate()
        var checkCount = 0
        val entered = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.DEFAULT) {
            gate.checkOnce("book-a") {
                checkCount++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val joiner = async { gate.checkOnce("book-a") { checkCount++; true } }
        yield(); yield()
        owner.cancelAndJoin()
        try {
            joiner.await()
            fail("等待者应随轮次中止")
        } catch (_: CancellationException) {
        }
        assertEquals("取消的轮不算检查完成", 1, checkCount)
        // 轮次清场: 下一轮重新检查
        assertTrue(gate.checkOnce("book-a") { checkCount++; true })
        assertEquals(2, checkCount)
    }

    @Test
    fun `reset作废在途轮且迟到结果不得越过reset`() = runBlocking {
        val gate = LocalFileCheckGate()
        var checkCount = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            gate.checkOnce("book-a") {
                checkCount++
                entered.complete(Unit)
                release.await()
                true
            }
        }
        entered.await()
        gate.reset()
        release.complete(Unit)
        assertTrue(owner.await())
        // reset 已作废本轮: 迟到的成功不入备忘, 同书下一轮重新检查
        assertTrue(gate.checkOnce("book-a") { checkCount++; true })
        assertEquals(2, checkCount)
    }
}
