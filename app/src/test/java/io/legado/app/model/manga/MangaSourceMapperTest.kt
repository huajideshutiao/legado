package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 插件源虚拟书源 URL 身份编解码契约 (书架归属与缓存键的稳定性依赖它)。
 */
class MangaSourceMapperTest {

    @Test
    fun `sourceUrl 与 sourceId 双向往返`() {
        val id = 6289731484943315811L
        val url = MangaSourceMapper.sourceUrlOf(id)
        assertEquals("tachiyomi://6289731484943315811", url)
        assertEquals(id, MangaSourceMapper.sourceIdOf(url))
    }

    @Test
    fun `sourceIdOf 非插件前缀与非法 id 返回 null`() {
        assertNull(MangaSourceMapper.sourceIdOf("https://example.com"))
        assertNull(MangaSourceMapper.sourceIdOf("tachiyomi://"))
        assertNull(MangaSourceMapper.sourceIdOf("tachiyomi://abc"))
    }

    @Test
    fun `bookUrl 与 mangaUrl 双向往返 (中文与特殊字符)`() {
        val sourceId = 12345L
        for (mangaUrl in listOf(
            "https://example.com/manga/123",
            "https://example.com/漫画/测试?page=1&sort=asc",
            "https://example.com/a b/#frag?x=%20",
        )) {
            val bookUrl = MangaSourceMapper.bookUrlOf(sourceId, mangaUrl)
            assertEquals(mangaUrl, MangaSourceMapper.mangaUrlOf(bookUrl, sourceId))
        }
    }

    @Test
    fun `mangaUrlOf 源 id 不匹配或非插件 bookUrl 返回 null`() {
        val bookUrl = MangaSourceMapper.bookUrlOf(1L, "https://example.com/a")
        assertNull(MangaSourceMapper.mangaUrlOf(bookUrl, 2L))
        assertNull(MangaSourceMapper.mangaUrlOf("https://example.com/book", 1L))
    }

    @Test
    fun `toSearchBook 映射书源身份与类型`() {
        val originUrl = MangaSourceMapper.sourceUrlOf(42L)
        val manga = SManga.create().apply {
            url = "https://example.com/m/9"
            title = "测试漫画"
            author = "作者"
            description = "简介"
            thumbnail_url = "https://img.example.com/c.jpg"
        }
        val searchBook = with(MangaSourceMapper) {
            manga.toSearchBook(originUrl, "测试源")
        }
        assertEquals(MangaSourceMapper.bookUrlOf(42L, manga.url), searchBook.bookUrl)
        assertEquals(originUrl, searchBook.origin)
        assertEquals("测试源", searchBook.originName)
        assertEquals("测试漫画", searchBook.name)
        assertEquals("作者", searchBook.author)
        assertEquals("简介", searchBook.intro)
        assertEquals("https://img.example.com/c.jpg", searchBook.coverUrl)
        assertEquals(searchBook.bookUrl, searchBook.tocUrl)
    }
}
