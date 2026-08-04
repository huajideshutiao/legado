package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [String.toBrowseUri] 回归: 书源 URL 未百分号编码 (中文/空格) 时, 单参 `URI(url)`
 * 构造抛 URISyntaxException, 曾导致桌面端 `openUrl` 确认后/`browseUrl` 降级路径
 * "点了没反应"。分段解析必须: 非法字符被 quote、合法字符与已编码内容原样保留。
 */
class BrowseUrlTest {

    @Test
    fun `中文参数和空格被编码`() {
        val uri = "https://www.baidu.com/s?wd=你好 世界&rn=20".toBrowseUri()
        assertTrue(uri != null)
        assertEquals(
            "https://www.baidu.com/s?wd=%E4%BD%A0%E5%A5%BD%20%E4%B8%96%E7%95%8C&rn=20",
            uri.toString()
        )
    }

    @Test
    fun `已编码 URL 原样保留`() {
        val url = "https://example.com/search?q=a%20b&c=1"
        val uri = url.toBrowseUri()
        assertTrue(uri != null)
        assertEquals(url, uri.toString())
    }

    @Test
    fun `严格合法 URL 不二次编码`() {
        val url = "https://example.com/path/x%2Fy?q=1&r=%2F"
        val uri = url.toBrowseUri()
        assertTrue(uri != null)
        assertEquals(url, uri.toString())
    }

    @Test
    fun `保留字符原样保留`() {
        val url = "https://example.com/a/b?x=1&y=2#frag"
        val uri = url.toBrowseUri()
        assertTrue(uri != null)
        assertEquals(url, uri.toString())
    }

    @Test
    fun `无 scheme 返回 null`() {
        assertNull("www.baidu.com/s?wd=1".toBrowseUri())
    }

    @Test
    fun `特殊 scheme 保留`() {
        assertEquals("mailto:foo@bar.com", "mailto:foo@bar.com".toBrowseUri().toString())
    }

    @Test
    fun `路径中的未编码大括号被编码`() {
        // 书源 URL 常带 {post} 等选项串残留或非法字符
        val uri = "https://example.com/x{1}/y".toBrowseUri()
        assertTrue(uri != null)
        assertEquals("https://example.com/x%7B1%7D/y", uri.toString())
    }
}
