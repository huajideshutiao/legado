package io.legado.app.ui.book.read

import io.legado.app.data.entities.Book
import io.legado.app.model.ReadBookShared
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformCapabilityProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限请求身份与归属判定的契约测试。
 *
 * 守住的契约（原版 `ReadBookViewModel.permissionDenialLiveData` 是 LiveData：
 * 无初值、每次 postValue 都是新事件、值相同也通知）：
 * 1. 无请求（初态 null）不触发任何处理 —— 原实现用带初值 0 的 StateFlow 表达"无请求"，
 *    `postPermissionDenial(0)` 与初值相等被 equals 去重吞掉；
 * 2. 同一 code 的后续失败是新请求（身份不同），不被值去重吞掉；
 * 3. 选目录结果只采用仍是最新那条请求、且书籍仍是当前书的那次 —— 旧结果不覆盖新请求，
 *    换书后的旧结果不落到新书。
 */
class PermissionDenialRequestTest {

    private val bookA = "file:///a.txt"
    private val bookB = "file:///b.txt"

    @Test
    fun `无请求时不处理`() {
        val request = PermissionDenialRequest(id = 1, code = 0, bookUrl = bookA)
        assertFalse(request.canAccept(current = null, currentBookUrl = bookA))
    }

    @Test
    fun `同一书籍同一请求可处理`() {
        val request = PermissionDenialRequest(id = 7, code = 1, bookUrl = bookA)
        assertTrue(request.canAccept(current = request, currentBookUrl = bookA))
    }

    @Test
    fun `同 code 的后续失败使旧结果作废且不误清新请求`() {
        val old = PermissionDenialRequest(id = 1, code = 1, bookUrl = bookA)
        val newer = PermissionDenialRequest(id = 2, code = 1, bookUrl = bookA)
        // 旧请求的结果回来时, 当前请求已是新那条 → 不采用
        assertFalse(old.canAccept(current = newer, currentBookUrl = bookA))
        // 新请求自己的结果仍可处理
        assertTrue(newer.canAccept(current = newer, currentBookUrl = bookA))
    }

    @Test
    fun `换书后的旧请求结果不落到新书`() {
        val request = PermissionDenialRequest(id = 3, code = 0, bookUrl = bookA)
        assertFalse(request.canAccept(current = request, currentBookUrl = bookB))
        assertFalse(request.canAccept(current = request, currentBookUrl = null))
    }

    @Test
    fun `无当前书时不处理任何请求`() {
        val request = PermissionDenialRequest(id = 4, code = 1, bookUrl = bookA)
        assertFalse(request.canAccept(current = request, currentBookUrl = null))
    }

    @Test
    fun `相同 code 与书籍的两次请求身份不同`() {
        val first = PermissionDenialRequest(id = 1, code = 0, bookUrl = bookA)
        val second = PermissionDenialRequest(id = 2, code = 0, bookUrl = bookA)
        assertFalse(first == second)
        assertFalse(first.canAccept(current = second, currentBookUrl = bookA))
    }
}

/** 可控选目器: 打开时登记回调并发信号, 测试手动决定结果。 */
private class FakePickerCapabilities : PlatformCapabilities {
    val callbacks = mutableListOf<(String?) -> Unit>()
    val opened = Channel<Unit>(Channel.UNLIMITED)

    override fun exitApplication() {}

    override fun openExternalUrl(url: String) {}

    override fun shareText(text: String) {}

    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        callbacks += onSelected
        opened.trySend(Unit)
    }
}

/**
 * await/accept 真实挂起闭环: 实际在途选择器由 VM 持有, 等待者取消不冒充选择器已关闭,
 * 重订阅接回同一个在途选择器; 选择器在途时新请求排队; 过期请求清空不拉起选择器。
 */
class PermissionDenialLoopTest {

    private val bookA = "file:///a.epub"

    private fun newVm(
        scope: CoroutineScope,
        book: Book,
    ): Triple<ReadBookViewModelShared, FakePickerCapabilities, ReadBookShared> {
        val fake = FakePickerCapabilities()
        PlatformCapabilityProviders.register(fake)
        val readBook = ReadBookShared()
        readBook.bookValue = book
        return Triple(ReadBookViewModelShared(readBook, scope), fake, readBook)
    }

    @Test
    fun `等待者取消后重订阅接回同一个在途选择器`() = runBlocking {
        val book = Book(bookUrl = bookA)
        val (vm, fake, _) = newVm(this, book)
        vm.postPermissionDenial(0, book)
        val id = vm.permissionDenialState.value!!.id

        val waiter1 = launch { vm.awaitPermissionDenialResolution(id) }
        fake.opened.receive()
        // 宿主 LaunchedEffect 随组合取消重启: 等待者消失, 底层选择器仍开着
        waiter1.cancelAndJoin()

        val waiter2 = async { vm.awaitPermissionDenialResolution(id) }
        yield(); yield()
        assertEquals("重订阅接回同一选择器, 不再开第二个", 1, fake.callbacks.size)
        fake.callbacks[0]("dir://picked")
        assertEquals("dir://picked", waiter2.await()?.dirUri)
        assertEquals(book, vm.acceptPermissionDenial(id))
        assertNull(vm.permissionDenialState.value)
    }

    @Test
    fun `选择器在途时新请求排队且旧结果作废不消费新请求`() = runBlocking {
        val book = Book(bookUrl = bookA)
        val (vm, fake, _) = newVm(this, book)
        vm.postPermissionDenial(0, book)
        val id1 = vm.permissionDenialState.value!!.id

        val waiter1 = async { vm.awaitPermissionDenialResolution(id1) }
        fake.opened.receive()
        // 选择器还开着时同 code 又失败一次: 新请求顶上, 但不开第二个选择器
        vm.postPermissionDenial(0, book)
        val id2 = vm.permissionDenialState.value!!.id
        val waiter2 = async { vm.awaitPermissionDenialResolution(id2) }
        yield(); yield()
        assertEquals("在途选择器未收尾, 新请求排队", 1, fake.callbacks.size)

        fake.callbacks[0](null)
        assertEquals("用户取消的第一个选择器不产出", null, waiter1.await()?.dirUri)
        assertEquals("已被取代的旧请求不得消费", null, vm.acceptPermissionDenial(id1))

        fake.opened.receive()
        assertEquals("新请求在旧选择器收尾后拉起自己的", 2, fake.callbacks.size)
        fake.callbacks[1]("dir://picked")
        assertEquals("dir://picked", waiter2.await()?.dirUri)
        assertEquals(book, vm.acceptPermissionDenial(id2))
    }

    @Test
    fun `换书后请求清空不再拉起选择器`() = runBlocking {
        val book = Book(bookUrl = bookA)
        val (vm, fake, readBook) = newVm(this, book)
        vm.postPermissionDenial(0, book)
        val id = vm.permissionDenialState.value!!.id
        // 换书: 当前书指向别的 bookUrl
        readBook.bookValue = Book(bookUrl = "file:///b.epub")
        assertNull(vm.awaitPermissionDenialResolution(id))
        assertNull(vm.permissionDenialState.value)
        assertEquals(0, fake.callbacks.size)
    }
}
