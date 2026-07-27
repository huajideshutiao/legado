package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

/**
 * [ImageBitmapLoader] 的 Android stub 实现。
 *
 * Android 端无 `javax.imageio.ImageIO` (JDK 专属), 需用 `android.graphics.BitmapFactory`
 * + `asImageBitmap()` 路径实现。本批仅建共享面 + desktop 接线, Android 实现留待后续补
 * (当前无 Android 端消费点, 返回 null 不影响功能)。
 *
 * 后续实现参照 [JvmImageBitmapLoader] 逻辑, 将 ImageIO.read 换为 BitmapFactory.decodeStream,
 * toComposeImageBitmap 换为 asImageBitmap。
 */
actual class ImageBitmapLoader {

    actual suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap? {
        // TODO: Android 端用 BitmapFactory + asImageBitmap 实现 (对照 JvmImageBitmapLoader)
        return null
    }
}
