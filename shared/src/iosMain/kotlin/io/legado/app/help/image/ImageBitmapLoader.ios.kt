package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

/**
 * [ImageBitmapLoader] 的 iOS stub 实现。
 *
 * iOS 端需用 `UIImage` / `Skia` 解码路径实现。本批仅建共享面 + desktop 接线,
 * iOS 实现留待后续补 (当前无 iOS 端消费点, 返回 null 不影响功能)。
 */
actual class ImageBitmapLoader {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? {
        // TODO: iOS 端用 UIImage / Skia 解码实现 (对照 JvmImageBitmapLoader)
        return null
    }
}
