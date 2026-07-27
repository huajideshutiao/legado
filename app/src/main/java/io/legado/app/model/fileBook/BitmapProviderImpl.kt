package io.legado.app.model.fileBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.legado.app.utils.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * [BitmapProvider] Android 实现 (app 端)。
 *
 * 用 `android.graphics.BitmapFactory.decodeStream` 解码图片,
 * `Bitmap.compress` 压缩为 JPEG 写入文件, 行为与原 [CbzFile.extractCover]
 * 内联逻辑完全一致 (CbzFile 已下沉 shared, 不再直接依赖 android.graphics)。
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
                FileOutputStream(FileUtils.createFileIfNotExist(outFile.absolutePath)).use {
                    cover.compress(Bitmap.CompressFormat.JPEG, quality, it)
                }
                cover.recycle()
                true
            } ?: false
        }.getOrDefault(false)
    }
}
