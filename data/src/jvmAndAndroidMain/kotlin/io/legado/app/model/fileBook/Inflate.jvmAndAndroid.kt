package io.legado.app.model.fileBook

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** java.util.zip.Inflater(nowrap=true)，字节输出与 app 侧 InflaterInputStream 逐字节一致 */
actual fun inflateRaw(
    compressed: ByteArray, offset: Int, length: Int, expectedSize: Int
): ByteArray {
    val inflater = Inflater(true)
    inflater.setInput(compressed, offset, length)
    try {
        val out = ByteArrayOutputStream(if (expectedSize > 0) expectedSize else maxOf(length * 2, 32))
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n > 0) {
                out.write(buf, 0, n)
            } else if (inflater.finished() || inflater.needsDictionary() || inflater.needsInput()) {
                break
            }
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
