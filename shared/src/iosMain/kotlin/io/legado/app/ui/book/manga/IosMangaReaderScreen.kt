package io.legado.app.ui.book.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * iOS 端漫画阅读页入口 (包装 shared/sharedUiMain 的 [MangaReaderScreenContent])。
 *
 * 阻塞点: imageSlot 依赖平台图片加载 (iOS 端未接入 CMP 图片加载框架),
 * 本入口仅做 stub 让编译通过, 后续 KP6+ 接入 iOS 平台图片加载。
 *
 * @param onBack 返回回调
 * @param onOpenToc 跳转目录 (由 IosNavHost 注入)
 * @param onOpenChangeSource 跳转切换书源 (由 IosNavHost 注入)
 */
@Composable
fun IosMangaReaderScreen(
    onBack: () -> Unit,
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
) {
    // KP-iOS: 全部 stub 数据, imageSlot 待接入
    // iOS 端待接入提示文案 (回调 lambda 非 @Composable, 需预先缓存)
    val prevChapterText = rememberString("ios_prev_chapter_not_implemented")
    val nextChapterText = rememberString("ios_next_chapter_not_implemented")
    val retryText = rememberString("ios_retry_not_implemented")
    Box(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("manga_read"),
            onBack = onBack,
        )
        MangaReaderScreenContent(
            bookName = "",
            chapterTitle = "",
            images = emptyList(),
            curChapterIndex = 0,
            chapterSize = 0,
            horizontal = false,
            autoPageSpeed = 0,
            loading = false,
            error = null,
            onBack = onBack,
            onPrevChapter = {
                Toasters.get().toast(prevChapterText)
            },
            onNextChapter = {
                Toasters.get().toast(nextChapterText)
            },
            onRetry = {
                Toasters.get().toast(retryText)
            },
            onOpenToc = onOpenToc,
            onOpenChangeSource = onOpenChangeSource,
            // KP-iOS: imageSlot 依赖平台图片加载, stub 空 Box
            imageSlot = { _, _, _ -> Box(Modifier) },
        )
    }
}
