package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book

/**
 * [EpubFilePlatform] 的 Native (iOS/ohos) actual 实现 (stub 降级)。
 *
 * Native 端无 epublib + Bitmap 平台 API, 暂不解析 epub 本地书。
 * 与 jvmMain 占位行为一致, 后续接入跨平台 epub 库再真实化。
 *
 * 模式参考 jvmMain EpubFilePlatform.jvm.kt 的 stub 实现。
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
