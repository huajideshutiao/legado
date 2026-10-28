package io.legado.app.model.analyzeRule

import io.legado.app.help.http.urlQueryEncoder
import io.legado.app.utils.PercentCodec
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * URL 参数编码矩阵 (encodeUrlParams, jvmAndAndroid actual = archive 原版行为基准)。
 *
 * 断言期望按一手定义推演:
 * - form 路径 = java.net.URLEncoder.encode (safe: alnum 与 ` .-_ *`, 空格→`+`, 连续不安全字符
 *   一次 charset 编码, 不可映射字符 REPLACE 为 `?`);
 * - query 整段路径 = urlQueryEncoder (unreserved + RFC3986 query 保留字, 已编码判定走
 *   NetworkUtils.encodedQuery); Charset 重载经共享 TextCharsetEncoder 恢复 archive hutool
 *   5.8.22「循环外一个 OutputStreamWriter」语义 (编码器状态跨整个 encode 调用);
 * - escape 路径 = EncoderUtils.escape (字母数字原样, 其余 %XX/%uXXXX)。
 *
 * 有状态字符集 (UTF-16/UTF-32) 的操作级对照: [jdkSharedEncoderEncode] 按 JDK CharsetEncoder +
 * 原版调用定义 (每次编码调用一个编码器, 逐连续不安全段 encode(end=false)) 生成期望;
 * 段尾孤立 high 代理按 encoder.replacement 即时替换 —— archive 逐字符 write+flush 且 writer
 * 从不 close: 段尾孤立 high 被静默丢弃、孤立 high 跨 safe 字符与后续 low 拼成合法代理对,
 * 两处均为原版明显缺陷, 不复刻, 因此孤立代理输入只写 hex 期望、不与 archive 对照。
 */
class UrlParamsEncodingTest {

    // ===== query 整段路径 (isQuery=true) =====

    @Test
    fun queryPlainAsciiKeptVerbatim() {
        // 全 safe (含 RFC3986 query 保留字) 输入原样返回; '%E7%A6%85' 的 '%' 在 safe 表内不二次转义
        for (params in listOf("a=1&b=2", "%E7%A6%85", "a*b/c", "k=1:2?3", "")) {
            assertEquals(params, encodeUrlParams(params, null, isQuery = true), "charset=null: $params")
            assertEquals(params, encodeUrlParams(params, "UTF-8", isQuery = true), "charset=UTF-8: $params")
        }
    }

    @Test
    fun querySpaceAndUnsafeCharsEncoded() {
        // 'a+b c' 判未编码 (空格不在 encodedQuery 保留表, '+' 在): '+' 原样, 空格 → %20
        assertEquals("a+b%20c", encodeUrlParams("a+b c", null, isQuery = true))
        assertEquals("a+b%20c", encodeUrlParams("a+b c", "UTF-8", isQuery = true))
        // 双引号/尖括号/井号不在 query safe 表
        assertEquals("a%22b%3Cc%3Ed%23e", encodeUrlParams("a\"b<c>d#e", "UTF-8", isQuery = true))
    }

    @Test
    fun queryNonAsciiEncodedAsUtf8ByDefault() {
        assertEquals("%E4%B8%AD%E6%96%87", encodeUrlParams("中文", null, isQuery = true))
        // 显式 UTF-8 与默认一致
        assertEquals("%E4%B8%AD%E6%96%87", encodeUrlParams("中文", "UTF-8", isQuery = true))
    }

    @Test
    fun queryExplicitCharsetProducesItsOwnBytes() {
        // 汉字「爱情」: GBK/GB2312/GB18030 双字节同值 (B0AE C7E9, GB2312 区位表一手)
        assertEquals("%B0%AE%C7%E9", encodeUrlParams("爱情", "GBK", isQuery = true))
        assertEquals("%B0%AE%C7%E9", encodeUrlParams("爱情", "GB2312", isQuery = true))
        assertEquals("%B0%AE%C7%E9", encodeUrlParams("爱情", "GB18030", isQuery = true))
        // Big5 与 JDK Charset 同码位对照 (繁体「愛」)
        assertEquals(
            java.net.URLEncoder.encode("愛", "Big5"),
            encodeUrlParams("愛", "Big5", isQuery = true),
        )
    }

    @Test
    fun queryEscapeModeSegmentsAndEscapes() {
        // escape 时 cs=null, 走表单分段: EncoderUtils.escape 逐字符 %XX/%uXXXX
        assertEquals("a%20b%2fc", encodeUrlParams("a b/c", "escape", isQuery = true))
        assertEquals("%u4e2d%u6587", encodeUrlParams("中文", "escape", isQuery = true))
    }

    @Test
    fun queryUnsupportedCharsetThrows() {
        // 对齐 JVM Charset.forName: 未知名/本平台不支持名抛异常, 不静默退化 UTF-8
        assertFailsWith<Exception> { encodeUrlParams("a=1", "NotACharset", isQuery = true) }
    }

    // ===== query 整段路径: 有状态字符集 (UTF-16/UTF-32) 操作级对照 =====

    /**
     * JDK CharsetEncoder + 原版调用定义的操作级 oracle: 对齐 archive hutool 5.8.22
     * PercentCodec.encode「循环外一个 OutputStreamWriter」—— 每次编码调用一个编码器
     * (REPLACE 错误动作), 逐连续不安全段 encode(end=false), 编码器状态跨段持续
     * (UTF-16 BOM 仅首段一次); 段尾孤立 high 代理按 encoder.replacement 即时替换 (契约修正,
     * archive 该处为静默丢弃/跨段拼接缺陷, 见类注释)。切段用 [urlQueryEncoder].isSafe ——
     * 与被测 query 路径同一 safe 表 (PercentCodec.QUERY 的 safe 集不含 %[]\^`{|} 等
     * query 保留字, 段边界会偏离被测实现)。
     */
    private fun jdkSharedEncoderEncode(input: String, charset: Charset): String {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = StringBuilder(input.length)
        val bb = ByteBuffer.allocate(input.length * 4 + 8)
        var i = 0
        while (i < input.length) {
            if (urlQueryEncoder.isSafe(input[i])) {
                out.append(input[i])
                i++
                continue
            }
            val start = i
            while (i < input.length) {
                if (urlQueryEncoder.isSafe(input[i])) break
                val r = input[i]
                i += if (r.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) 2 else 1
            }
            val cb = CharBuffer.wrap(input, start, i)
            val cr = encoder.encode(cb, bb, false)
            check(cr.isUnderflow()) { "oracle 缓冲不足: $cr" }
            // 段尾孤立 high 代理: 契约即时按 replacement 替换
            if (cb.hasRemaining()) {
                check(cb.remaining() == 1 && cb.get(cb.position()).isHighSurrogate())
                bb.put(encoder.replacement())
            }
            bb.flip()
            while (bb.hasRemaining()) {
                val v = bb.get().toInt() and 0xFF
                out.append('%')
                out.append("0123456789ABCDEF"[v ushr 4])
                out.append("0123456789ABCDEF"[v and 0xF])
            }
            bb.clear()
        }
        return out.toString()
    }

    @Test
    fun queryStatefulCharsetMatchesJdkSharedEncoderOracle() {
        val samples = listOf("a中中b", "a中b文", "中中", "a😀b😀c", "中,文😀文")
        val charsets = listOf(
            Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE,
            Charsets.UTF_32, Charsets.UTF_32LE, Charsets.UTF_32BE, Charsets.UTF_8,
        )
        for (charset in charsets) {
            for (sample in samples) {
                assertEquals(
                    jdkSharedEncoderEncode(sample, charset),
                    encodeUrlParams(sample, charset.name(), isQuery = true),
                    "charset=${charset.name()} sample=$sample",
                )
            }
        }
    }

    @Test
    fun queryUtf16BomOncePerCallAcrossRuns() {
        // UTF-16 BOM 仅随首个不安全段输出一次, 段间 safe 字符不重置编码器状态
        assertEquals("a%FE%FF%4E%2D%4E%2Db", encodeUrlParams("a中中b", "UTF-16", isQuery = true))
        assertEquals("a%FE%FF%4E%2Db%65%87", encodeUrlParams("a中b文", "UTF-16", isQuery = true))
        // 段内成套代理对编为真实码点 (😀 = D83D DE00 → D8 3D DE 00)
        assertEquals(
            "a%FE%FF%D8%3D%DE%00b%D8%3D%DE%00c",
            encodeUrlParams("a😀b😀c", "UTF-16", isQuery = true),
        )
    }

    @Test
    fun queryUtf16FixedEndianNoBom() {
        // UTF-16LE/BE (UnicodeEncoder mark=false) 无 BOM; LE replacement = FD FF, BE = FF FD
        assertEquals("a%2D%4Eb%87%65", encodeUrlParams("a中b文", "UTF-16LE", isQuery = true))
        assertEquals("a%4E%2Db%65%87", encodeUrlParams("a中b文", "UTF-16BE", isQuery = true))
        assertEquals("%FD%FFx%FD%FF", encodeUrlParams("\uD800x\uDC00", "UTF-16LE", isQuery = true))
    }

    @Test
    fun queryUtf32NoBomAllVariants() {
        // UTF-32 全系 newEncoder 均 doBOM=false (sun.nio.cs.UTF_32/UTF_32BE/UTF_32LE 一手),
        // 4 字节/码点, 无 BOM; BE 与无标记 UTF-32 同为 BIG
        assertEquals("%00%00%4E%2D", encodeUrlParams("中", "UTF-32", isQuery = true))
        assertEquals("%00%01%F6%00", encodeUrlParams("😀", "UTF-32", isQuery = true))
        assertEquals("a%00%00%4E%2Db%00%00%65%87", encodeUrlParams("a中b文", "UTF-32", isQuery = true))
        assertEquals("%2D%4E%00%00", encodeUrlParams("中", "UTF-32LE", isQuery = true))
        assertEquals("%00%00%4E%2D", encodeUrlParams("中", "UTF-32BE", isQuery = true))
        assertEquals("%00%F6%01%00", encodeUrlParams("😀", "UTF-32LE", isQuery = true))
    }

    @Test
    fun queryLoneSurrogatesReplacedIndependentlyNotMerged() {
        // \uD800 x \uDC00: x 为 safe 字符切分两段, 两段各自独立 REPLACE, 不跨 safe 字符拼成
        // 合法代理对 (archive writer 遗留态会拼接, 原版明显缺陷不复刻)
        assertEquals("%FE%FF%FF%FDx%FF%FD", encodeUrlParams("\uD800x\uDC00", "UTF-16", isQuery = true))
        assertEquals("%3Fx%3F", encodeUrlParams("\uD800x\uDC00", "UTF-8", isQuery = true))
        assertEquals("%00%00%FF%FDx%00%00%FF%FD", encodeUrlParams("\uD800x\uDC00", "UTF-32", isQuery = true))
        // 输入末尾孤立 high: 即时按 replacement 替换, 不无声丢失 (archive writer 未 close → 字节丢失)
        assertEquals("%FE%FF%FF%FD", encodeUrlParams("\uD800", "UTF-16", isQuery = true))
        assertEquals("%FF%FD", encodeUrlParams("\uD800", "UTF-16BE", isQuery = true))
        assertEquals("%3F", encodeUrlParams("\uD800", "UTF-8", isQuery = true))
        // 同段内 high+非 low: high 独立 REPLACE, 其余照常编码
        assertEquals("%FE%FF%4E%2D%FF%FD%65%87", encodeUrlParams("中\uD800文", "UTF-16", isQuery = true))
    }

    @Test
    fun queryUtf8LoneSurrogateLockedToNativeSameBytes() {
        // 与 native 端 UrlEntryEncodingTest 同一替换字节契约: 孤立代理项按 JVM UTF-8
        // REPLACE 输出单字节 '?' → %3F (非 U+FFFD), 合法代理对编真实码点, 两端锁定同一字节
        assertEquals("%3F", encodeUrlParams("\uD800", null, isQuery = true))
        assertEquals("%3F", encodeUrlParams("\uD800", "UTF-8", isQuery = true))
        assertEquals("a%3Fb", encodeUrlParams("a\uDC00b", "UTF-8", isQuery = true))
        assertEquals("%F0%9F%98%80", encodeUrlParams("😀", "UTF-8", isQuery = true))
    }

    @Test
    fun formUtf8LoneSurrogateLockedToNativeSameBytes() {
        // form 路径 (java.net.URLEncoder) 同一替换字节契约, 与 query 路径、native 端一致
        assertEquals("%3F", encodeUrlParams("\uD800", null, isQuery = false))
        assertEquals("a%3Fb", encodeUrlParams("a\uDC00b", "UTF-8", isQuery = false))
        assertEquals("%F0%9F%98%80", encodeUrlParams("😀", "UTF-8", isQuery = false))
    }

    @Test
    fun queryAsciiLatin1SurrogatePairIsSingleReplacement() {
        // JDK Surrogate.Parser (US-ASCII/ISO-8859-1 编码器一手): 成套代理对 = 一个不可映射
        // 单元 (unmappableForLength(2)) → 一个 '?'
        assertEquals("%3F", encodeUrlParams("😀", "US-ASCII", isQuery = true))
        assertEquals("%3F", encodeUrlParams("😀", "ISO-8859-1", isQuery = true))
        assertEquals("%3F%3F", encodeUrlParams("中文", "ISO-8859-1", isQuery = true))
    }

    // ===== 表单路径 (isQuery=false) =====

    @Test
    fun formSegmentsOnAmpAndEquals() {
        // charset 空: 已编码段原样保留, 未编码段 URLEncoder UTF-8
        assertEquals("a+b=%E4%B8%AD%E6%96%87", encodeUrlParams("a b=中文", null, isQuery = false))
        assertEquals("a+b", encodeUrlParams("a b", null, isQuery = false))
        // '&&' 中间空段保留, 结尾单个 '&' 吃掉, 开头空段丢弃
        assertEquals("a&&b", encodeUrlParams("&&a&&b&", null, isQuery = false))
        // 无 '=' 的段只编码名
        assertEquals("key", encodeUrlParams("key", null, isQuery = false))
    }

    @Test
    fun formPlusLiteralIsReencodedSpaceBecomesPlus() {
        // URLEncoder: 字面 '+' 是不安全字符 → %2B; 空格 → '+'
        assertEquals("a%2Bb", encodeUrlParams("a+b", null, isQuery = false))
    }

    @Test
    fun formAlreadyEncodedValueKeptVerbatimWhenCharsetUnspecified() {
        assertEquals("k=%E7%A6%85", encodeUrlParams("k=%E7%A6%85", null, isQuery = false))
    }

    @Test
    fun formAlreadyEncodedValueReencodedWhenCharsetSpecified() {
        // archive 原版 encodeOne: checkEncoded 仅在 charset 为空时为 true,
        // 显式 charset 下已编码输入同样按 URLEncoder 重编码 ('%' → %25)
        assertEquals("%25E7", encodeUrlParams("%E7", "GBK", isQuery = false))
    }

    @Test
    fun formExplicitCharsetUsesItsEncoderAndReplacement() {
        // 「爱情」GBK 双字节
        assertEquals("w=%B0%AE%C7%E9", encodeUrlParams("w=爱情", "GBK", isQuery = false))
        // emoji 超出 GBK/GB2312/Big5 表示范围: JVM REPLACE 输出 '?'
        assertEquals("e=%3F", encodeUrlParams("e=😀", "GBK", isQuery = false))
        assertEquals("e=%3F", encodeUrlParams("e=😀", "GB2312", isQuery = false))
        assertEquals("e=%3F", encodeUrlParams("e=😀", "Big5", isQuery = false))
        // GB18030 可表示 emoji (四字节 9439FC36, GB18030-2005 线性映射), 与 GBK/Big5 行为不同
        assertEquals("e=%94%39%FC%36", encodeUrlParams("e=😀", "GB18030", isQuery = false))
    }

    @Test
    fun formEscapeModeDoesNotUrlEncode() {
        assertEquals("%u4e2d", encodeUrlParams("中", "escape", isQuery = false))
        assertEquals("a%20b", encodeUrlParams("a b", "escape", isQuery = false))
    }

    @Test
    fun formUnsupportedCharsetThrows() {
        assertFailsWith<Exception> { encodeUrlParams("a=1", "NotACharset", isQuery = false) }
    }

    // ===== PercentCodec 编码路径: 连续段合并 vs 逐簇 =====

    @Test
    fun mergingRunsMatchesPerClusterEncodingForStatelessCharsets() {
        // 无状态字符集 (UTF-8/Latin 系/GBK 系): 连续不安全段合并一次字节化与逐簇字节一致。
        // 成立范围 = 合法 Unicode 与段内成套代理对输入 (逐簇 lambda 对孤立代理项是另一次
        // 独立转换, 语义差异由有状态用例单独覆盖)。
        val samples = listOf("a中b文", "中中中", "a+b c%d", "😊x", "a😊b😊c")
        for (name in listOf("UTF-8", "GBK", "GB18030", "Big5", "ISO-8859-1", "US-ASCII")) {
            val charset = Charset.forName(name)
            for (sample in samples) {
                val perCluster = PercentCodec.QUERY.encode(sample) { it.toByteArray(charset) }
                val merged = PercentCodec.QUERY.encodeMergingRuns(sample) { it.toByteArray(charset) }
                assertEquals(perCluster, merged, "charset=$name sample=$sample")
            }
        }
    }

    @Test
    fun mergingRunsByteOutputFollowsLambdaEncoderLifecycle() {
        // toBytes 每次新建转换 (String.toByteArray) 的生命周期 = 每次调用一个新编码器:
        // 合并后「中中」为一段 → 一次编码 → 一次 BOM (与 java.net.URLEncoder「连续不安全
        // 字符一次编码」同语义); 逐簇为对照仅描述该 lambda 生命周期 (每簇各带 BOM),
        // 不是 archive hutool oracle —— hutool 循环外单 writer、BOM 全调用一次,
        // 该语义的对照见上方 jdkSharedEncoderEncode 共享编码器用例。
        val utf16 = Charset.forName("UTF-16")
        val merged = PercentCodec.QUERY.encodeMergingRuns("中中") { it.toByteArray(utf16) }
        assertEquals("中中".toByteArray(utf16).joinToString("") {
            "%%%02X".format(it.toInt() and 0xFF)
        }, merged)
        // 逐簇对照: 每次独立转换各带一次 BOM
        val perCluster = PercentCodec.QUERY.encode("中中") { it.toByteArray(utf16) }
        assertEquals(
            "中中".flatMap { listOf(it) }.joinToString("") { c ->
                c.toString().toByteArray(utf16).joinToString("") {
                    "%%%02X".format(it.toInt() and 0xFF)
                }
            },
            perCluster,
        )
    }
}
