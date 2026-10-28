package io.legado.app.help.http

import io.legado.app.utils.TextCharsetCodec
import io.legado.app.utils.textCharsetCodec

/**
 * OkHttp 5.4.0 请求构建器编码语义的 native 等价 (iOS/鸿蒙无 OkHttp 变体)。
 *
 * 入口分层完全对齐 okhttp3.internal.url.writeCanonicalized / okhttp3.FormBody (parent-5.4.0 一手):
 * - canonicalize 全部发生在**入口** (FormBody.add/addEncoded、HttpUrl.Builder 的
 *   addQueryParameter/addEncodedQueryParameter/encodedQuery/URL 解析), 已编码判定、
 *   `%`/`+`/空格/encodeSet 的处理逐条对照 OkHttp;
 * - build/toString **只拼接**, 不再套任何 encodeSet —— OkHttp 的 parse→toString 与
 *   newBuilder→build 字节往返不变性由此保证 (query 值内原始 `=` 等原样保留);
 * - strict=false: OkHttp 除 toUri 路径外所有入口均不校验 `%HH` 三元组有效性,
 *   孤立 `%` 的最终合法化由 [io.legado.app.utils.canonicalizeUri] 在 [KmpHttpUrl]
 *   构造时兜底, 入口层不擅自收紧为「只保留有效 HH」。
 * - 字节化统一经 [io.legado.app.utils.textCharsetCodec] 门面 (UTF-8): 孤立代理项按
 *   JVM REPLACE 输出单字节 '?', 不用平台 encodeToByteArray (K/N 替换为 U+FFFD)。
 */
internal object NativeUrlEntryEncoding {

    /** FormBody.Builder 的一条键值对: 入口已按 alreadyEncoded 语义编码, build 直接拼接。 */
    internal data class NativeFormEntry(
        val encodedName: String,
        val encodedValue: String,
    )

    /** HttpUrl.Builder 的一条 query 名值对: 名可重复, 值可为 null (无 `=` 段), 入口已编码, build 直接拼接。 */
    internal data class NativeQueryEntry(
        val encodedName: String,
        val encodedValue: String?,
    )

    /** OkHttp FormBody 的 encodeSet: `" !\"#$&'()+,/:;<=>?@[\\]^`{|}~"` (okhttp3.internal.url.FORM_ENCODE_SET)。 */
    internal const val FORM_ENCODE_SET = " !\"#$&'()+,/:;<=>?@[\\]^`{|}~"

    /** OkHttp HttpUrl.Builder.query/encodedQuery 整段 encodeSet: `" \"'<>#"` (QUERY_ENCODE_SET)。 */
    internal const val QUERY_ENCODE_SET = " \"'<>#"

    /** OkHttp addQueryParameter: `" !\"#$&'(),/:;<=>?@[]\\^`{|}~"` (QUERY_COMPONENT_ENCODE_SET)。 */
    internal const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"

    /** OkHttp addEncodedQueryParameter: `" \"'<>#&="` (QUERY_COMPONENT_REENCODE_SET)。 */
    internal const val QUERY_COMPONENT_REENCODE_SET = " \"'<>#&="

    /**
     * java.net.URLEncoder.encode 的待编码集: 除 `a-z A-Z 0-9 空格 - _ . *` 外的全部 ASCII。
     * (URLEncoder 注释原文: Netscape/IE 仅保留 "-" "_" "." "*", 空格转 `+`; `~` 不保留。)
     */
    private val URLENCODER_ENCODE_SET: String = buildString {
        for (code in 0..0x7F) {
            val c = code.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == ' ' || c == '-' || c == '_' || c == '.' || c == '*'
            ) continue
            append(c)
        }
    }

    /**
     * OkHttp canonicalize 等价。仅覆盖本仓入口实际使用的开关面:
     * strict 恒 false (OkHttp 除 reencodeForUri 外的入口均不校验三元组), 无 uri 分支。
     *
     * @param spaceAsPlus  true = FORM 入口语义 (空格写 `+`); false = query 入口 (空格走 encodeSet → `%20`)
     * @param plusIsSpace  true = addEncoded/encodedQuery 入口 (`+` 保持原样); false = add 入口 (`+` → `%2B`)
     */
    internal fun canonicalize(
        input: String,
        encodeSet: String,
        alreadyEncoded: Boolean,
        plusIsSpace: Boolean,
        spaceAsPlus: Boolean,
        toBytes: (String) -> ByteArray,
    ): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (alreadyEncoded && (c == '\t' || c == '\n' || c == '\u000C' || c == '\r')) {
                i++
                continue
            }
            if (c == ' ' && spaceAsPlus) {
                out.append('+')
                i++
                continue
            }
            if (c == '+' && plusIsSpace) {
                out.append(if (alreadyEncoded) "+" else "%2B")
                i++
                continue
            }
            val needsEncoding = c.code < 0x20 || c.code == 0x7F || c.code >= 0x80 ||
                (c.code < 0x80 && c in encodeSet) ||
                (c == '%' && !alreadyEncoded)
            if (!needsEncoding) {
                out.append(c)
                i++
                continue
            }
            // 连续待编码簇合并为一段, 一次字节化 (远程转换每段一次往返)
            val start = i
            while (i < input.length) {
                val run = input[i]
                if (alreadyEncoded && (run == '\t' || run == '\n' || run == '\u000C' || run == '\r')) break
                if (run == ' ' && spaceAsPlus) break
                if (run == '+' && plusIsSpace) break
                val runNeedsEncoding = run.code < 0x20 || run.code == 0x7F || run.code >= 0x80 ||
                    (run.code < 0x80 && run in encodeSet) ||
                    (run == '%' && !alreadyEncoded)
                if (!runNeedsEncoding) break
                i += if (run.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) 2 else 1
            }
            for (b in toBytes(input.substring(start, i))) {
                val v = b.toInt() and 0xFF
                out.append('%').append(HEX[v ushr 4]).append(HEX[v and 0xF])
            }
        }
        return out.toString()
    }

    /** URLEncoder.encode(value, charset) 等价: 空格→`+`, 连续不安全字符一次编码, `%XX` 大写。 */
    internal fun urlEncoderEncode(value: String, toBytes: (String) -> ByteArray): String =
        canonicalize(
            input = value,
            encodeSet = URLENCODER_ENCODE_SET,
            alreadyEncoded = false,
            plusIsSpace = false,
            spaceAsPlus = true,
            toBytes = toBytes,
        )

    /** FormBody.add 入口编码 (FORM_ENCODE_SET, alreadyEncoded=false, 空格→`+`)。 */
    internal fun encodeFormComponent(s: String): String =
        canonicalize(
            input = s,
            encodeSet = FORM_ENCODE_SET,
            alreadyEncoded = false,
            plusIsSpace = false,
            spaceAsPlus = true,
            toBytes = textCharsetCodec("UTF-8").newEncoder()::encode,
        )

    /** FormBody.addEncoded 入口编码 (FORM_ENCODE_SET, alreadyEncoded=true, `%`/`+` 保持)。 */
    internal fun encodeFormEncodedComponent(s: String): String =
        canonicalize(
            input = s,
            encodeSet = FORM_ENCODE_SET,
            alreadyEncoded = true,
            plusIsSpace = true,
            spaceAsPlus = true,
            toBytes = textCharsetCodec("UTF-8").newEncoder()::encode,
        )

    /** addQueryParameter 入口编码 (QUERY_COMPONENT_ENCODE_SET, alreadyEncoded=false, `%`→%25, `+`→%2B)。 */
    internal fun encodeQueryComponent(s: String): String =
        canonicalize(
            input = s,
            encodeSet = QUERY_COMPONENT_ENCODE_SET,
            alreadyEncoded = false,
            plusIsSpace = true,
            spaceAsPlus = false,
            toBytes = textCharsetCodec("UTF-8").newEncoder()::encode,
        )

    /** addEncodedQueryParameter 入口编码 (QUERY_COMPONENT_REENCODE_SET, alreadyEncoded=true, `%`/`+` 保持)。 */
    internal fun encodeQueryEncodedComponent(s: String): String =
        canonicalize(
            input = s,
            encodeSet = QUERY_COMPONENT_REENCODE_SET,
            alreadyEncoded = true,
            plusIsSpace = true,
            spaceAsPlus = false,
            toBytes = textCharsetCodec("UTF-8").newEncoder()::encode,
        )

    /**
     * OkHttp Builder 构造解析与 encodedQuery() 的整段入口编码 (QUERY_ENCODE_SET,
     * alreadyEncoded=true, `+` 保持, 空格/`\"'< >#` 编码, 值内 `=` 原样保留)。
     */
    internal fun encodeQuerySegment(s: String): String =
        canonicalize(
            input = s,
            encodeSet = QUERY_ENCODE_SET,
            alreadyEncoded = true,
            plusIsSpace = true,
            spaceAsPlus = false,
            toBytes = textCharsetCodec("UTF-8").newEncoder()::encode,
        )

    /**
     * OkHttp String.toQueryNamesAndValues 等价: 按 `&` 分段, 段内首个 `=` 分名值;
     * 无 `=` 的段值记 null, 名可重复、可为空串。
     */
    internal fun splitQuery(query: String): MutableList<Pair<String, String?>> {
        val result = mutableListOf<Pair<String, String?>>()
        var pos = 0
        while (pos <= query.length) {
            var ampersandOffset = query.indexOf('&', pos)
            if (ampersandOffset == -1) ampersandOffset = query.length
            val equalsOffset = query.indexOf('=', pos)
            if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
                result.add(query.substring(pos, ampersandOffset) to null)
            } else {
                result.add(query.substring(pos, equalsOffset) to query.substring(equalsOffset + 1, ampersandOffset))
            }
            pos = ampersandOffset + 1
        }
        return result
    }

    /** OkHttp List<String?>.toQueryString 等价: 交替写名值, 值为 null 不写 `=` (名与 `&` 分隔照写)。 */
    internal fun joinQuery(entries: List<NativeQueryEntry>): String {
        val out = StringBuilder()
        entries.forEachIndexed { index, entry ->
            if (index > 0) out.append('&')
            out.append(entry.encodedName)
            if (entry.encodedValue != null) {
                out.append('=').append(entry.encodedValue)
            }
        }
        return out.toString()
    }

    /**
     * URL 参数整段编码 (encodeUrlParams query 路径)。
     *
     * 字节语义对齐 archive 一手 hutool 5.8.22 PercentCodec.encode(String, Charset):
     * 编码器状态跨整个 encode 调用持续 (UTF-16 BOM 全调用仅一次), 连续不安全段一段一次
     * 字节化 (远程转换每段一次往返); 段尾孤立 high 代理按编码器 replacement 即时替换,
     * 不跨段拼接。原版逐字符 write+flush 且 writer 从不 close, 段尾孤立 high 被静默丢弃、
     * 孤立 high 会跨 safe 字符与后续 low 拼成合法代理对, 两处均为原版明显缺陷, 不复刻。
     */
    internal fun encodeQueryParams(params: String, codec: TextCharsetCodec): String =
        urlQueryEncoder.encode(params, codec)

    private val HEX = "0123456789ABCDEF".toCharArray()
}
