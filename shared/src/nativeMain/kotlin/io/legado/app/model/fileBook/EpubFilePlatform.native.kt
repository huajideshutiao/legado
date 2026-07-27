package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book

/**
 * [EpubFilePlatform] 的 Native (iOS/ohos) actual 实现 (stub 降级)。
 *
 * 本 expect/actual 仅服务 jvmAndAndroid 端 epublib 路径; native 端 epub 解析走
 * nativeMain [EpubFile] (commonMain EpubParser + BitmapProviders), 不经这些 stub。
 */

actual class LocalEpubResource actual constructor(book: Book) {
    // stub: native 端无 epublib, epubBook 返回 null 降级
    actual val epubBook: Any? = null

    // stub: native 端无底层句柄, close 空实现 (幂等)
    actual fun close() {
        // no-op
    }
}

actual fun decodeBitmap(bytes: ByteArray): Any? {
    // stub: native 端无 BitmapFactory, 返回 null 降级
    return null
}

actual fun compressBitmap(bitmap: Any?, format: String, quality: Int, destPath: String): Boolean {
    // stub: native 端无 Bitmap.compress, 返回 false 降级
    return false
}
