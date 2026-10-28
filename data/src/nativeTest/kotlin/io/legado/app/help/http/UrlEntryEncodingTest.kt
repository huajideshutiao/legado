package io.legado.app.help.http

import io.legado.app.help.http.NativeUrlEntryEncoding.NativeQueryEntry
import io.legado.app.model.analyzeRule.encodeUrlParams
import io.legado.app.utils.textCharsetCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * native 端 URL 构建器编码语义 (OkHttp 5.4.0 canonicalize 等价 + URLEncoder 等价 + charset 接入)。
 *
 * 断言期望按一手定义逐字符推演:
 * - okhttp3.internal.url FORM_ENCODE_SET / QUERY_ENCODE_SET / QUERY_COMPONENT_ENCODE_SET /
 *   QUERY_COMPONENT_REENCODE_SET (parent-5.4.0 -Url.kt);
 * - java.net.URLEncoder safe 表 (alnum 与 ` .-_ *`, 空格→`+`);
 * - JVM Charset.forName 别名拒绝 (OpenJDK charsets 表);
 * - hutool 5.8.22 PercentCodec.encode「循环外一个 OutputStreamWriter」: 编码器状态跨整个
 *   encode() 调用 (UTF-16 BOM 仅一次; UTF-32 全系无 BOM), 段尾孤立 high 代理即时按
 *   replacement 替换 (archive writer 未 close 会静默丢失/跨段拼接, 原版明显缺陷不复刻)。
 *
 * 平台 CJK 转换 (CFStringGetBytes / @ohos.util TextCodec 桥) 的实际字节需实机验证,
 * 本文件仅覆盖进程内可判定的入口语义与结构。
 */
class UrlEntryEncodingTest {

    // ===== OkHttp FormBody 入口 =====

    @Test
    fun formAddEncodesFormEncodeSet() {
        assertEquals("a+b", NativeUrlEntryEncoding.encodeFormComponent("a b"))
        assertEquals("a%2Bb", NativeUrlEntryEncoding.encodeFormComponent("a+b"))
        assertEquals("a%26b%3Dc", NativeUrlEntryEncoding.encodeFormComponent("a&b=c"))
        // '*' 不在 FORM_ENCODE_SET, 保留; '~' 在集合内, 编码
        assertEquals("a*b", NativeUrlEntryEncoding.encodeFormComponent("a*b"))
        assertEquals("a%7Eb", NativeUrlEntryEncoding.encodeFormComponent("a~b"))
        // 非 ASCII 按 UTF-8 字节
        assertEquals("%E4%B8%AD", NativeUrlEntryEncoding.encodeFormComponent("中"))
    }

    @Test
    fun formAddEncodedKeepsPercentAndPlus() {
        assertEquals("a%20b", NativeUrlEntryEncoding.encodeFormEncodedComponent("a%20b"))
        // strict=false: 不成套 % 同样保留 (最终合法性由 canonicalizeUri 分层处理)
        assertEquals("a%E7b", NativeUrlEntryEncoding.encodeFormEncodedComponent("a%E7b"))
        assertEquals("a+b", NativeUrlEntryEncoding.encodeFormEncodedComponent("a+b"))
        // 已编码入口仅豁免 `%` 与 `+`, 其余 FORM_ENCODE_SET 字符仍重编码
        assertEquals("a+b+2%26", NativeUrlEntryEncoding.encodeFormEncodedComponent("a b+2&"))
        // 空格仍写 '+'
        assertEquals("a+b", NativeUrlEntryEncoding.encodeFormEncodedComponent("a b"))
        // \t\n\f\r 跳过
        assertEquals("abcde", NativeUrlEntryEncoding.encodeFormEncodedComponent("a\tb\nc\u000Cd\re"))
    }

    // ===== OkHttp HttpUrl.Builder query 入口 =====

    @Test
    fun queryAddComponentEncodesComponentEncodeSet() {
        assertEquals("a%20b", NativeUrlEntryEncoding.encodeQueryComponent("a b"))
        assertEquals("a%25b", NativeUrlEntryEncoding.encodeQueryComponent("a%b"))
        assertEquals("a%2Bb", NativeUrlEntryEncoding.encodeQueryComponent("a+b"))
        // '*' 不在 QUERY_COMPONENT_ENCODE_SET, 保留; '/' 在集合内, 编码
        assertEquals("a*b", NativeUrlEntryEncoding.encodeQueryComponent("a*b"))
        assertEquals("a%2Fb", NativeUrlEntryEncoding.encodeQueryComponent("a/b"))
        assertEquals("%E4%B8%AD", NativeUrlEntryEncoding.encodeQueryComponent("中"))
    }

    @Test
    fun queryAddEncodedComponentReencodesOnlyReencodeSet() {
        assertEquals("%E7", NativeUrlEntryEncoding.encodeQueryEncodedComponent("%E7"))
        assertEquals("a+b", NativeUrlEntryEncoding.encodeQueryEncodedComponent("a+b"))
        // 空格在 REENCODE_SET 内 → %20
        assertEquals("a%20b", NativeUrlEntryEncoding.encodeQueryEncodedComponent("a b"))
        assertEquals("a%26b%3Dc", NativeUrlEntryEncoding.encodeQueryEncodedComponent("a&b=c"))
        assertEquals("a%22b", NativeUrlEntryEncoding.encodeQueryEncodedComponent("a\"b"))
        // ':' '/' '*' 等 REENCODE_SET 外字符保留
        assertEquals("a:b/c*d", NativeUrlEntryEncoding.encodeQueryEncodedComponent("a:b/c*d"))
    }

    @Test
    fun querySegmentKeepsEncodedAndPlus() {
        // 整段 query (parse 与 encodedQuery 入口): `=` 不在 QUERY_ENCODE_SET, 值内 `=` 原样保留
        assertEquals("a%20b%E7&c=d", NativeUrlEntryEncoding.encodeQuerySegment("a b%E7&c=d"))
        assertEquals("k=a+b", NativeUrlEntryEncoding.encodeQuerySegment("k=a+b"))
        assertEquals("q=a=b", NativeUrlEntryEncoding.encodeQuerySegment("q=a=b"))
    }

    // ===== query 名值拆分/回拼 (OkHttp toQueryNamesAndValues / toQueryString) =====

    @Test
    fun splitQueryFollowsOkHttpSemantics() {
        assertEquals(
            listOf<Pair<String, String?>>("a" to "1", "b" to null, "c" to "2=3", "" to null),
            NativeUrlEntryEncoding.splitQuery("a=1&b&c=2=3&"),
        )
        assertEquals(listOf<Pair<String, String?>>("" to null), NativeUrlEntryEncoding.splitQuery(""))
    }

    @Test
    fun joinQueryOmitsEqualsForNullValues() {
        assertEquals(
            "a=1&b&c=",
            NativeUrlEntryEncoding.joinQuery(
                listOf(
                    NativeQueryEntry("a", "1"),
                    NativeQueryEntry("b", null),
                    NativeQueryEntry("c", ""),
                )
            ),
        )
    }

    @Test
    fun parseBuildRoundTripKeepsValueEquals() {
        // OkHttp parse→toString 字节往返不变 (值内 `=`、已编码三元组、`+` 原样)
        val query = "q=a=b&w=%E7%A6%85&p=a+b&noval"
        val rebuilt = NativeUrlEntryEncoding.joinQuery(
            NativeUrlEntryEncoding.splitQuery(NativeUrlEntryEncoding.encodeQuerySegment(query))
                .map { (n, v) -> NativeQueryEntry(n, v) }
        )
        assertEquals(query, rebuilt)
    }

    // ===== URLEncoder 等价 (encodeUrlParams 表单路径) =====

    @Test
    fun urlEncoderEncodeFollowsJdkSafeTable() {
        assertEquals("a+b", NativeUrlEntryEncoding.urlEncoderEncode("a b") { it.encodeToByteArray() })
        assertEquals("a%2Bb", NativeUrlEntryEncoding.urlEncoderEncode("a+b") { it.encodeToByteArray() })
        // URLEncoder safe = alnum + ` .-_ *`; '~' 编码 (JDK dontNeedEncoding 不含 '~')
        assertEquals("a%7Eb", NativeUrlEntryEncoding.urlEncoderEncode("a~b") { it.encodeToByteArray() })
        assertEquals("a*b", NativeUrlEntryEncoding.urlEncoderEncode("a*b") { it.encodeToByteArray() })
        // 连续不安全字符合并为一段一次编码
        assertEquals("%E4%B8%AD%E6%96%87", NativeUrlEntryEncoding.urlEncoderEncode("中文") { it.encodeToByteArray() })
    }

    // ===== encodeUrlParams native actual: charset 接入 =====

    @Test
    fun encodeUrlParamsQueryUsesExplicitCharset() {
        assertEquals("%E4%B8%AD%E6%96%87", encodeUrlParams("中文", null, isQuery = true))
        assertEquals("%E4%B8%AD%E6%96%87", encodeUrlParams("中文", "UTF-8", isQuery = true))
        // Latin1 无「中」→ REPLACE '?' (进程内 codec, 不依赖平台桥)
        assertEquals("%3F%3F", encodeUrlParams("中文", "ISO-8859-1", isQuery = true))
        // 已编码判定通过则原样 (charset 空时的行为分支)
        assertEquals("%E4%B8%AD", encodeUrlParams("%E4%B8%AD", null, isQuery = true))
    }

    @Test
    fun encodeUrlParamsStatefulCharsetSingleBomPerCall() {
        // hutool 5.8.22 一手语义: 编码器状态跨整个 encode() 调用, UTF-16 BOM 仅首个不安全段前一次
        // (共享 TextCharsetEncoder; safe 字符原样穿插)
        assertEquals(
            "a%FE%FF%4E%2D%4E%2Db",
            encodeUrlParams("a中中b", "UTF-16", isQuery = true),
        )
        assertEquals(
            "a%FE%FF%4E%2Db%65%87",
            encodeUrlParams("a中b文", "UTF-16", isQuery = true),
        )
        // 段内成套代理对编为真实码点 (😀 = D83D DE00 → D8 3D DE 00)
        assertEquals(
            "a%FE%FF%D8%3D%DE%00b%D8%3D%DE%00c",
            encodeUrlParams("a😀b😀c", "UTF-16", isQuery = true),
        )
    }

    @Test
    fun encodeUrlParamsUtf16FixedEndianNoBom() {
        // UTF-16LE/BE (UnicodeEncoder mark=false) 无 BOM; LE replacement = FD FF, BE = FF FD
        assertEquals("a%2D%4Eb%87%65", encodeUrlParams("a中b文", "UTF-16LE", isQuery = true))
        assertEquals("a%4E%2Db%65%87", encodeUrlParams("a中b文", "UTF-16BE", isQuery = true))
        assertEquals("a%2D%4E%2D%4Eb", encodeUrlParams("a中中b", "UTF-16LE", isQuery = true))
    }

    @Test
    fun encodeUrlParamsUtf32VariantsNoBomNoOverrun() {
        // UTF-32 全系 newEncoder 均 doBOM=false (sun.nio.cs.UTF_32/UTF_32BE/UTF_32LE 一手),
        // 4 字节/码点无 BOM; 旧「BOM+按码元切片」实现对非 ASCII 越界输出已删除
        assertEquals("%00%00%4E%2D", encodeUrlParams("中", "UTF-32", isQuery = true))
        assertEquals("%00%01%F6%00", encodeUrlParams("😀", "UTF-32", isQuery = true))
        assertEquals("a%00%00%4E%2Db%00%00%65%87", encodeUrlParams("a中b文", "UTF-32", isQuery = true))
        assertEquals("%2D%4E%00%00", encodeUrlParams("中", "UTF-32LE", isQuery = true))
        assertEquals("%00%00%4E%2D", encodeUrlParams("中", "UTF-32BE", isQuery = true))
        assertEquals("%00%F6%01%00", encodeUrlParams("😀", "UTF-32LE", isQuery = true))
    }

    @Test
    fun encodeUrlParamsLoneSurrogatesReplacedIndependentlyNotMerged() {
        // \uD800 x \uDC00: x 为 safe 字符切分两段, 两段各自独立 REPLACE, 不跨 safe 字符拼成
        // 合法代理对 (archive writer 遗留态会拼接, 原版明显缺陷不复刻)
        assertEquals("%FE%FF%FF%FDx%FF%FD", encodeUrlParams("\uD800x\uDC00", "UTF-16", isQuery = true))
        assertEquals("%3Fx%3F", encodeUrlParams("\uD800x\uDC00", "UTF-8", isQuery = true))
        assertEquals("%00%00%FF%FDx%00%00%FF%FD", encodeUrlParams("\uD800x\uDC00", "UTF-32", isQuery = true))
        // 输入末尾孤立 high: 即时按 replacement 替换, 不无声丢失 (archive writer 未 close → 字节丢失)
        assertEquals("%FE%FF%FF%FD", encodeUrlParams("\uD800", "UTF-16", isQuery = true))
        assertEquals("%3F", encodeUrlParams("\uD800", "UTF-8", isQuery = true))
        // 同段内 high+非 low: high 独立 REPLACE, 其余照常编码
        assertEquals("%FE%FF%4E%2D%FF%FD%65%87", encodeUrlParams("中\uD800文", "UTF-16", isQuery = true))
    }

    @Test
    fun encodeUrlParamsAsciiLatin1SurrogatePairIsSingleReplacement() {
        // JDK Surrogate.Parser (US-ASCII/ISO-8859-1 编码器一手): 成套代理对 = 一个不可映射
        // 单元 (unmappableForLength(2)) → 一个 '?'
        assertEquals("%3F", encodeUrlParams("😀", "US-ASCII", isQuery = true))
        assertEquals("%3F", encodeUrlParams("😀", "ISO-8859-1", isQuery = true))
        assertEquals("%3F%3F", encodeUrlParams("中文", "ISO-8859-1", isQuery = true))
    }

    @Test
    fun encodeUrlParamsEscapeMode() {
        assertEquals("%u4e2d", encodeUrlParams("中", "escape", isQuery = false))
        assertEquals("a%20b", encodeUrlParams("a b", "escape", isQuery = true))
    }

    @Test
    fun encodeUrlParamsFormAlreadyEncodedBranch() {
        assertEquals("k=%E4%B8%AD", encodeUrlParams("k=%E4%B8%AD", null, isQuery = false))
    }

    @Test
    fun encodeUrlParamsUnsupportedCharsetThrows() {
        // JVM Charset.forName 对未知名/本平台不支持名抛异常, native 门面同语义
        assertFailsWith<Exception> { encodeUrlParams("a", "NotACharset", isQuery = true) }
        assertFailsWith<Exception> { encodeUrlParams("a", "X-GBK", isQuery = true) }
        assertFailsWith<Exception> { encodeUrlParams("a", "GB-18030", isQuery = true) }
        assertFailsWith<Exception> { encodeUrlParams("a", "BIG-5", isQuery = true) }
        // 空白名 (trim 后非空) 同未知名拒绝
        assertFailsWith<Exception> { encodeUrlParams("a", " ", isQuery = true) }
        // native 无真实 GB18030-2000 转换器, 拒绝该名 (不以 2022 库/平台 gb18030 冒充 2000)
        assertFailsWith<Exception> { encodeUrlParams("a", "gb18030-2000", isQuery = true) }
    }

    @Test
    fun encodeUrlParamsAcceptsJvmAliases() {
        // JDK charsets 表别名 (GBK: windows-936/CP936; GB2312: gb2312-80/euc-cn/EUC_CN;
        // GB18030-2022 由真实 2022 库承接; US-ASCII/Latin-1 官方别名全量对齐 JDK 清单)
        for (name in listOf(
            "GBK", "windows-936", "CP936",
            "GB2312", "gb2312-80", "gb2312-1980", "EUC-CN", "EUCCN", "X-EUC-CN", "EUC_CN",
            "GB18030", "GB18030-2022",
            "BIG5", "csBig5",
            "UTF-8", "UTF8", "UTF-16", "UTF_16", "UTF-16LE", "UTF_16LE", "UTF-16BE", "UTF_16BE",
            "UTF-32", "UTF_32", "ISO-8859-1", "latin1", "ISO8859-1", "US-ASCII", "ASCII", "IBM367",
            // US-ASCII 官方别名 (JDK charsets 一手): csASCII/646/iso_646.irv:1983/ANSI_X3.4-1968/ascii7
            "csASCII", "646", "iso_646.irv:1983", "ANSI_X3.4-1968", "ascii7",
            // ISO-8859-1 官方别名: cp819
            "cp819",
        )) {
            // 每个名字至少可实例化并参与编码 (全 safe ASCII: 名字仅验实例化, 编码路径由其他用例覆盖)
            assertEquals("ka", encodeUrlParams("ka", name, isQuery = false), "alias=$name")
        }
    }

    @Test
    fun encodeUrlParamsQuerySegmentAmpEqualsRemainLiteral() {
        // query 整段路径: & = 等 query 保留字原样, 只编码值段非保留字符
        assertEquals("k1=%E4%B8%AD%E6%96%87&k2=v", encodeUrlParams("k1=中文&k2=v", "UTF-8", isQuery = true))
    }

    // ===== 替换字节契约: 孤立代理项 =====

    @Test
    fun loneSurrogateBytesFollowJvmReplaceOnEveryUrlEntry() {
        // JVM UTF-8 REPLACE 契约 (JDK doReplace / Okio writeUtf8CodePoint 一手): 孤立代理项
        // 输出单字节 '?' → %3F, 非 K/N encodeToByteArray 的 U+FFFD (%EF%BF%BD); 已编码判定
        // 只改 safe 集合, 孤立代理项在全部入口均不安全 → 未编码/已编码入口字节一致;
        // 合法代理对编真实码点 (😀 UTF-8 = F0 9F 98 80)
        for ((input, expected) in listOf(
            "\uD800" to "%3F",
            "a\uDC00b" to "a%3Fb",
            "😀" to "%F0%9F%98%80",
        )) {
            assertEquals(expected, NativeUrlEntryEncoding.encodeQueryComponent(input))
            assertEquals(expected, NativeUrlEntryEncoding.encodeQueryEncodedComponent(input))
            assertEquals(expected, NativeUrlEntryEncoding.encodeQuerySegment(input))
            assertEquals(expected, NativeUrlEntryEncoding.encodeFormComponent(input))
            assertEquals(expected, NativeUrlEntryEncoding.encodeFormEncodedComponent(input))
            assertEquals(
                expected,
                NativeUrlEntryEncoding.urlEncoderEncode(input, textCharsetCodec("UTF-8")::encode),
            )
        }
    }
}
