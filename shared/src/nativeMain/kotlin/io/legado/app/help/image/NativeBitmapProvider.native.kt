package io.legado.app.help.image

import io.legado.app.model.fileBook.BitmapProvider
import io.legado.app.utils.File
import io.legado.app.utils.InputStream

/**
 * [BitmapProvider] 的 iOS / 鸿蒙共用实现, 解码/编码委托构造传入的 [ImageOps]
 * (iOS: UIImage + UIGraphics; 鸿蒙: napi PixelMap, 桥接未就绪时 encode 返回原始字节不抛异常)。
 *
 * 解 [input] 读到 ByteArray → [ImageOps.decode] → [ImageOps.encode] ("jpg", quality)
 * → 写入 [outFile] 路径 (父目录 mkdirs)。
 *
 * 注册: 各端 ProviderRegistry 里 `BitmapProviders.register(NativeBitmapProvider(平台 ImageOps))`。
 */
class NativeBitmapProvider(private val imageOps: ImageOps) : BitmapProvider {

    override fun decodeStreamAndCompressToJpeg(
        input: InputStream,
        outFile: File,
        quality: Int
    ): Boolean = runCatching {
        val bytes = input.readAllBytes()
        if (bytes.isEmpty()) return false
        val ref = imageOps.decode(bytes)
        val jpeg = imageOps.encode(ref, "jpg", quality)
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
