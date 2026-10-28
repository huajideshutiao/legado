package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.URI

/**
 * [String.canonicalizeUri] 回归: native 端（Darwin / @ohos.net.http）要求交给引擎的 url
 * 逐段满足 RFC 3986 字符集，且 `%` 后必须紧跟两位十六进制 —— 否则 Apple NSURLComponents
 * 抛 NSInvalidArgumentException（Kotlin/Native 捕不到 → 闪退），鸿蒙返回 URL 错误码。
 *
 * 断言面 = 「语法合法性」：非法字符被转义、成套 `%XX` 不二次转义、合法字符原样保留、
 * 结构（scheme/authority/query/fragment）不被打散、幂等。语义规范化（默认端口移除、
 * dot-segment 折叠等）不在本函数职责内，故也不断言。
 */
class UriCanonicalTest {

    /**
     * 输出不变式: 不论输入多脏, 结果必须能被严格解析器接受, 且二次调用不变。
     * 参数化覆盖边界形态 (IPv6 残尾、userinfo 多冒号、控制符、非 http scheme)。
     */
    @Test
    fun `输出恒为严格可解析的 URI 且幂等`() {
        val inputs = listOf(
            "http://u:p:q@a.com/",
            "http://[::1]x/",
            "http://[fe80::1%25en0]/",
            "https://a.com/a b?q=中%zz#f g",
            "mailto:a b@c.com",
            "  https://a.com/x\n",
        )
        for (input in inputs) {
            val once = input.canonicalizeUri()
            assertNotNull("必须是严格可解析的 URI: $input -> $once", URI(once))
            assertEquals("二次合法化必须不变: $input", once, once.canonicalizeUri())
        }
    }

    @Test
    fun `裸百分号转为 %25`() {
        assertEquals("https://a.com/p?q=100%25", "https://a.com/p?q=100%".canonicalizeUri())
        assertEquals("https://a.com/%25zz", "https://a.com/%zz".canonicalizeUri())
        assertEquals("https://a.com/50%25", "https://a.com/50%".canonicalizeUri())
    }

    @Test
    fun `成套百分号编码原样保留`() {
        val url = "https://a.com/s?q=a%20b&r=%2Fc%3F"
        assertEquals(url, url.canonicalizeUri())
        // 小写十六进制同样成套, 不改写大小写 (不属语法合法性)
        assertEquals("https://a.com/%e4%bd%a0", "https://a.com/%e4%bd%a0".canonicalizeUri())
    }

    /**
     * 搜索请求实况: 关键词已由 URL 参数编码器写成 `%XX` 后随 query 交给合法化,
     * 每一段必须原样放行。回归点: 不跳过整段三元组时 `%E7` 会被再追加成 `%E7E7`,
     * 关键词变成乱码字节 → 站点返回空结果页 (iOS/鸿蒙全源搜索无结果)。
     */
    @Test
    fun `已编码的搜索关键词不被二次追加`() {
        val url = "https://a.com/search?q=%E7%88%B1%E6%83%85&page=1"
        assertEquals(url, url.canonicalizeUri())
    }

    @Test
    fun `路径段内非法字符与空格被转义`() {
        assertEquals(
            "https://a.com/a%20b%7Bc%7D%7Cd%5Be%5D%5Ef%60g%5Ch",
            "https://a.com/a b{c}|d[e]^f`g\\h".canonicalizeUri()
        )
    }

    @Test
    fun `中文按 UTF-8 百分号编码`() {
        assertEquals(
            "https://a.com/s?wd=%E4%BD%A0%E5%A5%BD%20%E4%B8%96%E7%95%8C&rn=20",
            "https://a.com/s?wd=你好 世界&rn=20".canonicalizeUri()
        )
    }

    @Test
    fun `查询与片段的保留字符不动`() {
        val url = "https://a.com/s?q=a&b=c;d,e:f/g?h#frag?x=a&b"
        assertEquals(url, url.canonicalizeUri())
    }

    @Test
    fun `userinfo 与端口保留`() {
        assertEquals(
            "http://u%20s:p%40ss@a.com:8080/p",
            "http://u s:p@ss@a.com:8080/p".canonicalizeUri()
        )
        // 非数字端口: ':' 不可能是端口分隔符, 按 host 字符转义
        assertEquals("http://a.com%3Afoo/p", "http://a.com:foo/p".canonicalizeUri())
    }

    @Test
    fun `IPv6 字面量方括号与 zone id 保留`() {
        assertEquals("http://[::1]:8080/p%20a", "http://[::1]:8080/p a".canonicalizeUri())
        assertEquals(
            "http://[fe80::1%25en0]/",
            "http://[fe80::1%25en0]/".canonicalizeUri()
        )
    }

    @Test
    fun `无 scheme 与协议相对地址按 path 处理`() {
        assertEquals("//a.com/x%20y", "//a.com/x y".canonicalizeUri())
        assertEquals("a.com/x%20y", "a.com/x y".canonicalizeUri())
    }

    @Test
    fun `不透明 URI 不动分隔符`() {
        assertEquals("mailto:a%20b@c.com", "mailto:a b@c.com".canonicalizeUri())
        assertEquals(
            "data:image/png;base64,iVBORw0KGgo=",
            "data:image/png;base64,iVBORw0KGgo=".canonicalizeUri()
        )
    }

    @Test
    fun `首尾空白与控制符被剥离`() {
        assertEquals("https://a.com/x", "  https://a.com/x\n".canonicalizeUri())
        assertEquals("https://a.com/a%20b", "https://a.com/a b   ".canonicalizeUri())
    }

    @Test
    fun `幂等`() {
        listOf(
            "https://a.com/a b{c}|d[e]?q=100%&wd=你好 世界",
            "http://u s:p@ss@a.com:8080/p",
            "http://[fe80::1%25en0]/",
            "https://a.com/%zz",
            "//a.com/x y",
        ).forEach { input ->
            val once = input.canonicalizeUri()
            assertEquals("二次合法化必须不变: $input", once, once.canonicalizeUri())
        }
    }
}
