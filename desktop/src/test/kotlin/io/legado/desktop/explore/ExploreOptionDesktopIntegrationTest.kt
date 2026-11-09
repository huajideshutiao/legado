// 发现参数/请求会话的桌面真实链路回归: 真实 DesktopCore + QuickJS JNI + WebBook + 真实 VM,
// 本地 HttpServer 只提供受控网页, 不替换解析器/JS 桥。
package io.legado.desktop.explore

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HomeSection
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.legado.app.model.Debug
import io.legado.app.web.api.DebugWsHandler
import io.legado.app.web.api.WsSession
import io.legado.app.model.webBook.ExploreOptionUiState
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.explore.ExploreShowScreenModel
import io.legado.app.ui.book.source.debug.BookSourceDebugScreenModel
import io.legado.app.ui.book.source.debug.BookSourceDebugUiEvent
import io.legado.app.ui.book.explore.ExploreShowUiEvent
import io.legado.app.ui.compose.component.AppChoiceField
import io.legado.app.ui.compose.component.AppMultiChoiceField
import eu.kanade.tachiyomi.source.model.Filter
import io.legado.app.ui.compose.component.AppMixedChoiceRowGroup
import io.legado.app.ui.compose.component.ExploreOptionsRow
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.SharedEventBusProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchViewModel
import io.legado.app.ui.explore.ExploreShowViewModelShared
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.ScreenInfoProvider
import io.legado.app.utils.ScreenInfoProviders
import io.legado.desktop.ui.DesktopPlatformCapabilities
import io.legado.app.help.HomeTabHelpShared
import io.legado.app.ui.main.home.HomeViewModelShared
import io.legado.desktop.help.tvbox.TvBoxTestRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExploreOptionDesktopIntegrationTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun boot() {
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            TvBoxTestRuntime.bootstrap()
            PlatformCapabilityProviders.register(DesktopPlatformCapabilities)
            ScreenInfoProviders.register(object : ScreenInfoProvider {
                override val screenWidthPx: Int = 2400
                override val screenHeightPx: Int = 900
            })
        }
    }

    private var server: HttpServer? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private lateinit var scope: CoroutineScope
    private val requests = CopyOnWriteArrayList<String>()
    private val slowStarted = CountDownLatch(1)
    private val releaseSlow = CountDownLatch(1)
    private val slowFinished = CountDownLatch(1)
    private val pageTwoStarted = CountDownLatch(1)
    private val releasePageTwo = CountDownLatch(1)
    private val pageTwoFinished = CountDownLatch(1)
    @Volatile
    private var slowGateTimedOut = false
    @Volatile
    private var pageTwoGateTimedOut = false

    private val port: Int get() = server!!.address.port
    private val sourceUrl: String get() = "http://127.0.0.1:$port"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        releaseSlow.countDown()
        releasePageTwo.countDown()
        scope.cancel()
        server?.stop(0)
        executor?.shutdownNow()
    }

    @Test
    fun `footer空表到达清旧结果且成功与失败字段互斥`() {
        val model = ExploreShowScreenModel()
        model.dispatch(ExploreShowUiEvent.StartLoad)
        model.dispatch(
            ExploreShowUiEvent.BooksLoaded(
                books = listOf(SearchBook(name = "旧", bookUrl = "/old")),
                hasNextPage = true,
                emptyText = "empty",
                bottomLineText = "bottom",
            )
        )
        assertEquals(1, model.state.value.books.size)
        model.dispatch(
            ExploreShowUiEvent.BooksLoaded(
                books = emptyList(),
                hasNextPage = false,
                emptyText = "empty",
                bottomLineText = "bottom",
            )
        )
        assertTrue(model.state.value.books.isEmpty())
        assertEquals("empty", model.state.value.footerText)
        assertEquals(false, model.state.value.footerLoading)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `单选标题不重置 重置在弹窗底栏`() = runDesktopComposeUiTest(640, 720) {
        val resetCount = java.util.concurrent.atomic.AtomicInteger(0)
        var options by mutableStateOf(
            listOf(
                ExploreOptionUiState(
                    name = "t",
                    items = listOf("A" to "1", "B" to "2"),
                    multiSelect = false,
                    selectedValue = "2",
                )
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme {
                    ExploreOptionsRow(
                        options = options,
                        ownerKey = "compose-single",
                        onSelect = { _, value ->
                            options = options.map { it.copy(selectedValue = value) }
                        },
                        onMultiSelect = { _, _ -> },
                        onReset = {
                            if (options.single().selectedValue != "1") {
                                resetCount.incrementAndGet()
                                options = options.map { it.copy(selectedValue = "1") }
                            }
                        },
                    )
                }
            }
        }
        onNodeWithText("t", useUnmergedTree = true).assertHasNoClickAction()
        onNodeWithText("t", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("Reset").performClick()
        assertEquals(1, resetCount.get())
        // 已无变化时重复重置: 入口仍打开, 但调用方不产生第二次重载
        onNodeWithText("t", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("Reset").performClick()
        assertEquals(1, resetCount.get())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `多选标题无点击语义 别名同取值在真实弹窗一起选中`() = runDesktopComposeUiTest(640, 720) {
        var options by mutableStateOf(
            listOf(
                ExploreOptionUiState(
                    name = "t",
                    items = listOf("分组" to "", "A" to "1", "B" to "1", "C" to "2"),
                    multiSelect = true,
                )
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme {
                    ExploreOptionsRow(
                        options = options,
                        ownerKey = "compose-multi",
                        onSelect = { _, _ -> },
                        onMultiSelect = { _, values ->
                            options = options.map {
                                it.copy(selectedValues = values.map { v -> v }.toSet())
                            }
                        },
                        onReset = {},
                    )
                }
            }
        }
        onNodeWithText("t", useUnmergedTree = true).assertHasNoClickAction()
        onNodeWithText("t", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("分组", useUnmergedTree = true).assertHasNoClickAction()
        onNodeWithText("A").performClick()
        onNodeWithText("OK").performClick()
        assertEquals(setOf(1, 2), options.single().selectedIndexes)
        // 取消不提交工作副本
        onNodeWithText("t", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("C").performClick()
        onNodeWithText("Cancel").performClick()
        assertEquals(setOf(1, 2), options.single().selectedIndexes)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `打开A后删除A B不继承弹窗状态`() = runDesktopComposeUiTest(640, 720) {
        var fields by mutableStateOf(
            listOf(
                multiField("A", "key-a", "A-OPT"),
                multiField("B", "key-b", "B-OPT"),
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }
        onNodeWithText("A", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("A-OPT").assertExists()
        fields = fields.filterNot { it.fieldKey == "key-a" }
        onNodeWithText("A-OPT").assertDoesNotExist()
        onNodeWithText("OK").assertDoesNotExist()
        onNodeWithText("B-OPT").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `重排后未变的A仍对应A`() = runDesktopComposeUiTest(640, 720) {
        var fields by mutableStateOf(
            listOf(
                multiField("A", "key-a", "A-OPT"),
                multiField("B", "key-b", "B-OPT"),
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }
        onNodeWithText("A", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("A-OPT").assertExists()
        fields = fields.reversed()
        onNodeWithText("A-OPT").assertExists()
        onNodeWithText("B-OPT").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `同label但raw定义变化取消旧draft`() = runDesktopComposeUiTest(640, 720) {
        var fields by mutableStateOf(
            listOf(multiField("A", "key-a", "A-OPT", listOf("A-OPT" to "raw-1")))
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }
        onNodeWithText("A", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText("A-OPT").assertExists()
        fields = fields.map {
            it.copy(definitionKey = listOf("A-OPT" to "raw-2"))
        }
        onNodeWithText("A-OPT").assertDoesNotExist()
        onNodeWithText("OK").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `同名不同键的字段互不串弹窗`() = runDesktopComposeUiTest(640, 720) {
        var fields by mutableStateOf(
            listOf(
                multiField("同名", "key-first", "FIRST"),
                multiField("同名", "key-second", "SECOND"),
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }
        onAllNodesWithText("同名")[1].performTouchInput { longClick() }
        onNodeWithText("SECOND").assertExists()
        fields = fields.filterNot { it.fieldKey == "key-first" }
        onNodeWithText("SECOND").assertExists()
        onNodeWithText("FIRST").assertDoesNotExist()
    }

    @Test
    fun `同名两段共用原始选择且各段保留自己的包装`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val vm = ExploreShowViewModelShared(scope)
        val books = collectBooks(vm)
        vm.initData(source(), "本地", "$sourceUrl/list?<t(&x=,m:1,n:2)>&<t(&y=,m:1,n:2)>")
        assertEquals(listOf("书A"), books.receiveBookList().map { it.name })
        assertEquals(1, vm.optionsFlow.value.size)
        assertTrue(requests.last().contains("&x=1"))
        assertTrue(requests.last().contains("&y=1"))

        assertTrue(vm.selectOption("t", "2"))
        vm.explore(true)
        assertEquals(listOf("书A"), books.receiveBookList().map { it.name })
        assertEquals(1, vm.optionsFlow.value.size)
        assertTrue(requests.last().contains("&x=2"))
        assertTrue(requests.last().contains("&y=2"))
    }

    @Test
    fun `URL级JS拼接的声明按真实结果生成控件`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val vm = ExploreShowViewModelShared(scope)
        val books = collectBooks(vm)
        val template = "@js: '$sourceUrl/list?' + '<t(&x=,m:1,n:2)>'"
        vm.initData(source(), "本地", template)
        assertEquals(listOf("书A"), books.receiveBookList().map { it.name })
        assertEquals("t", vm.optionsFlow.value.single().name)
        assertTrue(requests.last().contains("&x=1"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `多选重复取值只提交一次且按取值身份联动`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val vm = ExploreShowViewModelShared(scope)
        val books = collectBooks(vm)
        vm.initData(source(), "本地", "$sourceUrl/list?<t(&tag=,A:1,B:1,C:2)|,>")
        books.receiveBookList()
        val option = withTimeout(5_000) {
            vm.optionsFlow.first { it.isNotEmpty() }
        }.single()
        assertTrue(option.headerIndexes.isEmpty())
        assertTrue(option.selectedIndexes.isEmpty())

        assertTrue(vm.selectOptions("t", setOf("1")))
        vm.explore(true)
        books.receiveBookList()
        assertEquals(1, requests.last().split("&tag=1").size - 1)
        assertEquals(setOf(0, 1), vm.optionsFlow.value.single().selectedIndexes)
    }

    @Test
    fun `旧选择的慢请求在新选择完成后仍不能覆盖结果或页码`() = runBlocking {
        startServer(delaySlow = true) { pageOneHtml(next = null) }
        val vm = ExploreShowViewModelShared(scope)
        val books = collectBooks(vm)
        vm.initData(source(), "本地", "$sourceUrl/list?<t(&x=,m:slow,n:fast)>")
        assertTrue(withTimeout(5_000) {
            vm.optionsFlow.first { it.isNotEmpty() }
        }.single().multiSelect.not())
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS))

        assertTrue(vm.selectOption("t", "fast"))
        vm.explore(true)
        assertEquals(listOf("书A"), books.receiveBookList().map { it.name })
        assertEquals(2, vm.page)
        releaseSlow.countDown()
        assertTrue(slowFinished.await(5, TimeUnit.SECONDS))
        assertEquals(2, vm.page)
        assertEquals(listOf("书A"), vm.pageState.value.books.map { it.name })
        assertTrue(requests.any { it.contains("x=fast") })
        assertTrue(requests.any { it.contains("x=slow") })
    }

    @Test
    fun `翻页动态声明把已选值废弃时停发HTTP并清结果`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val vm = ExploreShowViewModelShared(scope)
        val books = collectBooks(vm)
        val errors = collectErrors(vm)
        val template = "@js: page==1 ? '$sourceUrl/list?<t(&x=,m:1,n:2)>' : " +
            "'$sourceUrl/list?<t(&x=,q:9,r:8)>'"
        vm.initData(source(), "本地", template)
        assertEquals(listOf("书A"), books.receiveBookList().map { it.name })
        assertEquals(2, vm.page)
        assertEquals(1, requests.size)

        vm.explore()
        val invalid = withTimeout(5_000) { vm.pageState.first { it.error != null } }
        assertTrue(invalid.books.isEmpty())
        assertTrue(errors.receiveError().contains("参数选择已失效"))
        assertEquals(1, vm.page)
        assertEquals(listOf("q", "r"), vm.optionsFlow.value.single().items.map { it.first })
        assertEquals("9", vm.optionsFlow.value.single().selectedValue)
        assertEquals(1, requests.size)

        // 显式刷新第 1 页: 本次真实声明把旧值失效时按有效默认继续请求一次，不要求用户连点。
        val before = requests.size
        vm.explore(true)
        val fresh = withTimeout(5_000) {
            vm.pageState.first { it.requestId > invalid.requestId && !it.loading }
        }
        assertEquals(listOf("书A"), fresh.books.map { it.name })
        assertTrue(fresh.notice?.contains("默认") == true)
        assertEquals(before + 1, requests.size)
        assertEquals(2, vm.page)
    }

    @Test
    fun `搜索慢页在新选择重搜后不能提交结果或推进页码`() = runBlocking {
        startServer(delayPageTwo = true) { pageOneHtml(next = "/search?page=2&x=1") }
        val source = source().apply {
            searchUrl = "$sourceUrl/search?page={{page}}&<t(&x=,m:1,n:2)>"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
                nextPageUrl = "class.next@href",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(source)
        val vm = SearchViewModel()
        try {
            vm.updateSearchScope(SearchScope(source).toString())
            val books = Channel<List<SearchBook>>(Channel.UNLIMITED)
            scope.launch { vm.searchBooks.collect { books.trySend(it) } }
            vm.onQueryChange("kw")
            vm.onQuerySubmit()
            books.receiveNonEmptyBookList()
            withTimeout(5_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            assertTrue(withTimeout(5_000) { vm.sourceSearchOptionsFlow.first { it[source.bookSourceUrl].orEmpty().isNotEmpty() } }.isNotEmpty())
            vm.scrollToBottom()
            assertTrue(pageTwoStarted.await(5, TimeUnit.SECONDS))
            assertTrue(vm.selectSourceOption(source.bookSourceUrl, "t", "2"))
            vm.restartSourceSearch(source)
            books.receiveNonEmptyBookList()
            releasePageTwo.countDown()
            assertTrue(pageTwoFinished.await(5, TimeUnit.SECONDS))
            assertEquals(
                "2",
                vm.sourceSearchOptionsFlow.value[source.bookSourceUrl]?.single()?.selectedValue,
            )
            assertEquals(1, requests.count { it.contains("page=2") })
            assertEquals(listOf("书A"), vm.searchBooks.replayCache.lastOrNull()?.map { it.name } ?: emptyList<String>())
        } finally {
            vm.close()
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `搜索 VM 多选取值集合重搜不吞写且不重复提交`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source().apply {
            searchUrl = "$sourceUrl/search?<t(&x=,m:1,n:2)|,>"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(source)
        val vm = SearchViewModel()
        try {
            vm.updateSearchScope(SearchScope(source).toString())
            val books = Channel<List<SearchBook>>(Channel.UNLIMITED)
            scope.launch { vm.searchBooks.collect { books.trySend(it) } }
            vm.onQueryChange("kw")
            vm.onQuerySubmit()
            books.receiveNonEmptyBookList()
            val option = withTimeout(5_000) {
                vm.sourceSearchOptionsFlow.first { it[source.bookSourceUrl].orEmpty().isNotEmpty() }
            }[source.bookSourceUrl]!!.single()
            assertEquals("t", option.name)

            assertTrue(vm.selectSourceOptions(source.bookSourceUrl, "t", setOf("1")))
            vm.restartSourceSearch(source)
            books.receiveNonEmptyBookList()
            assertEquals(1, requests.last().split("&x=1").size - 1)
            assertTrue(
                withTimeout(5_000) {
                    vm.sourceSearchOptionsFlow.first {
                        it[source.bookSourceUrl]?.single()?.selectedValues == setOf("1")
                    }
                }.isNotEmpty()
            )
        } finally {
            vm.close()
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `真实可投递调试会话中print为false不投递回调`() = runBlocking {
        startServer(delaySlow = true) { pageOneHtml(next = null) }
        val source = source()
        var callbacks = 0
        val callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                callbacks++
            }

            override fun onExploreOptionsResolved(
                options: List<io.legado.app.model.webBook.ExploreOption>,
                invalidated: Boolean,
                requestId: Long,
            ) {
            }
        }
        val token = Debug.newDebugRequestId()
        try {
            Debug.startDebug(
                this,
                source,
                "发现::@js: '$sourceUrl/list?<t(&x=,slow:slow)>'",
                requestId = token,
                callback = callback,
            )
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
            val before = callbacks
            Debug.log(source.bookSourceUrl, "print-false-active", print = false)
            assertEquals(before, callbacks)
        } finally {
            releaseSlow.countDown()
            Debug.cancelDebug(true, token)
            Debug.finishChecking()
            Debug.clearCheckMessages()
        }
    }

    @Test
    fun `普通校验日志在无调试会话时仍更新且真实WebBook阶段可达`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source()
        var callbacks = 0
        Debug.callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                callbacks++
            }

            override fun onExploreOptionsResolved(
                options: List<io.legado.app.model.webBook.ExploreOption>,
                invalidated: Boolean,
                requestId: Long,
            ) {
            }
        }
        try {
            Debug.startChecking(source)
            val initial = Debug.checkState.value.messages[source.bookSourceUrl].orEmpty()
            WebBook.getBookListAwait(source, "$sourceUrl/list", 1, isSearch = false)
            val after = Debug.checkState.value.messages[source.bookSourceUrl].orEmpty()
            assertTrue(after != initial)
            assertTrue(!after.contains("开始校验"))
            assertEquals(0, callbacks)

            Debug.cancelDebug(true)
            Debug.startChecking(source)
            val initialAfterClose = Debug.checkState.value.messages[source.bookSourceUrl].orEmpty()
            WebBook.getBookListAwait(source, "$sourceUrl/list", 1, isSearch = false)
            val afterClose = Debug.checkState.value.messages[source.bookSourceUrl].orEmpty()
            assertTrue(afterClose != initialAfterClose)
            assertTrue(!afterClose.contains("开始校验"))
            assertEquals(0, callbacks)
        } finally {
            Debug.finishChecking()
            Debug.clearCheckMessages()
            Debug.cancelDebug(true)
        }
    }

    @Test
    fun `单源重搜在飞时主链触底不会重复抓同页`() = runBlocking {
        startServer(delaySlow = true) { pageOneHtml(next = "/search?page=2&x=slow") }
        val source = source().apply {
            bookSourceUrl = "$sourceUrl/search"
            searchUrl = "$sourceUrl/search?<t(&x=,m:1,n:slow)>"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
                nextPageUrl = "class.next@href",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(source)
        val vm = SearchViewModel()
        try {
            vm.updateSearchScope(SearchScope(source).toString())
            vm.onQueryChange("kw")
            vm.onQuerySubmit()
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            assertTrue(vm.selectSourceOption(source.bookSourceUrl, "t", "slow"))
            vm.restartSourceSearch(source)
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
            val before = requests.count { it.contains("x=slow") && it.contains("page=2").not() }
            vm.scrollToBottom()
            kotlinx.coroutines.delay(200)
            assertEquals(before, requests.count { it.contains("x=slow") && it.contains("page=2").not() })
            releaseSlow.countDown()
            assertTrue(slowFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, requests.count { it.contains("x=slow") && it.contains("page=2").not() })
        } finally {
            vm.close()
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `多源坏源失败后不参与自动续页 好源仍可继续到底`() = runBlocking {
        startServer { pageOneHtml(next = "/good?page=2") }
        val bad = source().apply {
            bookSourceUrl = "$sourceUrl/bad"
            bookSourceName = "坏源"
            bookSourceGroup = "round4"
            searchUrl = "$sourceUrl/bad"
            searchRule = SearchRule(
                bookList = "@js: throw new Error('bad-source')",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
            )
        }
        val good = source().apply {
            bookSourceUrl = "$sourceUrl/good"
            bookSourceName = "好源"
            bookSourceGroup = "round4"
            searchUrl = "$sourceUrl/good?<t(&x=,m:1,n:2)>"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
                nextPageUrl = "class.next@href",
                hasMoreRule = "String(result).includes('class=\"next\"')",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(bad, good)
        val vm = SearchViewModel()
        try {
            vm.updateSearchScope("round4")
            vm.onQueryChange("kw")
            vm.onQuerySubmit()
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            assertEquals(1, requests.count { it.startsWith("/bad") })
            assertTrue(vm.hasMore.value)

            vm.scrollToBottom()
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            assertTrue(requests.any { it.startsWith("/good") && it.contains("page=2") })
            assertEquals(1, requests.count { it.startsWith("/bad") })
            assertEquals(false, vm.hasMore.value)

            vm.scrollToBottom()
            kotlinx.coroutines.delay(200)
            assertEquals(1, requests.count { it.startsWith("/bad") })
        } finally {
            vm.close()
            AppDbProviders.get().bookSourceDao.delete(bad, good)
        }
    }

    @Test
    fun `已到底且无在飞的源开关筛选不会补发页面`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source().apply {
            bookSourceUrl = "$sourceUrl/end"
            searchUrl = "$sourceUrl/end"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
                hasMoreRule = "String(result).includes('class=\"next\"')",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(source)
        val vm = SearchViewModel()
        try {
            vm.updateSearchScope(SearchScope(source).toString())
            vm.onQueryChange("kw")
            vm.onQuerySubmit()
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            val before = requests.size
            vm.pauseSourcePagination(source.bookSourceUrl)
            vm.resumeSourcePagination(source.bookSourceUrl)
            kotlinx.coroutines.delay(200)
            assertEquals(before, requests.size)
        } finally {
            vm.close()
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `调试 VM 真实@js与搜索模式带当前请求身份`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source().apply {
            searchUrl = "@js: '$sourceUrl/search?<t(&z=,a:1,b:2)>'"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(source)
        val model = BookSourceDebugScreenModel(
            defaultTextMy = "我的",
            defaultTextFx = "发现",
            systemText = "系统",
            exploreErrorText = { it },
            exploreJsonErrorText = { it.message ?: "" },
        )
        try {
            model.init(sourceUrl)
            withTimeout(5_000) {
                while (model.bookSource == null) kotlinx.coroutines.delay(20)
            }
            model.dispatch(
                BookSourceDebugUiEvent.QueryChange(
                    "发现::@js: '$sourceUrl/list?<t(&x=,m:1,n:2)>'"
                )
            )
            model.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            val state = withTimeout(8_000) {
                model.uiState.first { it.exploreOptions.isNotEmpty() }
            }
            assertEquals("t", state.exploreOptions.single().name)
            withTimeout(5_000) { while (requests.none { it.startsWith("/list") }) kotlinx.coroutines.delay(10) }
            assertEquals(1, requests.count { it.startsWith("/list") })
            assertTrue(requests.first { it.startsWith("/list") }.contains("&x=1"))

            model.dispatch(BookSourceDebugUiEvent.SelectOption("t", "2"))
            withTimeout(8_000) {
                while (
                    model.uiState.value.exploreOptions.singleOrNull()?.selectedValue != "2" ||
                    requests.none { it.startsWith("/list") && it.contains("&x=2") }
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }

            // 编辑 B 但不提交: 再选 A 参数必须仍以已提交 A 为目标, 不改 query、不启动 B。
            model.dispatch(BookSourceDebugUiEvent.QueryChange("kw"))
            val listCountBeforeSelect = requests.count { it.startsWith("/list") }
            model.dispatch(BookSourceDebugUiEvent.SelectOption("t", "1"))
            withTimeout(8_000) {
                while (
                    requests.count { it.startsWith("/list") } != listCountBeforeSelect + 1 ||
                    model.uiState.value.exploreOptions.singleOrNull()?.selectedValue != "1"
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }
            val newListRequest = requests
                .filter { it.startsWith("/list") }
                .drop(listCountBeforeSelect)
                .single()
            assertTrue(newListRequest.contains("&x=1"))
            assertEquals("kw", model.uiState.value.query)
            assertTrue(requests.none { it.startsWith("/search") })

            // 显式提交 B 才切换目标。
            model.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            withTimeout(8_000) {
                while (
                    model.uiState.value.exploreOptions.isEmpty() ||
                    requests.none { it.startsWith("/search") }
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(1, requests.count { it.startsWith("/search") })
        } finally {
            model.onCleared()
            Debug.cancelDebug(true)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `WS非法JSON无调试token仍send并close 传输失败也只清理自身`() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        val ws = object : WsSession {
            override fun send(text: String) {
                sent.add(text)
            }

            override fun close(reason: String) {
                closed.countDown()
            }
        }
        val handler = DebugWsHandler(ws, "不能为空")
        try {
            handler.onMessage("not-json")
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertTrue(sent.contains("数据必须为Json格式"))
            // 已 sealed 的新消息不再受理, 不会再次 send/close。
            handler.onMessage("""{"tag":"","key":""}""")
            kotlinx.coroutines.delay(100)
            assertEquals(1, sent.size)
        } finally {
            handler.onClose()
        }
    }

    @Test
    fun `WS传输send失败仍尝试物理close并结束自身`() = runBlocking {
        val closed = CountDownLatch(1)
        val ws = object : WsSession {
            override fun send(text: String) {
                throw java.io.IOException("broken-pipe")
            }

            override fun close(reason: String) {
                closed.countDown()
            }
        }
        val handler = DebugWsHandler(ws, "不能为空")
        try {
            handler.onMessage("not-json")
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        } finally {
            handler.onClose()
        }
    }

    @Test
    fun `聚合副本不污染原始分组的来源集合`() = runBlocking {
        startServer(
            queryHtml = { query ->
                when {
                    query.contains("x=2") -> "<html><body></body></html>"
                    query.contains("x=1") -> sameBookHtml("/b/1")
                    else -> sameBookHtml("/a/1")
                }
            },
        ) { sameBookHtml("/a/1") }
        val a = source().apply {
            bookSourceUrl = "$sourceUrl/a"
            bookSourceName = "A"
            bookSourceGroup = "samebook"
            searchUrl = "$sourceUrl/a"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
            )
        }
        val b = source().apply {
            bookSourceUrl = "$sourceUrl/b"
            bookSourceName = "B"
            bookSourceGroup = "samebook"
            searchUrl = "$sourceUrl/b?<t(&x=,m:1,n:2)>"
            searchRule = SearchRule(
                bookList = "class.item",
                name = "class.name@text",
                author = "class.author@text",
                bookUrl = "class.name@href",
            )
        }
        AppDbProviders.get().bookSourceDao.insert(a, b)
        val vm = SearchViewModel()
        try {
            vm.updateSearchScope("samebook")
            vm.onQueryChange("kw")
            vm.onQuerySubmit()
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            withTimeout(5_000) {
                while (vm.searchBooks.replayCache.lastOrNull()?.singleOrNull()?.origins?.size != 2) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(2, vm.searchBooks.replayCache.last().single().origins.size)
            val groupsBefore = vm.searchGroups.replayCache.last()
            assertEquals(2, groupsBefore.size)
            assertEquals(1, groupsBefore.first { it.source.bookSourceUrl == a.bookSourceUrl }.books.single().origins.size)
            assertEquals(1, groupsBefore.first { it.source.bookSourceUrl == b.bookSourceUrl }.books.single().origins.size)
            val aRequests = requests.count { it.startsWith("/a") }

            assertTrue(vm.selectSourceOption(b.bookSourceUrl, "t", "2"))
            vm.restartSourceSearch(b)
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            withTimeout(5_000) {
                while (vm.searchBooks.replayCache.lastOrNull()?.singleOrNull()?.origins?.size != 1) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(1, vm.searchBooks.replayCache.last().single().origins.size)
            assertEquals(aRequests, requests.count { it.startsWith("/a") })
            assertEquals(1, vm.searchGroups.replayCache.last().size)
            assertEquals(1, vm.searchGroups.replayCache.last().single().books.single().origins.size)

            assertTrue(vm.selectSourceOption(b.bookSourceUrl, "t", "1"))
            vm.restartSourceSearch(b)
            withTimeout(8_000) { while (vm.isSearching.value) kotlinx.coroutines.delay(20) }
            withTimeout(5_000) {
                while (vm.searchBooks.replayCache.lastOrNull()?.singleOrNull()?.origins?.size != 2) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(2, vm.searchBooks.replayCache.last().single().origins.size)
            assertEquals(2, vm.searchGroups.replayCache.last().size)
            assertEquals(1, vm.searchGroups.replayCache.last()
                .first { it.source.bookSourceUrl == a.bookSourceUrl }.books.single().origins.size)
            assertEquals(1, vm.searchGroups.replayCache.last()
                .first { it.source.bookSourceUrl == b.bookSourceUrl }.books.single().origins.size)
        } finally {
            vm.close()
            AppDbProviders.get().bookSourceDao.delete(a, b)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `同key同items单选多选双向切换只关闭picker保留值标签`() = runDesktopComposeUiTest(640, 720) {
        val definition = listOf("A-OPT" to "raw")
        var fields by mutableStateOf<List<io.legado.app.ui.compose.component.AppOptionField>>(
            listOf(multiField("A", "key-a", "A-OPT", definition))
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }

        // 多选 picker: 行标题 + dialog 标题 = 2。
        onNodeWithText("A", useUnmergedTree = true).performTouchInput { longClick() }
        assertEquals(2, onAllNodesWithText("A").fetchSemanticsNodes().size)
        onNodeWithText("A-OPT").assertExists()

        // 同 key、同 raw definition 改单选: picker 必须关闭, 值标签 A-OPT 仍在下拉值区。
        fields = listOf(
            AppChoiceField(
                fieldKey = "key-a",
                definitionKey = definition,
                title = "A",
                options = listOf("A-OPT"),
                selectedIndex = 0,
                onSelect = {},
            )
        )
        assertEquals(1, onAllNodesWithText("A").fetchSemanticsNodes().size)
        onNodeWithText("A-OPT").assertExists()
        onNodeWithText("OK").assertDoesNotExist()

        // 单选 picker 再打开, 再改回多选: 反向同样关闭旧 picker。
        onNodeWithText("A", useUnmergedTree = true).performTouchInput { longClick() }
        assertEquals(2, onAllNodesWithText("A").fetchSemanticsNodes().size)
        fields = listOf(multiField("A", "key-a", "A-OPT", definition))
        assertEquals(1, onAllNodesWithText("A").fetchSemanticsNodes().size)
        onNodeWithText("A-OPT").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `普通下拉值区菜单在定义raw变化后关闭`() = runDesktopComposeUiTest(640, 720) {
        var fields by mutableStateOf(
            listOf(
                AppChoiceField(
                    fieldKey = "key-a",
                    definitionKey = listOf("A-OPT" to "old"),
                    title = "A",
                    options = listOf("A-OPT", "B-OPT"),
                    selectedIndex = 0,
                    onSelect = {},
                )
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }
        onNodeWithText("A-OPT", useUnmergedTree = true).performTouchInput { click() }
        waitForIdle()
        onNodeWithText("B-OPT").assertExists()
        fields = listOf(
            fields.single().copy(definitionKey = listOf("A-OPT" to "new"))
        )
        onNodeWithText("B-OPT").assertDoesNotExist()
        assertEquals(1, onAllNodesWithText("A-OPT").fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `同名不同SDK Filter实例不互串picker`() = runDesktopComposeUiTest(640, 720) {
        val firstFilter = TestSelectFilter("同名", arrayOf("FIRST"))
        val secondFilter = TestSelectFilter("同名", arrayOf("SECOND"))
        var fields: List<AppChoiceField> by mutableStateOf(
            listOf(
                filterField(firstFilter),
                filterField(secondFilter),
            )
        )
        setContent {
            val theme = remember { DesktopThemeStoreProvider() }
            val config = remember { DesktopAppConfigProvider() }
            val bus = remember { SharedEventBusProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides theme,
                LocalAppConfigProvider provides config,
                LocalEventBusProvider provides bus,
            ) {
                AppTheme { AppMixedChoiceRowGroup(fields) }
            }
        }
        onAllNodesWithText("同名")[1].performTouchInput { longClick() }
        // 两行同名标题 + 打开的第二行 dialog 标题; SECOND 同时是值区和 dialog 项。
        assertEquals(3, onAllNodesWithText("同名").fetchSemanticsNodes().size)
        assertEquals(2, onAllNodesWithText("SECOND").fetchSemanticsNodes().size)
        // 同一 Filter 实例、同 definition、同选择, 只换新 callback 对象; List 必须不相等才能重组。
        var picked = -1
        val rebuilt = listOf(
            filterField(firstFilter) { picked = it },
            filterField(secondFilter) { picked = it },
        )
        assertTrue(rebuilt != fields)
        assertTrue(rebuilt[1].fieldKey === secondFilter)
        assertEquals(fields[1].definitionKey, rebuilt[1].definitionKey)
        assertEquals(fields[1].selectedIndex, rebuilt[1].selectedIndex)
        runOnIdle { fields = rebuilt }
        // 重组后尚未点选, 新 callback 不应被提前调用。
        assertEquals(-1, picked)
        assertEquals(3, onAllNodesWithText("同名").fetchSemanticsNodes().size)
        assertEquals(2, onAllNodesWithText("SECOND").fetchSemanticsNodes().size)
        runOnIdle { fields = fields.filterNot { it.fieldKey === firstFilter } }
        assertEquals(2, onAllNodesWithText("同名").fetchSemanticsNodes().size)
        assertEquals(2, onAllNodesWithText("SECOND").fetchSemanticsNodes().size)
        onNodeWithText("FIRST").assertDoesNotExist()
    }

    @Test
    fun `同页旧慢任务释放后新独立慢任务仍保持loading`() = runBlocking {
        startServer(delaySlow = true, delayPageTwo = true) { pageOneHtml(next = null) }
        val source = source()
        AppDbProviders.get().bookSourceDao.insert(source)
        val model = BookSourceDebugScreenModel(
            defaultTextMy = "我的",
            defaultTextFx = "发现",
            systemText = "系统",
            exploreErrorText = { it },
            exploreJsonErrorText = { it.message ?: "" },
        )
        try {
            model.init(sourceUrl)
            withTimeout(5_000) {
                while (model.bookSource == null) kotlinx.coroutines.delay(20)
            }
            model.dispatch(
                BookSourceDebugUiEvent.QueryChange(
                    "发现::@js: '$sourceUrl/list?<t(&x=,slow:slow,fast:fast)>'"
                )
            )
            model.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS))
            assertTrue(model.uiState.value.loading)

            model.dispatch(
                BookSourceDebugUiEvent.QueryChange(
                    "发现::@js: '$sourceUrl/list?<t(&page=,2:2,3:3)>'"
                )
            )
            model.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            assertTrue(pageTwoStarted.await(5, TimeUnit.SECONDS))
            // 新会话阻塞在独立 page=2 gate; 旧 Job 的取消 completion 不得清掉新 loading。
            assertTrue(model.uiState.value.loading)

            releaseSlow.countDown()
            assertTrue(slowFinished.await(5, TimeUnit.SECONDS))
            assertTrue(!slowGateTimedOut)
            // 这里只确认旧服务器响应已释放且新请求仍被独立 gate 阻塞;
            // server slowFinished 不等价于客户端旧 Job completion, 不宣称该顺序。
            assertTrue(model.uiState.value.loading)

            releasePageTwo.countDown()
            withTimeout(8_000) { model.uiState.first { !it.loading } }
            assertTrue(!pageTwoGateTimedOut)
        } finally {
            releaseSlow.countDown()
            releasePageTwo.countDown()
            model.onCleared()
            Debug.cancelDebug(true)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `缺书源公开入口触发error且不残loading`() = runBlocking {
        val model = BookSourceDebugScreenModel(
            defaultTextMy = "我的",
            defaultTextFx = "发现",
            systemText = "系统",
            exploreErrorText = { it },
            exploreJsonErrorText = { it.message ?: "" },
        )
        var started = false
        var errored = false
        try {
            model.startDebug(
                key = "发现::@js: 'http://127.0.0.1/list?<t(&x=,m:1,n:2)>'",
                start = {
                    started = true
                    assertTrue(model.uiState.value.loading)
                },
                error = { errored = true },
            )
            assertTrue(started)
            withTimeout(5_000) { model.uiState.first { !it.loading } }
            assertTrue(errored)
            assertTrue(model.uiState.value.exploreOptions.isEmpty())
            assertTrue(requests.isEmpty())
        } finally {
            model.onCleared()
        }
    }

    @Test
    fun `清scope后公开入口同步completion不残loading且不发HTTP`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source()
        AppDbProviders.get().bookSourceDao.insert(source)
        val model = BookSourceDebugScreenModel(
            defaultTextMy = "我的",
            defaultTextFx = "发现",
            systemText = "系统",
            exploreErrorText = { it },
            exploreJsonErrorText = { it.message ?: "" },
        )
        var started = false
        var errored = false
        try {
            model.init(sourceUrl)
            withTimeout(5_000) {
                while (model.bookSource == null) kotlinx.coroutines.delay(20)
            }
            model.onCleared()
            val requestsBefore = requests.size
            model.startDebug(
                key = "发现::@js: '$sourceUrl/list?<t(&x=,m:1,n:2)>'",
                start = {
                    started = true
                    assertTrue(model.uiState.value.loading)
                },
                error = { errored = true },
            )
            assertTrue(started)
            withTimeout(5_000) { model.uiState.first { !it.loading } }
            assertTrue(!errored)
            assertTrue(model.uiState.value.logs.isEmpty())
            assertTrue(model.uiState.value.exploreOptions.isEmpty())
            assertEquals(requestsBefore, requests.size)
        } finally {
            model.onCleared()
            Debug.cancelDebug(true)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `同源旧调试慢任务日志不进入新会话`() = runBlocking {
        startServer(delaySlow = true) { pageOneHtml(next = null) }
        val source = source()
        AppDbProviders.get().bookSourceDao.insert(source)
        fun model() = BookSourceDebugScreenModel(
            defaultTextMy = "我的",
            defaultTextFx = "发现",
            systemText = "系统",
            exploreErrorText = { it },
            exploreJsonErrorText = { it.message ?: "" },
        )
        val old = model()
        val fresh = model()
        try {
            old.init(sourceUrl)
            fresh.init(sourceUrl)
            withTimeout(5_000) {
                while (old.bookSource == null || fresh.bookSource == null) {
                    kotlinx.coroutines.delay(20)
                }
            }
            old.dispatch(
                BookSourceDebugUiEvent.QueryChange(
                    "发现::@js: java.log('old-js-log'); java.logType(123); " +
                        "'$sourceUrl/list?<t(&x=,slow:slow,fast:fast)>'"
                )
            )
            old.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            assertTrue(old.uiState.value.loading)
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS))

            fresh.dispatch(
                BookSourceDebugUiEvent.QueryChange(
                    "发现::@js: java.log('fresh-js-log'); " +
                        "'$sourceUrl/list?<t(&x=,fast:fast,slow:slow)>'"
                )
            )
            fresh.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            withTimeout(8_000) { fresh.uiState.first { it.exploreOptions.isNotEmpty() } }
            // 旧模型被新会话抢占后，其真实 Debug Job 完成必须结束自身 loading。
            withTimeout(5_000) { while (old.uiState.value.loading) kotlinx.coroutines.delay(20) }
            assertTrue(!old.uiState.value.loading)

            releaseSlow.countDown()
            assertTrue(slowFinished.await(5, TimeUnit.SECONDS))
            kotlinx.coroutines.delay(200)
            assertTrue(fresh.uiState.value.logs.none { it.contains("x=slow") })
            assertTrue(fresh.uiState.value.logs.none {
                it.contains("old-js-log")
            })
        } finally {
            old.onCleared()
            fresh.onCleared()
            Debug.cancelDebug(true)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `调试 token 跨页面唯一且旧页清理不会取消新页`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source()
        AppDbProviders.get().bookSourceDao.insert(source)
        fun newModel() = BookSourceDebugScreenModel(
            defaultTextMy = "我的",
            defaultTextFx = "发现",
            systemText = "系统",
            exploreErrorText = { it },
            exploreJsonErrorText = { it.message ?: "" },
        )
        val first = newModel()
        val second = newModel()
        try {
            first.init(sourceUrl)
            second.init(sourceUrl)
            withTimeout(5_000) {
                while (first.bookSource == null || second.bookSource == null) {
                    kotlinx.coroutines.delay(20)
                }
            }
            val key = "发现::@js: '$sourceUrl/list?<t(&x=,m:1,n:2)>'"
            first.dispatch(BookSourceDebugUiEvent.QueryChange(key))
            first.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            withTimeout(8_000) { first.uiState.first { it.exploreOptions.isNotEmpty() } }

            second.dispatch(BookSourceDebugUiEvent.QueryChange(key))
            second.dispatch(BookSourceDebugUiEvent.SubmitQuery)
            withTimeout(8_000) { second.uiState.first { it.exploreOptions.isNotEmpty() } }

            // 旧页销毁晚于新页启动: 只清理旧 token, 不能取消新页当前会话。
            first.onCleared()
            second.dispatch(BookSourceDebugUiEvent.SelectOption("t", "2"))
            withTimeout(8_000) {
                while (
                    second.uiState.value.exploreOptions.singleOrNull()?.selectedValue != "2" ||
                    requests.none { it.startsWith("/list") && it.contains("&x=2") }
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }
        } finally {
            second.onCleared()
            Debug.cancelDebug(true)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `主页 VM 编辑后按新模板重取并发布参数行`() = runBlocking {
        startServer { pageOneHtml(next = null) }
        val source = source()
        AppDbProviders.get().bookSourceDao.insert(source)
        val title = "测试主页_${System.nanoTime()}"
        val section = HomeSection(
            id = "sec_${System.nanoTime()}",
            title = "本地展示",
            sourceUrl = sourceUrl,
            sourceName = source.bookSourceName,
            exploreUrl = "$sourceUrl/list?<t(&x=,m:1,n:2)>",
            exploreName = "本地",
            style = HomeSection.STYLE_COVER_ROW,
        )
        HomeTabHelpShared.addTab(title)
        HomeTabHelpShared.addSection(title, section)
        val vm = HomeViewModelShared(scope)
        try {
            vm.initTab(title)
            withTimeout(8_000) {
                while (vm.sectionBooks(title, section.id).isEmpty()) {
                    kotlinx.coroutines.delay(20)
                }
            }
            withTimeout(5_000) { while (requests.isEmpty()) kotlinx.coroutines.delay(10) }
            assertTrue(requests.last().contains("&x=1"))
            val edited = section.copy(
                exploreUrl = "$sourceUrl/list?<t(&y=,m:1,n:2)>",
                style = HomeSection.STYLE_FOUR_ROW,
            )
            vm.updateSection(title, edited)
            withTimeout(8_000) {
                while (
                    vm.sectionBooks(title, section.id).isEmpty() ||
                    requests.none { it.contains("&y=1") }
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertTrue(vm.selectSectionOption(title, edited, "t", "2"))
            vm.onSectionOptionSelected(title, edited)
            withTimeout(8_000) {
                while (
                    vm.sectionOptions(title, section.id).singleOrNull()?.selectedValue != "2" ||
                    vm.sectionBooks(title, section.id).isEmpty() ||
                    requests.none { it.contains("&y=2") }
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals("2", vm.sectionOptions(title, section.id).single().selectedValue)
        } finally {
            HomeTabHelpShared.removeTab(title)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    @Test
    fun `主页无限流失败后暂停 不会自动重发坏源`() = runBlocking {
        startServer(failPath = "/fail") { pageOneHtml(next = null) }
        val source = source().apply {
            exploreRule = ExploreRule(bookList = "@js: throw new Error('boom')")
        }
        AppDbProviders.get().bookSourceDao.insert(source)
        val title = "测试无限_${System.nanoTime()}"
        val section = HomeSection(
            id = "inf_${System.nanoTime()}",
            title = "无限流",
            sourceUrl = sourceUrl,
            sourceName = source.bookSourceName,
            exploreUrl = "$sourceUrl/fail",
            exploreName = "本地",
            style = HomeSection.STYLE_INFINITE_GRID,
        )
        HomeTabHelpShared.addTab(title)
        HomeTabHelpShared.addSection(title, section)
        val vm = HomeViewModelShared(scope)
        try {
            vm.initTab(title)
            withTimeout(8_000) {
                while (!vm.isInfinitePaused(title)) kotlinx.coroutines.delay(20)
            }
            assertTrue(!vm.hasMoreInfinite(title))
            assertTrue(requests.any { it.startsWith("/fail") })
            val before = requests.size
            vm.loadInfinite(title)
            vm.loadInfinite(title)
            vm.loadInfinite(title)
            kotlinx.coroutines.delay(200)
            assertEquals(before, requests.size)
        } finally {
            HomeTabHelpShared.removeTab(title)
            AppDbProviders.get().bookSourceDao.delete(source)
        }
    }

    private class TestSelectFilter(
        name: String,
        values: Array<String>,
    ) : Filter.Select<String>(name, values)

    private fun filterField(
        filter: TestSelectFilter,
        onSelect: (Int) -> Unit = {},
    ) = AppChoiceField(
        fieldKey = filter,
        definitionKey = filter.values.toList(),
        title = filter.name,
        options = filter.values.toList(),
        selectedIndex = 0,
        onSelect = onSelect,
    )

    private fun multiField(
        title: String,
        key: String,
        label: String,
        definition: List<Pair<String, String>> = listOf(label to "raw"),
    ) = AppMultiChoiceField(
        fieldKey = key,
        definitionKey = definition,
        title = title,
        options = listOf(label),
        selectedIndexes = emptySet(),
        onSelectionChange = {},
    )

    // ─────────────────────────── 受控网页与 VM 观察 ───────────────────────────

    private fun startServer(
        delaySlow: Boolean = false,
        delayPageTwo: Boolean = false,
        failPath: String? = null,
        queryHtml: ((String) -> String)? = null,
        listHtml: () -> String,
    ) {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server = httpServer
        executor = pool
        httpServer.executor = pool
        httpServer.createContext("/") handler@ { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            requests.add("${exchange.requestURI.path}?$query")
            if (failPath != null && exchange.requestURI.path.startsWith(failPath)) {
                val bytes = "server error".toByteArray()
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                if (delaySlow && query.contains("x=slow")) {
                    slowStarted.countDown()
                    if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                        slowGateTimedOut = true
                        slowFinished.countDown()
                        val bytes = "slow gate timeout".toByteArray()
                        exchange.sendResponseHeaders(500, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                        exchange.close()
                        return@handler
                    }
                }
                if (delayPageTwo && query.contains("page=2")) {
                    pageTwoStarted.countDown()
                    if (!releasePageTwo.await(5, TimeUnit.SECONDS)) {
                        pageTwoGateTimedOut = true
                        pageTwoFinished.countDown()
                        val bytes = "pageTwo gate timeout".toByteArray()
                        exchange.sendResponseHeaders(500, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                        exchange.close()
                        return@handler
                    }
                }
                val html = queryHtml?.invoke(query) ?: when {
                    exchange.requestURI.path.startsWith("/list2") -> pageTwoHtml()
                    query.contains("page=2") -> pageTwoHtml()
                    delaySlow && query.contains("x=slow") -> pageOneHtml(next = null, name = "慢书")
                    else -> listHtml()
                }
                val bytes = html.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                try {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    if (delaySlow && query.contains("x=slow")) slowFinished.countDown()
                    if (delayPageTwo && query.contains("page=2")) pageTwoFinished.countDown()
                }
            }
        }
        httpServer.start()
    }

    private fun source() = BookSource(
        bookSourceUrl = sourceUrl,
        bookSourceName = "本地",
    ).apply {
        exploreRule = ExploreRule(
            bookList = "class.item",
            name = "class.name@text",
            author = "class.author@text",
            bookUrl = "class.name@href",
            nextPageUrl = "class.next@href",
        )
    }

    private fun sameBookHtml(bookUrl: String): String =
        "<html><body><div class=\"item\"><a class=\"name\" href=\"$bookUrl\">同书</a>" +
            "<span class=\"author\">同作者</span></div></body></html>"

    private fun pageOneHtml(next: String?, name: String = "书A"): String = buildString {
        append("<html><body>")
        append("<div class=\"item\"><a class=\"name\" href=\"/book/1\">$name</a>")
        append("<span class=\"author\">作者A</span></div>")
        if (next != null) append("<a class=\"next\" href=\"$next\">下一页</a>")
        append("</body></html>")
    }

    private fun pageTwoHtml(): String =
        "<html><body><div class=\"item\"><a class=\"name\" href=\"/book/2\">书B</a>" +
            "<span class=\"author\">作者B</span></div></body></html>"

    private fun collectBooks(vm: ExploreShowViewModelShared): Channel<List<SearchBook>> {
        val channel = Channel<List<SearchBook>>(Channel.UNLIMITED)
        scope.launch {
            vm.pageState.collect { page ->
                if (page.requestId > 0 && !page.loading && page.error == null) {
                    channel.trySend(page.books)
                }
            }
        }
        return channel
    }

    private fun collectErrors(vm: ExploreShowViewModelShared): Channel<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        scope.launch {
            vm.pageState.collect { page -> page.error?.let { channel.trySend(it) } }
        }
        return channel
    }

    private suspend fun Channel<List<SearchBook>>.receiveBookList(): List<SearchBook> =
        withTimeout(5_000) { receiveCatching().getOrThrow() }

    private suspend fun Channel<List<SearchBook>>.receiveNonEmptyBookList(): List<SearchBook> =
        withTimeout(5_000) {
            while (true) {
                val books = receiveCatching().getOrThrow()
                if (books.isNotEmpty()) return@withTimeout books
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList<SearchBook>()
        }

    private suspend fun Channel<String>.receiveError(): String =
        withTimeout(5_000) { receiveCatching().getOrThrow() }
}
