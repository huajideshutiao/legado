package io.legado.app.model.chapter

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 目录自动检查节流窗口 (原版 `ReadBook.upToc` / `ReadMangaViewModel.upToc` 同值 600000ms)。 */
private const val UP_TOC_THROTTLE_MS = 600_000L

/** 剩余未读章节少于此数才检查目录 (原版 `chapterSize - durChapterIndex - 1 >= 3` 反向守卫)。 */
private const val UP_TOC_REMAIN_THRESHOLD = 3

/**
 * 阅读/播放中的目录自动更新 (原版 `ReadBook.upToc` / `ReadMangaViewModel.upToc`)。
 *
 * 原版两份实现同骨架, KMP 化后成三份 (含 `ReadBookShared` 旧引擎), 且行为分化:
 * 文字版无 force 参数、失败静默 (`runCatching{}.onSuccess{}` 没有 onError 分支);
 * 漫画版有 force、失败发错误事件。此处按「漫画口径 + 失败必须可见」收敛。
 *
 * 音频/视频原先没有目录自动更新 (追更书播到最后一章就停), 接入本类后四模式一致。
 *
 * # 锁纪律
 * 节流的「读-改」与单飞标志的「判定-置位」在锁内 (并发调用只放一个过), 协程在锁外启动 ——
 * 任务内部可能回调宿主的装载逻辑 (同样取宿主的锁), 锁内启动即埋自锁死
 * (atomicfu 的锁在 Native 端不可重入)。原版漫画整段在锁内 launch, 是缺陷, 不复刻。
 *
 * @param scope 拉取任务作用域
 * @param onUpdated 目录增长且落库后回调 (刷新内存目录 / 补载下一章)
 * @param onError 拉取或落库失败回调 (调用方决定 toast / 错误页; 不给则只记日志)
 */
class ChapterTocUpdater(
    private val scope: CoroutineScope,
    private val onUpdated: suspend (book: Book, chapters: List<BookChapter>) -> Unit,
    private val onError: ((Throwable) -> Unit)? = null,
) {
    private val lock = SynchronizedObject()

    /** 目录拉取是否在途: 单飞标志, force 也不会并发发多份请求。 */
    private var inFlight = false

    /**
     * 检查目录是否有新章。
     *
     * @param force 跳过 canUpdate / 剩余章数 / 节流三重守卫 (「目录里查不到这一章」时用)
     */
    fun upToc(
        book: Book,
        bookSource: BookSource?,
        chapterSize: Int,
        durChapterIndex: Int,
        force: Boolean = false,
    ) {
        bookSource ?: return
        // 单飞标志的「判定-置位」与节流的「读-改」同在一把锁内; 协程在锁外启动。
        // force 只跳过三重守卫, 不跳单飞 —— 一轮预下载里连续缺 N 章会调 N 次
        // upToc(true), 无单飞就并发发 N 份目录请求。
        val oldBook = synchronized(lock) {
            if (inFlight) return
            if (!force) {
                if (!book.canUpdate) return
                if (chapterSize - durChapterIndex - 1 >= UP_TOC_REMAIN_THRESHOLD) return
                if (systemCurrentTimeMillis() - book.lastCheckTime < UP_TOC_THROTTLE_MS) return
            }
            book.lastCheckTime = systemCurrentTimeMillis()
            inFlight = true
            book.copy()
        }
        scope.launch {
            try {
                runCatching {
                    val chapters = WebBook.getChapterListAwait(bookSource, book).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (chapters.size <= chapterSize) return@runCatching
                    val bookDao = AppDbProviders.get().bookDao
                    if (oldBook.bookUrl == book.bookUrl) {
                        // 只 PATCH 目录相关列: book 是联网前拍的快照, 整行 update 会把期间
                        // 阅读/播放界面 PATCH 进去的进度字段冲回旧值 (旧 ReadBookShared.upToc 同口径)
                        bookDao.updateTocInfo(
                            book.bookUrl,
                            book.totalChapterNum,
                            book.lastCheckTime,
                            book.lastCheckCount,
                            book.latestChapterTitle,
                            book.latestChapterTime,
                            book.durChapterTitle,
                        )
                    } else {
                        bookDao.replace(oldBook, book)
                        BookStorageProviders.get().updateCacheFolder(oldBook, book)
                    }
                    // 未入架书目录不落库 (对齐原版漫画; 原版文字无此守卫, 会给未入架书写下孤儿章节行)
                    if (!oldBook.isNotShelf) {
                        val chapterDao = AppDbProviders.get().bookChapterDao
                        chapterDao.delByBook(oldBook.bookUrl)
                        chapterDao.insert(*chapters.toTypedArray())
                    }
                    onUpdated(book, chapters)
                }.onFailure {
                    if (it is CancellationException) throw it
                    currentCoroutineContext().ensureActive()
                    AppLog.put("目录更新失败\n${it.message}", it)
                    onError?.invoke(it)
                }
            } finally {
                // 取消路径也要清: 不清则本实例此后永不再拉目录
                synchronized(lock) { inFlight = false }
            }
        }
    }
}
