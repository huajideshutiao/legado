package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 书源校验集成测试。
 *
 * 模拟"校验所选"流程 (参考 CheckSourceService.doCheckSource):
 * 1. 取数据库里第一个书源
 * 2. 校验搜索: WebBook.getBookListAwait(source, searchWord)
 * 3. 校验发现: source.exploreKinds().first -> WebBook.getBookListAwait(source, url, isSearch = false)
 *
 * 目的: 验证 rhino -> quickjs 迁移后,发现页/搜索解析是否正常,
 *      无需手动介入即可定位问题。
 *
 * 运行方式:
 * .\gradlew :app:connectedAppDebugAndroidTest --console=plain 2>&1 | Tee-Object -FilePath "app\test_output.txt"
 */
@RunWith(AndroidJUnit4::class)
class BookSourceCheckTest {

    private lateinit var source: BookSource

    @Before
    fun setup() {
        // 取数据库里第一个书源 (按 customOrder 升序)
        val sources = appDb.bookSourceDao.all
        assertFalse("数据库里没有书源,请先导入书源", sources.isEmpty())
        source = sources.first()
        println("==== 测试书源: ${source.bookSourceName} (${source.bookSourceUrl}) ====")
        println("searchUrl: ${source.searchUrl}")
        println("exploreUrl: ${source.exploreUrl?.take(200)}")
        println("searchRule.bookList: ${source.searchRule.bookList?.take(200)}")
        println("exploreRule.bookList: ${source.exploreRule.bookList?.take(200)}")
    }

    /**
     * 校验搜索: 和 CheckSourceService 一样的流程
     */
    @Test
    fun testSearch() = runBlocking {
        if (source.searchUrl.isNullOrBlank()) {
            println("==== 搜索: searchUrl 为空,跳过 ====")
            return@runBlocking
        }
        val searchWord = source.getCheckKeyword("我的")
        println("==== 搜索开始, 关键字: $searchWord ====")
        try {
            val page = WebBook.getBookListAwait(source, searchWord)
            println("==== 搜索结果: ${page.books.size} 本 ====")
            page.books.take(3).forEachIndexed { i, sb ->
                println("  [$i] name=${sb.name} author=${sb.author} bookUrl=${sb.bookUrl}")
            }
            assertTrue("搜索返回空列表", page.books.isNotEmpty())
            // 校验第一本书的字段解析
            val first = page.books.first()
            assertNotNull("搜索结果 name 为空", first.name)
            assertNotNull("搜索结果 bookUrl 为空", first.bookUrl)
            println("==== 搜索通过 ====")
        } catch (e: Throwable) {
            println("==== 搜索失败: ${e.javaClass.name}: ${e.message} ====")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 校验发现: 和 CheckSourceService 一样的流程
     */
    @Test
    fun testExplore() = runBlocking {
        if (source.exploreUrl.isNullOrBlank()) {
            println("==== 发现: exploreUrl 为空,跳过 ====")
            return@runBlocking
        }
        println("==== 发现开始 ====")
        try {
            val kinds = source.exploreKinds()
            println("==== 发现分类: ${kinds.size} 个 ====")
            kinds.take(5).forEachIndexed { i, k ->
                println("  [$i] title=${k.title} url=${k.url?.take(100)}")
            }
            val url = kinds.firstOrNull { !it.url.isNullOrBlank() }?.url
            assertNotNull("没有有效的发现 url", url)
            println("==== 发现请求 url: $url ====")
            val page = WebBook.getBookListAwait(source, url!!, isSearch = false)
            println("==== 发现结果: ${page.books.size} 本 ====")
            page.books.take(3).forEachIndexed { i, sb ->
                println("  [$i] name=${sb.name} author=${sb.author} bookUrl=${sb.bookUrl}")
            }
            assertTrue("发现返回空列表", page.books.isNotEmpty())
            val first = page.books.first()
            assertNotNull("发现结果 name 为空", first.name)
            assertNotNull("发现结果 bookUrl 为空", first.bookUrl)
            println("==== 发现通过 ====")
        } catch (e: Throwable) {
            println("==== 发现失败: ${e.javaClass.name}: ${e.message} ====")
            e.printStackTrace()
            throw e
        }
    }
}
