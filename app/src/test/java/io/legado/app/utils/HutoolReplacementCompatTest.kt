package io.legado.app.utils

import cn.hutool.core.codec.Base64 as HutoolBase64
import cn.hutool.core.codec.PercentCodec as HutoolPercentCodec
import cn.hutool.core.lang.Validator
import cn.hutool.core.net.RFC3986
import cn.hutool.core.net.URLDecoder as HutoolURLDecoder
import cn.hutool.core.net.URLEncodeUtil
import io.legado.app.lib.webdav.UrlPathDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hutool 内用剥离的向量守护：自有实现与 hutool 原实现逐向量对拍。
 * 覆盖 Base64Lenient / PercentCodec / UrlPathDecoder / IP 校验正则。
 */
class HutoolReplacementCompatTest {

    private val printableAscii = buildString { for (c in ' '..'~') append(c) }

    @Test
    fun lenientBase64MatchesHutool() {
        val vectors = listOf(
            "", "TWFu", "TQ==", "TWE=", "TWFuTQ", "TWFuQ",
            "SGVsbG8sIOS4lueVjA==",
            "SGVsbG8s\r\nIOS4lueVjA==", "  TWFu  ",
            "PDw_Pz8-Pg==", "-_-_", "a-b_c",   // URL-safe 字母表
            "=TWFu=",                          // 中途 padding 被忽略
            "###aGk=###", "无效字符TWFu",
        )
        for (v in vectors) {
            assertArrayEquals("decode($v)", HutoolBase64.decode(v), Base64Lenient.decode(v))
            assertEquals("decodeStr($v)", HutoolBase64.decodeStr(v), Base64Lenient.decodeStr(v))
            assertEquals(
                "decodeStr($v, GBK)",
                HutoolBase64.decodeStr(v, charset("GBK")),
                Base64Lenient.decodeStr(v, charset("GBK"))
            )
        }
        assertNull(Base64Lenient.decodeStr(null))
    }

    @Test
    fun queryCodecMatchesHutoolEncodeQuery() {
        val vectors = listOf(
            printableAscii, "中文 空格", "emoji😀x", "a+b&c=d?e/f#g",
            "'single'\"double\"", "~-._", "%already%20encoded", "line\nbreak\ttab",
        )
        for (v in vectors) {
            assertEquals(URLEncodeUtil.encodeQuery(v), PercentCodec.QUERY.encode(v, Charsets.UTF_8))
            assertEquals(
                RFC3986.QUERY.encode(v, charset("GBK")),
                PercentCodec.QUERY.encode(v, charset("GBK"))
            )
        }
    }

    @Test
    fun analyzeUrlQueryEncoderMatchesHutool() {
        // 与 AnalyzeUrl.queryEncoder 完全相同的构造式
        val hutool = RFC3986.UNRESERVED.orNew(HutoolPercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}"))
        val ours = PercentCodec.UNRESERVED.orNew(PercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}"))
        val vectors = listOf(printableAscii, "key=中文&x=1 2", "emoji😀", "#hash'quote\"")
        for (v in vectors) {
            assertEquals(hutool.encode(v, Charsets.UTF_8), ours.encode(v, Charsets.UTF_8))
        }
    }

    @Test
    fun webdavPathDecodeMatchesHutool() {
        val vectors = listOf(
            "", "/dav/%E4%B8%AD%E6%96%87/%e4%b9%a6.epub",
            "a+b%20c",                       // '+' 不转空格
            "100%25", "%", "abc%", "%zz", "%4", "%4g", "%%41",
            "café%20x", "abc%E4%B8%ADdef",   // hex 字符与 %XX 同段解码
            "/a/b/c.txt", "%2F%2f",
        )
        for (v in vectors) {
            assertEquals(
                "decode($v)",
                HutoolURLDecoder.decodeForPath(v, Charsets.UTF_8),
                UrlPathDecoder.decode(v, Charsets.UTF_8)
            )
        }
    }

    @Test
    fun ipValidationMatchesHutool() {
        val v4Vectors = listOf(
            "1.2.3.4", "255.255.255.255", "192.168.1.1", "256.1.1.1", "1.2.3",
            "1.2.3.4.5", "01.2.3.4", "a.b.c.d", "192.168.001.1", "10.0.0.01", "", null,
        )
        for (v in v4Vectors) {
            val expected = !v.isNullOrEmpty() && v[0] in '1'..'9'
                    && v.count { it == '.' } == 3 && Validator.isIpv4(v)
            assertEquals("isIPv4($v)", expected, NetworkUtils.isIPv4Address(v))
        }
        val v6Vectors = listOf(
            "::1", "::", "1::", "2001:db8::1", "fe80::1%eth0", "::ffff:192.168.1.1",
            "1:2:3:4:5:6:7:8", "g::1", "1.2.3.4", "2001:db8:::1", "12345::1",
            "fe80:0:0:0:0:0:0:1", "", null,
        )
        for (v in v6Vectors) {
            val expected = v != null && v.contains(":") && Validator.isIpv6(v)
            assertEquals("isIPv6($v)", expected, NetworkUtils.isIPv6Address(v))
        }
    }
}
