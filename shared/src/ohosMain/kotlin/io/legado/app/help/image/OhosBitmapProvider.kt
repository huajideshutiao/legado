package io.legado.app.help.image

import io.legado.app.model.fileBook.BitmapProvider
import io.legado.app.utils.File
import io.legado.app.utils.InputStream

/**
 * [BitmapProvider] 鸿蒙实现, 委托 [OhosImageOps] (napi PixelMap) 完成解码/编码。
 *
 * 解 [input] 读到 ByteArray → [OhosImageOps.decode] → [OhosImageOps.encode] ("jpg", quality)
 * → [kotlin.io.File] 写入 [outFile.path] (父目录 mkdirs)。
 *
 * 桥接未就绪时 [OhosImageOps] 降级 (encode 返回原始字节), 仍写入封面文件, 不抛异常。
 *
 * 注册: [io.legado.app.help.config.registerOhosProviders] 中 `BitmapProviders.register(OhosBitmapProvider)`。
 */
object OhosBitmapProvider : BitmapProvider {

    override fun decodeStreamAndCompressToJpeg(
        input: InputStream,
        outFile: File,
        quality: Int
    ): Boolean = runCatching {
        val bytes = input.readAllBytes()
        if (bytes.isEmpty()) return false
        val ref = OhosImageOps.decode(bytes)
        val jpeg = OhosImageOps.encode(ref, "jpg", quality)
        val target = kotlin.io.File(outFile.path)
        target.parentFile?.mkdirs()
        target.writeBytes(jpeg)
        true
    }.getOrDefault(false)

    private fun InputStream.readAllBytes(): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n == -1) break
            chunks.add(buffer.copyOf(n))
            total += n
        }
        val out = ByteArray(total)
        var off = 0
        for (c in chunks) {
            c.copyInto(out, off)
            off += c.size
        }
        return out
    }
}
