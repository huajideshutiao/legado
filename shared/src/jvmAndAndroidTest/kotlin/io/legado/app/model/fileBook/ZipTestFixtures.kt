package io.legado.app.model.fileBook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipOutputStream

/** 用 java.util.zip 生成真实 zip 字节样本 */
object ZipTestFixtures {

    class Spec(
        val name: String,
        val data: ByteArray,
        val stored: Boolean = false,
        val extra: ByteArray? = null,
    )

    fun buildZip(specs: List<Spec>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, Charsets.ISO_8859_1).use { zos ->
            specs.forEach { spec ->
                val e = java.util.zip.ZipEntry(spec.name)
                if (spec.stored) {
                    e.method = java.util.zip.ZipEntry.STORED
                    e.size = spec.data.size.toLong()
                    e.crc = CRC32().apply { update(spec.data) }.value
                }
                spec.extra?.let { e.extra = it }
                zos.putNextEntry(e)
                zos.write(spec.data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 合法 TLV extra 字段：4 字节头 + payload */
    fun extraField(totalSize: Int): ByteArray {
        require(totalSize >= 4)
        return ByteArray(totalSize).also {
            it[0] = 0xFE.toByte(); it[1] = 0xCA.toByte() // id 0xCAFE
            val len = totalSize - 4
            it[2] = (len and 0xff).toByte(); it[3] = (len ushr 8 and 0xff).toByte()
        }
    }

    /** 内存假 RangedSource：截断到实际大小，模拟服务端 Range 语义 */
    fun sourceOf(bytes: ByteArray, readOffsets: MutableList<Long>? = null) =
        RangedSource { offset, length, _ ->
            readOffsets?.add(offset)
            val start = offset.coerceAtLeast(0L).toInt()
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            if (start >= end) ByteArray(0) else bytes.copyOfRange(start, end)
        }

    /** 按 entry method 还原内容（8=deflate raw，0=stored） */
    fun decode(chunk: RemoteZipCore.Chunk, method: Int): ByteArray {
        val bis = ByteArrayInputStream(chunk.bytes, chunk.offset, chunk.length)
        return if (method == 8) {
            InflaterInputStream(bis, Inflater(true)).use { it.readBytes() }
        } else {
            bis.readBytes()
        }
    }

    /** 裸 raw-deflate（nowrap）压缩，产出等价 zip method==8 的数据区字节 */
    fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }
}
