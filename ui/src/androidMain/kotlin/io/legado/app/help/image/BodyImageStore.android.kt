package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.legado.app.model.ImageProvider

/**
 * Android 端正文图片表: 直接落在 [ImageProvider] 的位图 LRU 上。
 *
 * web 出图 (`BookController.getImg`) 与阅读页内嵌图读的是同一份位图 (同一个 key 空间按书隔离),
 * 因此共用同一个字节预算, 不再是两张各吃设置的正文表。位图转换无拷贝:
 * `ImageBitmap.asAndroidBitmap()` / `Bitmap.asImageBitmap()` 只是包装同一个位图。
 *
 * 预算 (容量与订阅) 归 [ImageProvider] 自己所有: 它在对象初始化时就按当前设置对齐并订阅变化,
 * 因此本类不再单建订阅, 也不会把必要订阅变成可选。
 */
private object AndroidBodyImageStore : BodyImageStore {

    private val lru get() = ImageProvider.bitmapLruCache

    override fun peek(key: String): ImageBitmap? {
        val bitmap = lru[key] ?: return null
        if (bitmap.isRecycled) {
            lru.remove(key)
            return null
        }
        return bitmap.asImageBitmap()
    }

    override fun put(key: String, bitmap: ImageBitmap): Boolean {
        val android = bitmap.asAndroidBitmap()
        if (android.byteCount > lru.maxSize()) {
            lru.remove(key)
            return false
        }
        lru.put(key, android)
        return true
    }

    override fun clearBook(bookKey: String) {
        lru.snapshot().keys.forEach { if (it.startsWith(bookKey)) lru.remove(it) }
    }

    override fun clear() {
        lru.evictAll()
    }

    override fun applyBudget() {
        lru.resize(ImageProvider.cacheSize)
    }
}

internal actual fun bodyImageStore(): BodyImageStore = AndroidBodyImageStore
