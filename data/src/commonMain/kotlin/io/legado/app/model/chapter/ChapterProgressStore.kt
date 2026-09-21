package io.legado.app.model.chapter

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.utils.postEvent
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 阅读进度落库与云端同步 (四模式共用)。
 *
 * 原版由 `Book.saveRead()` 扩展 + `BaseReadViewModel.uploadProgress/syncProgress` 承担;
 * KMP 化后四模式各写一份 (文字 `saveProgressAwait`、漫画 `saveReadAwait`、
 * 音频 `AudioPlayShared.saveRead`、视频 `saveRead`), 且分化出「有没有回写内存 book 实体」
 * 「有没有发 UP_BOOKSHELF」「章名要不要过替换规则」三处差异。此处收敛为单一实现。
 *
 * 章名一律过标题替换规则 + 内存目录优先取章 (原版 `ReadBook.saveRead` 口径);
 * 视频原先直接用 `chapter.title` 不过规则, 是缺陷 (书架/详情显示的章名与阅读页不一致), 不复刻。
 */
object ChapterProgressStore {

    /**
     * 落库当前进度并回写内存 [book] 实体。
     *
     * 内存回写是必需的: 详情页/目录页经 IntentData 交接读的是内存实体, 不回写就停在跳转前那一章。
     * 只 PATCH 进度列而非整行 update: 整行会冲掉后台 updateToc/refreshBookInfo 写入的最新元数据。
     *
     * @param durChapterPos 章内位置; 末页/末图停留时由调用方取负编码 (原版 `durChapterPos * -1`)
     * @param chapter 当前章 (取章名; null 时保留 book 原章名)
     */
    suspend fun save(
        book: Book,
        durChapterIndex: Int,
        durChapterPos: Int,
        chapter: BookChapter?,
    ) {
        val durChapterTitle = chapter?.getDisplayTitle(
            ContentProcessorProviders.get().getTitleReplaceRules(book),
            book.getUseReplaceRule(),
        ) ?: book.durChapterTitle
        val durChapterTime = systemCurrentTimeMillis()
        book.durChapterIndex = durChapterIndex
        book.durChapterPos = durChapterPos
        book.durChapterTitle = durChapterTitle
        book.durChapterTime = durChapterTime
        book.lastCheckCount = 0
        AppDbProviders.get().bookDao.updateProgress(
            bookUrl = book.bookUrl,
            durChapterIndex = durChapterIndex,
            durChapterPos = durChapterPos,
            durChapterTime = durChapterTime,
            durChapterTitle = durChapterTitle,
        )
        ReadTimeRecorder.flushAll()
    }

    /**
     * 落库 + 通知书架刷新 + 上传云端进度 (原版 onPause 的「先 saveRead 再 uploadProgress」顺序)。
     *
     * 上传读的是 DB 刚落的最新行而非内存实体, 避免长持有实体整行冲写并发修改。
     */
    suspend fun saveAndUpload(
        book: Book,
        durChapterIndex: Int,
        durChapterPos: Int,
        chapter: BookChapter?,
    ) {
        save(book, durChapterIndex, durChapterPos, chapter)
        // 落库后通知书架重查 (Room 失效推送为主, 显式事件作双保险, 保证立即刷新)
        postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
        uploadAwait(book.bookUrl)
    }

    /**
     * 上传进度: 读 DB 最新行上传, 成功后只落 syncTime。
     *
     * 失败只记日志: 上传是尽力而为, 不该把宿主协程 (音频侧 = Service 生命周期) 拖死。
     */
    suspend fun uploadAwait(bookUrl: String) {
        runCatching {
            val bookDao = AppDbProviders.get().bookDao
            val fresh = bookDao.getBook(bookUrl) ?: return
            val syncTimeBefore = fresh.syncTime
            // 内部已守卫 syncBookProgress / authorization, 成功时写 fresh.syncTime
            AppWebDavShared.uploadBookProgress(fresh)
            currentCoroutineContext().ensureActive()
            if (fresh.syncTime != syncTimeBefore) {
                bookDao.update(fresh)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传阅读进度失败\n${it.message}", it)
        }
    }

    /**
     * 三路云端进度比对 (原版 `BaseReadViewModel.syncProgress`)。
     *
     * @param onNewProgress 云端较新时回调 (通常弹确认框); 不给则不弹, 仅「本地较新→上传」生效
     */
    fun pullCloud(
        scope: CoroutineScope,
        book: Book,
        enabled: Boolean,
        onNewProgress: ((BookProgress) -> Unit)? = null,
    ) {
        if (!enabled) return
        if (book.isNotShelf) return
        scope.launch {
            AppWebDavShared.syncProgress(
                book = book,
                manual = false,
                onNewProgress = onNewProgress,
            )
        }
    }
}
