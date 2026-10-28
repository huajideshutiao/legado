package com.legado.charset

import com.fleeksoft.charset.CharsetDecoder
import com.fleeksoft.charset.CharsetEncoder
import com.fleeksoft.charset.cs.Unicode

/**
 * UTF-32 字符集族 (fork 自 com.fleeksoft.charset:charset:0.0.8 的 cs/utf/UTF_32*.kt,
 * 换包避免与依赖 klib 同包重类, 解码代理区约束见 [Utf32Coder] 头注释):
 * - Utf32: 解码按 BOM 判序并剥离 (无 BOM 默认 BE), 编码输出 BE 无 BOM;
 * - Utf32LE/Utf32BE: 定字节序, 编解码均无 BOM。
 * 语义与 JVM UTF_32/UTF_32LE/UTF_32BE 一致。
 */
class Utf32 : Unicode("UTF-32") {
    override fun newDecoder(): CharsetDecoder = Utf32Coder.Decoder(this, Utf32Coder.NONE)

    override fun newEncoder(): CharsetEncoder = Utf32Coder.Encoder(this, Utf32Coder.BIG, false)
}

class Utf32LE : Unicode("UTF-32LE") {
    override fun newDecoder(): CharsetDecoder = Utf32Coder.Decoder(this, Utf32Coder.LITTLE)

    override fun newEncoder(): CharsetEncoder = Utf32Coder.Encoder(this, Utf32Coder.LITTLE, false)
}

class Utf32BE : Unicode("UTF-32BE") {
    override fun newDecoder(): CharsetDecoder = Utf32Coder.Decoder(this, Utf32Coder.BIG)

    override fun newEncoder(): CharsetEncoder = Utf32Coder.Encoder(this, Utf32Coder.BIG, false)
}
