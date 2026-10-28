package io.legado.app.utils

import com.fleeksoft.charset.Charset
import com.fleeksoft.charset.CharsetEncoder
import com.fleeksoft.charset.CodingErrorAction
import com.fleeksoft.charset.cs.US_ASCII
import com.fleeksoft.charset.cs.euc.EUC_CN
import com.fleeksoft.charset.cs.ext.Big5
import com.fleeksoft.charset.cs.ext.GB18030
import com.fleeksoft.charset.cs.ext.GBK
import com.fleeksoft.charset.cs.iso.ISO_8859_1
import com.fleeksoft.charset.cs.utf.UTF_16
import com.fleeksoft.charset.cs.utf.UTF_16BE
import com.fleeksoft.charset.cs.utf.UTF_16LE
import com.fleeksoft.charset.cs.utf.UTF_8
import com.fleeksoft.io.ByteBuffer
import com.fleeksoft.io.ByteBufferFactory
import com.fleeksoft.io.CharBuffer
import com.fleeksoft.io.CharBufferFactory
import com.legado.charset.Utf32
import com.legado.charset.Utf32BE
import com.legado.charset.Utf32LE
import io.legado.app.exception.NoStackTraceException
import kotlin.math.ceil

// 字符集对象不可变 (状态在每次新建的 encoder/decoder 内), 单例持有;
// GBK/Big5 来自构建期解包的 charset-ext 源码, GB18030 来自 vendor 固定提交源文件,
// UTF-32 族为本仓换包小改版, 其余来自 charset 依赖。
private val GBK_CHARSET = GBK()
private val BIG5_CHARSET = Big5()
private val GB18030_CHARSET = GB18030.INSTANCE
private val UTF32_CHARSET = Utf32()
private val UTF32LE_CHARSET = Utf32LE()
private val UTF32BE_CHARSET = Utf32BE()

/**
 * [TextCharsetCodec] nativeMain actual (iOS/鸿蒙共用): 委托 com.fleeksoft.charset
 * (OpenJDK sun.nio.cs 移植)。
 *
 * 平台原生转换器与 JVM 映射不保证一致 (iOS CP936 对 U+20AC 输出 0x80 而 JDK GBK 是 A2E3;
 * 鸿蒙 @ohos.util 对 GB2312 默认替换 A1A1 不是 '?'; 严格解码+裁尾重试也不是 REPLACE),
 * 故编解码均 REPLACE, 与 JVM 字节级一致: 坏字节/不可映射字符按各字符集 replacement 替换
 * (合法代理对替换一次), UTF-32 解码代理区/超范围码点替换 U+FFFD (com.legado.charset.Utf32Coder)。
 * 可支持名/别名与 JVM Charset.forName 逐字对齐 (OpenJDK jdk-charsets 一手清单),
 * 未知名抛异常, 不静默退化 UTF-8。
 */
actual fun textCharsetCodec(name: String): TextCharsetCodec {
    // JVM Charset.forName 仅大小写无关匹配 ('_' 与 '-' 不互换), 别名按 JDK 原样列出
    return when (name.uppercase()) {
        "UTF-8", "UTF8", "UNICODE-1-1-UTF-8" -> FleeksoftCharsetCodec("UTF-8", UTF_8.INSTANCE)
        "UTF-16LE", "UTF_16LE", "X-UTF-16LE", "UNICODELITTLEUNMARKED" ->
            FleeksoftCharsetCodec("UTF-16LE", UTF_16LE.INSTANCE)
        "UTF-16BE", "UTF_16BE", "ISO-10646-UCS-2", "X-UTF-16BE", "UNICODEBIGUNMARKED" ->
            FleeksoftCharsetCodec("UTF-16BE", UTF_16BE.INSTANCE)
        "UTF-16", "UTF_16", "UTF16", "UNICODE", "UNICODEBIG" -> FleeksoftCharsetCodec("UTF-16", UTF_16.INSTANCE)
        "UTF-32LE", "UTF_32LE", "X-UTF-32LE" -> FleeksoftCharsetCodec("UTF-32LE", UTF32LE_CHARSET)
        "UTF-32BE", "UTF_32BE", "X-UTF-32BE" -> FleeksoftCharsetCodec("UTF-32BE", UTF32BE_CHARSET)
        "UTF-32", "UTF_32", "UTF32" -> FleeksoftCharsetCodec("UTF-32", UTF32_CHARSET)
        "ISO-8859-1", "ISO-IR-100", "ISO_8859-1", "LATIN1", "L1", "IBM819", "CP819",
        "CSISOLATIN1", "819", "IBM-819", "ISO8859_1", "ISO_8859-1:1987", "8859_1", "ISO8859-1",
        "ISO_8859_1" -> FleeksoftCharsetCodec("ISO-8859-1", ISO_8859_1.INSTANCE)
        "US-ASCII", "ISO-IR-6", "ANSI_X3.4-1986", "ISO_646.IRV:1991", "ASCII",
        "ISO646-US", "US", "IBM367", "CP367",
        "CSASCII", "646", "ISO_646.IRV:1983", "ANSI_X3.4-1968", "ASCII7" ->
            FleeksoftCharsetCodec("US-ASCII", US_ASCII.INSTANCE)
        "GBK", "WINDOWS-936", "CP936" -> FleeksoftCharsetCodec("GBK", GBK_CHARSET)
        "GB2312", "GB2312-80", "GB2312-1980", "EUC-CN", "EUCCN", "X-EUC-CN", "EUC_CN" ->
            FleeksoftCharsetCodec("GB2312", EUC_CN.INSTANCE)
        // GB18030 实现为 2022 版 (vendor 源 IS_2000=false), 不接受 2000 别名:
        // OpenJDK 也只按实际实现给一个版本别名 (StandardCharsets.aliases_GB18030)
        "GB18030", "GB18030-2022" -> FleeksoftCharsetCodec("GB18030", GB18030_CHARSET)
        "BIG5", "CSBIG5" -> FleeksoftCharsetCodec("Big5", BIG5_CHARSET)
        else -> throw NoStackTraceException("平台暂不支持 $name 字符集")
    }
}

/** 按名表委托 com.fleeksoft.charset 的编解码实现; 编解码均 REPLACE。 */
private class FleeksoftCharsetCodec(
    override val name: String,
    private val charset: Charset,
) : TextCharsetCodec {

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBufferFactory.wrap(bytes, offset, length)).toString()
    }

    override fun encode(str: String): ByteArray {
        if (str.isEmpty()) return ByteArray(0)
        val bb = replaceEncoder(charset).encode(CharBufferFactory.wrap(str))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }

    override fun newEncoder(): TextCharsetEncoder = FleeksoftCharsetEncoder(replaceEncoder(charset))
}

private fun replaceEncoder(charset: Charset): CharsetEncoder =
    charset.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

/**
 * 有状态编码器: [encode] 每次接收完整文本片段, 状态跨片段保留 (BOM 由底层编码器按首输入
 * 写入, 空片段不写 BOM); 容量按 maxBytesPerChar 契约预分配, 实际 OVERFLOW 扩容续编。
 * 末尾孤立 high surrogate: 编码器等待代理对会带未消费输入返回 UNDERFLOW, 本片段即终结,
 * 立即按 replacement() 输出并消费, 不允许带入下一段与后续 low surrogate 拼成伪代理对。
 */
private class FleeksoftCharsetEncoder(encoder: CharsetEncoder) : TextCharsetEncoder {

    private val encoder = encoder

    override fun encode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val cb = CharBufferFactory.wrap(input)
        val maxBytesPerChar = encoder.maxBytesPerChar()
        var out = ByteBufferFactory.allocate(ceil(cb.remaining() * maxBytesPerChar).toInt())
        while (true) {
            val cr = encoder.encode(cb, out, false)
            if (cr.isOverflow()) {
                out = grow(out, cb, maxBytesPerChar)
                continue
            }
            if (!cb.hasRemaining()) break
            if (cb.remaining() == 1 && cb.get(cb.position()).isHighSurrogate()) {
                val replacement = encoder.replacement()
                if (out.remaining() < replacement.size) {
                    out = grow(out, cb, maxBytesPerChar)
                    continue
                }
                out.put(replacement)
                cb.get()
                continue
            }
            // 库内 DoubleByte.Encoder (GBK/Big5/GB2312) 输出容量不足错报 UNDERFLOW
            // (JDK 原版为 OVERFLOW, 见 sun.nio.cs.DoubleByte#encodeArrayLoop), 按容量扩容
            out = grow(out, cb, maxBytesPerChar)
        }
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    /** 按剩余输入的 maxBytesPerChar 上限扩容并续传已产出字节。 */
    private fun grow(out: ByteBuffer, cb: CharBuffer, maxBytesPerChar: Float): ByteBuffer {
        val next = ByteBufferFactory.allocate(
            out.capacity() + ceil(cb.remaining() * maxBytesPerChar).toInt().coerceAtLeast(1)
        )
        out.flip()
        next.put(out)
        return next
    }
}
