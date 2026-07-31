@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.UIKit.UIImage
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIImageView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// iOS 漫画阅读平台能力: 图片流复用 shared 提取器, 渲染用 UIKitView + UIImageView
object IosMangaReaderPlatform : MangaReaderScreenModel.Platform {

    // 图片 URL 提取: 复用 commonMain 的 MangaImageExtractorShared (与 desktop 同源)
    override fun flowImages(bookChapter: BookChapter, content: String): Flow<String> =
        MangaImageExtractorShared.extractImageUrls(content).asFlow()

    // UIKitView + UIImageView: 真实图片渲染, NSURLSession 异步加载
    @Composable
    override fun Image(
        url: String,
        modifier: Modifier,
        horizontal: Boolean,
        book: Book?,
        source: BookSource?,
        colorFilterConfig: MangaColorFilterConfig,
        grayEnabled: Boolean,
    ) {
        UIKitView(
            factory = { UIImageView() },
            update = { view ->
                view.contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                view.clipsToBounds = true
                // 未加载时异步拉取图片 (主线程刷新 UIImage)
                if (view.image == null) {
                    val nsUrl = NSURL.URLWithString(url) ?: return@UIKitView
                    NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, _, _ ->
                        data?.let { bytes ->
                            dispatch_async(dispatch_get_main_queue()) {
                                view.image = UIImage.imageWithData(bytes)
                            }
                        }
                    }.resume()
                }
            },
            modifier = modifier,
        )
    }
}
