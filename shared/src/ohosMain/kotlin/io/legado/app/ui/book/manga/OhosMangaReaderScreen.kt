package io.legado.app.ui.book.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.platform.rememberString

/**
 * 鸿蒙端漫画阅读页入口 (包装 shared/sharedUiMain 的 [MangaReaderScreenContent])。
 *
 * 对照 desktop [io.legado.desktop.ui.book.manga.MangaReaderScreen] 装配模式:
 * - 注入 [OhosMangaImageExtractor] 实现 shared [MangaImageExtractor]
 * - 装配 [MangaReaderViewModelShared] (scope=rememberCoroutineScope, config=DEFAULT)
 * - [LaunchedEffect] 调 [MangaReaderViewModelShared.initData] 触发加载
 * - [collectAsState] 收集 VM StateFlow, 映射到 [MangaReaderScreenContent] 入参
 *
 * # 阻塞点
 *
 * - **imageSlot** 依赖平台图片加载 (鸿蒙端原 ArkTS 用 ArkUI Image 组件直接加载 URL,
 *   Compose 侧未接入 Coil3 等图片加载框架), 本入口仅做 stub 让编译通过,
 *   后续接入鸿蒙平台图片加载。
 * - **路由参数**: 鸿蒙 OhosNavHost 当前不向 MANGA_READER 注入 Book (与 iOS 差异),
 *   故 initData 调用时 IntentData.book 可能为空, VM 会写入 "没有找到书" 错误,
 *   后续 NavHost 接入 Book 参数后自然生效。
 *
 * 原 ArkTS MangaReader.ets 通过 napi loadMangaChapter(bookUrl, chapterIndex) 取图片 URL 列表,
 * Scroll + Column + Image 实现上下滑动浏览; Compose 化后 imageSlot 需在 ohosMain 侧实现
 * (后续 KP, 用 Coil3 KMP)。
 *
 * @param onBack 返回回调
 * @param onOpenToc 跳转目录 (由 OhosNavHost 注入)
 * @param onOpenChangeSource 跳转切换书源 (由 OhosNavHost 注入)
 */
@Composable
fun OhosMangaReaderScreen(
    onBack: () -> Unit,
    onOpenToc: () -> Unit = {},
    onOpenChangeSource: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // shared VM 注入: OhosMangaImageExtractor + MangaReaderConfig.DEFAULT
    // 对照 desktop MangaReaderScreen 第 141-147 行装配模式 (鸿蒙端无 PreferenceStore, 用 DEFAULT)
    val viewModel = remember {
        MangaReaderViewModelShared(
            scope = scope,
            imageExtractor = OhosMangaImageExtractor(),
            config = MangaReaderConfig.DEFAULT,
        )
    }

    // 收集 shared VM 状态 (StateFlow 经 collectAsState 订阅, 对照 desktop 第 150-155 行)
    val book by viewModel.book.collectAsState()
    val durChapterIndex by viewModel.durChapterIndex.collectAsState()
    val durChapter by viewModel.durChapter.collectAsState()
    val mangaContent by viewModel.mangaContent.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // 适配 shared VM 状态 -> shared ScreenContent 入参 (对照 desktop 第 159-169 行映射)
    // images: MangaContent.items 含 MangaPage + ReaderLoading 头, 仅取 MangaPage.mImageUrl
    val images = remember(mangaContent) {
        mangaContent?.items
            ?.filterIsInstance<MangaPage>()
            ?.map { it.mImageUrl }
            ?: emptyList()
    }
    // chapterSize: shared VM 是 plain Int (非 StateFlow), 随 mangaContent 变化重组时读取最新值
    val chapterSize = viewModel.chapterSize
    val chapterTitle = durChapter?.title ?: ""
    val bookName = book?.name ?: ""
    // error: shared VM 是 Pair<String, Boolean>?, ScreenContent 取 .first 作错误文案
    val errorStr = error?.first

    // 初始化数据 (对照 desktop 第 173-176 行 LaunchedEffect(book.bookUrl) -> initData)
    // 鸿蒙端无 Book 参数, 仅触发 initData; 若 IntentData.book 为空, VM 写入 "没有找到书" 错误
    LaunchedEffect(Unit) {
        viewModel.initData()
    }
    // 退出时取消预下载 + 释放 VM 资源 (对照 desktop 第 179-185 行 DisposableEffect)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelPreDownloadTask()
            viewModel.onCleared()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AppTitleBar(
            title = rememberString("manga_read"),
            onBack = onBack,
        )
        MangaReaderScreenContent(
            bookName = bookName,
            chapterTitle = chapterTitle,
            images = images,
            curChapterIndex = durChapterIndex,
            chapterSize = chapterSize,
            horizontal = false,
            autoPageSpeed = 0,
            loading = loading,
            error = errorStr,
            onBack = onBack,
            // 方法带默认参数 toFirst, 函数引用类型为 (Boolean)->Boolean 不可直接赋给 ()->Unit,
            // 故用 lambda 调用 (与 desktop 第 198-199 行一致)
            onPrevChapter = { viewModel.moveToPrevChapter() },
            onNextChapter = { viewModel.moveToNextChapter() },
            onRetry = { viewModel.loadContent() },
            onOpenToc = onOpenToc,
            onOpenChangeSource = onOpenChangeSource,
            // KP-ohos: imageSlot 依赖平台图片加载 (Coil3), stub 空 Box
            imageSlot = { _, _, _ -> Box(Modifier) },
        )
    }
}
