package io.legado.app.utils

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil

actual fun textCharsetCodec(name: String): TextCharsetCodec =
    JvmTextCharsetCodec(charset(name))

/** 已有 [Charset] 实例直取编解码器, 避免按名重建 (供 PercentCodecExt 等使用)。 */
internal fun textCharsetCodec(charset: Charset): TextCharsetCodec =
    JvmTextCharsetCodec(charset)

private class JvmTextCharsetCodec(private val charset: Charset) : TextCharsetCodec {

    override val name: String get() = charset.name()

    override fun decode(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, charset)

    override fun encode(str: String): ByteArray = str.toByteArray(charset)

    override fun newEncoder(): TextCharsetEncoder = JvmTextCharsetEncoder(charset.newEncoder())
}

/**
 * 有状态编码器: [encode] 每次接收完整文本片段, 状态跨片段保留 (BOM 由底层编码器按首输入
 * 写入, 空片段不写 BOM); 容量按 maxBytesPerChar 契约预分配, 实际 OVERFLOW 扩容续编。
 * 末尾孤立 high surrogate: JDK 编码器 (UTF_8/UTF-16/UTF-32/GB18030) 等待代理对会带未消费
 * 输入返回 UNDERFLOW, 本片段即终结, 立即按 replacement() 输出并消费, 不允许带入下一段
 * 与后续 low surrogate 拼成伪代理对; 其余编码器带未消费输入返回 UNDERFLOW 仅此一例。
 */
private class JvmTextCharsetEncoder(encoder: CharsetEncoder) : TextCharsetEncoder {

    private val encoder = encoder
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    override fun encode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val cb = CharBuffer.wrap(input)
        val maxBytesPerChar = encoder.maxBytesPerChar()
        var out = ByteBuffer.allocate(ceil(cb.remaining() * maxBytesPerChar).toInt())
        while (true) {
            val cr = encoder.encode(cb, out, false)
            if (cr.isOverflow()) {
                out = grow(out, cb, maxBytesPerChar)
                continue
            }
            if (!cb.hasRemaining()) break
            check(cb.remaining() == 1 && Character.isHighSurrogate(cb.get(cb.position()))) {
                "UNDERFLOW 带未消费输入 ${cb.remaining()} 字符"
            }
            val replacement = encoder.replacement()
            if (out.remaining() < replacement.size) {
                out = grow(out, cb, maxBytesPerChar)
                continue
            }
            out.put(replacement)
            cb.get()
        }
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    /** 按剩余输入的 maxBytesPerChar 上限扩容并续传已产出字节。 */
    private fun grow(out: ByteBuffer, cb: CharBuffer, maxBytesPerChar: Float): ByteBuffer {
        val next = ByteBuffer.allocate(
            out.capacity() + ceil(cb.remaining() * maxBytesPerChar).toInt().coerceAtLeast(1)
        )
        out.flip()
        next.put(out)
        return next
    }
}
