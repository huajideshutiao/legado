package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * 纯 Kotlin Md5Digest 对照 java MessageDigest oracle 的向量测试。
 */
class MD5UtilsTest {

    private fun oracle(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun oracle(str: String): String = oracle(str.toByteArray())

    @Test
    fun `md5Encode 向量对照 MessageDigest`() {
        val vectors = listOf(
            "", // 空串（RFC 1321: d41d8cd98f00b204e9800998ecf8427e）
            "a",
            "abc",
            "message digest",
            "书源规则解析", // 中文多字节
            "第1章 起点：混合ASCII与中文🌟", // 代理对
            "x".repeat(55), // 补位边界：55 字节（pad 到单块极限）
            "y".repeat(56), // 补位边界：56 字节（跨块）
            "z".repeat(64), // 整块
            "灵".repeat(10000), // 长文多块
        )
        for (v in vectors) {
            assertEquals("md5Encode(${v.take(20)}...)", oracle(v), MD5Utils.md5Encode(v))
        }
        assertEquals("null 入参返回空串", "", MD5Utils.md5Encode(null))
        assertEquals("RFC1321 空串向量", "d41d8cd98f00b204e9800998ecf8427e", MD5Utils.md5Encode(""))
    }

    @Test
    fun `md5Encode16 为全量 8-24 截段`() {
        val v = "legado"
        assertEquals(oracle(v).substring(8, 24), MD5Utils.md5Encode16(v))
    }

    @Test
    fun `InputStream 流式重载与整体编码一致`() {
        val bytes = ByteArray(100_003) { (it * 31 and 0xff).toByte() } // 非 8192 整数倍，覆盖尾块
        assertEquals(oracle(bytes), MD5Utils.md5Encode(bytes.inputStream()))
    }
}
