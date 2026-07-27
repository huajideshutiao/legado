package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64 as JavaBase64
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64 as KotlinBase64

/**
 * StringUtils.compress 的 base64 语义锁定测试。
 *
 * 原实现走 android.util.Base64(NO_WRAP)，下沉 shared 后换 kotlin.io.encoding.Base64(Default)。
 * 三方同构:标准字母表 + '=' padding + 无换行(android NO_WRAP ≡ java basic ≡ kotlin Default)。
 *
 * 测试 1 锁定这一编码等价(下沉的真实不变量):对三组 gzip 产物,kotlin Base64.Default.encode
 * 与 java.util.Base64.basic 逐串一致,且等于硬编码 oracle 向量。
 *
 * 测试 2 钉住 compress 的实际当前输出。注意:compress 在 finally 前(gzip.close 之前)就
 * 取 out.toByteArray(),故只含 10 字节 gzip 头、不含压缩体——这是先于本次下沉的既有缺陷
 * (compress 无调用方,死代码,故从未暴露)。本批只做 Base64 无损平移,不修此逻辑,
 * 因此三组输入的实际输出恒为同一头串,锁死以证明平移未改变字节。
 */
class StringUtilsCodecTest {

    private val s1 = "abc"                          // 短串
    private val s2 = "简繁转换与压缩往返测试"        // 中文多字节
    private val s3 = "书源规则".repeat(200)          // 长串

    // java.util.Base64.basic 对「正确 gzip(close 后) 产物」的 oracle 向量
    private val v1 = "H4sIAAAAAAAA/0tMSgYAwkEkNQMAAAA="
    private val v2 = "H4sIAAAAAAAA/wEhAN7/566A57mB6L2s5o2i5LiO5Y6L57yp5b6A6L+U5rWL6K+VU4CvICEAAAA="
    private val v3 = "H4sIAAAAAAAA/3uyc9mzXRNeLG952jHzySh7lD3KHmWPskfZo+xR9iibYjYA0h9oNWAJAAA="

    /** 正确 gzip:写完 close 后再取字节(含压缩体+trailer)。 */
    private fun gzip(str: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(str.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `base64 编码 kotlin Default 与 java basic 同构且匹配向量`() {
        for ((s, v) in listOf(s1 to v1, s2 to v2, s3 to v3)) {
            val bytes = gzip(s)
            val kt = KotlinBase64.Default.encode(bytes)
            val java = JavaBase64.getEncoder().encodeToString(bytes)
            assertEquals("kotlin 与 java basic 应逐串一致", java, kt)
            assertEquals("应匹配硬编码 oracle 向量", v, kt)
        }
    }

    @Test
    fun `compress 钉住实际当前输出(既有 close 顺序缺陷,仅头串)`() {
        // 既有缺陷:取字节早于 gzip.close,只含 10 字节 gzip 头;本批不修,仅锁字节不变
        val headerOnly = "H4sIAAAAAAAA/w=="
        assertEquals(headerOnly, StringUtils.compress(s1).getOrThrow())
        assertEquals(headerOnly, StringUtils.compress(s2).getOrThrow())
        assertEquals(headerOnly, StringUtils.compress(s3).getOrThrow())
    }

    @Test
    fun `空串直接返回空串`() {
        assertEquals("", StringUtils.compress("").getOrThrow())
    }
}
