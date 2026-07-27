package io.legado.app.service

import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.ui.book.read.ReadBookViewModelShared

/**
 * [ReadAloudChapterNavigator] 的默认实现（KMP commonMain, 各平台共用）。
 *
 * 桥接 [ReadBookViewModelShared] (UI 层 ViewModel) 到 commonMain 的
 * [ReadAloudControllerShared], 让控制器可以:
 * - 读章节总数 ([ReadBookViewModelShared.chapterSize])
 * - 加载章节段落 (查 [BookStorageProviders] 本地缓存, 切段)
 * - 触发切章 ([ReadBookViewModelShared.moveToNextChapter] /
 *   [ReadBookViewModelShared.moveToPrevChapter] / [ReadBookViewModelShared.loadChapter])
 *
 * # 章节内容加载策略
 *
 * - **本地缓存命中**: 直接 [BookStorageProviders.get].getContent + [ReadAloudQueue.splitParagraphs]
 * - **本地缓存未命中**: 返回 emptyList 并触发 [ReadBookViewModelShared.loadChapter]
 *   异步联网拉取 (ControllerShared state 置 ERROR, 用户重试时缓存已就绪)
 *
 * 这与 app 端 `BaseReadAloudService.newReadAloud` → `CacheBook.getChapterContent`
 * 编排对齐, 但简化为同步读 + 异步预热 (避免主线程阻塞)。
 *
 * # 下沉说明
 *
 * 原桌面端 `DesktopReadAloudNavigator` (desktop/src/main/kotlin/.../tts/TtsControlPanel.kt)
 * 完全使用 commonMain API (ReadBookViewModelShared + BookStorageProviders + ReadAloudQueue),
 * 无 desktop 专属依赖, 故下沉到 commonMain 供 desktop/iOS 共用。desktop 端原类名保留为
 * typealias 维持 import 兼容; iOS 端直接使用本类。
 *
 * @param viewModel 当前 ReaderScreen 的 ViewModel 实例
 */
class DefaultReadAloudNavigator(
    private val viewModel: ReadBookViewModelShared,
) : ReadAloudChapterNavigator {

    /** 通过 ViewModel.chapterSize 拿章节总数。 */
    override val chapterCount: Int
        get() = viewModel.chapterSize

    /**
     * 加载指定章节的段落列表 (按 \n 切段)。
     *
     * 同步查本地缓存, 缓存未命中时返回 emptyList + 触发异步拉取。
     */
    override fun loadChapterParagraphs(chapterIndex: Int): List<String> {
        val book = viewModel.book.value ?: return emptyList()
        val chapter = viewModel.chapterList.value.getOrNull(chapterIndex) ?: run {
            // 章节列表未加载, 触发 loadChapter 异步拉取章节列表
            viewModel.loadChapter(chapterIndex)
            return emptyList()
        }
        val content = runCatching {
            BookStorageProviders.get().getContent(book, chapter)
        }.getOrNull()
        if (content.isNullOrBlank()) {
            // 缓存未命中: 触发 ViewModel 异步联网拉取 (loadChapter 内部走 WebBook.getContentAwait)
            // ControllerShared 看到 emptyList 会 state 置 ERROR, 用户重试时缓存已就绪
            viewModel.loadChapter(chapterIndex)
            return emptyList()
        }
        return ReadAloudQueue.splitParagraphs(content)
    }

    /**
     * 切到指定章节 (触发 ViewModel.loadChapter 联网拉取 + 排版)。
     *
     * 不阻塞, 与 [loadChapterParagraphs] 解耦:
     * - [moveToChapter] 让 ViewModel 开始拉取
     * - [loadChapterParagraphs] 同步读缓存
     */
    override fun moveToChapter(chapterIndex: Int) {
        viewModel.loadChapter(chapterIndex)
    }

    /** 切到下一章 (调用 viewModel.moveToNextChapter, 内部触发 loadChapter)。 */
    override fun moveToNextChapter() {
        viewModel.moveToNextChapter()
    }

    /** 切到上一章。 */
    override fun moveToPrevChapter() {
        viewModel.moveToPrevChapter()
    }
}
