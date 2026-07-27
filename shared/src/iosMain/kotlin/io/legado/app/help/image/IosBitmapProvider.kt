package io.legado.app.help.image

import io.legado.app.model.fileBook.BitmapProvider
import io.legado.app.utils.File
import io.legado.app.utils.InputStream

/**
 * [BitmapProvider] iOS 实现, 委托 [IosImageOps] (UIImage + UIGraphics) 完成解码/编码。
 *
 * 解 [input] 读到 ByteArray → [IosImageOps.decode] → [IosImageOps.encode] ("jpg", quality)
 * → [kotlin.io.File] 写入 [outFile.path] (父目录 mkdirs)。
 *
 * 注册: [io.legado.app.help.config.registerIosProviders] 中 `BitmapProviders.register(IosBitmapProvider)`。
 */
object IosBitmapProvider : BitmapProvider {

    override fun decodeStreamAndCompressToJpeg(
        input: InputStream,
        outFile: File,
        quality: Int
    ): Boolean = runCatching {
        val bytes = input.readAllBytes()
        if (bytes.isEmpty()) return false
        val ref = IosImageOps.decode(bytes)
        val jpeg = IosImageOps.encode(ref, "jpg", quality)
        val target = File(outFile.path)
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
