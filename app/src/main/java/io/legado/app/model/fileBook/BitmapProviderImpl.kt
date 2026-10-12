package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.legado.app.help.writeBytesAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * [BitmapProvider] Android 实现 (app 端)。
 *
 * 用 `android.graphics.BitmapFactory.decodeStream` 解码图片,
 * `Bitmap.compress` 压缩为 JPEG 后整份落盘 (同目录临时件 + 改名就位, 见 [writeBytesAtomically]),
 * 与原 [CbzFile.extractCover] 内联逻辑的产物一致 (CbzFile 已下沉 shared, 不再直接依赖 android.graphics)。
 *
 * 注册: [registerAndroidWebBookProviders] 中 `BitmapProviders.register(BitmapProviderImpl)`。
 */
object BitmapProviderImpl : BitmapProvider {

    override fun decodeStreamAndCompressToJpeg(
        input: InputStream,
        outFile: File,
        quality: Int
    ): Boolean {
        return runCatching {
            BitmapFactory.decodeStream(input)?.let { cover ->
                val jpeg = ByteArrayOutputStream()
                cover.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
                cover.recycle()
                // 整份落盘: 封面提取按"目标文件已存在即有效"被复用, 半截 JPEG 会被永久当成封面
                writeBytesAtomically(outFile.absolutePath, jpeg.toByteArray())
            } ?: false
        }.getOrDefault(false)
    }
}
