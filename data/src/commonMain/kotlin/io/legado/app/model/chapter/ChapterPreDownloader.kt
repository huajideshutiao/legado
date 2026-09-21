package io.legado.app.model.chapter

import io.legado.app.data.entities.BookChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.math.min

/** 预下载并发上限 (原版 `ReadBook.preDownloadSemaphore` / `ReadMangaViewModel` 同值)。 */
private const val PRE_DOWNLOAD_CONCURRENCY = 2

/** 预下载生效的最小配置值; 低于此值只做目录检查 (原版 `if (AppConfig.preDownloadNum < 2)`)。 */
private const val PRE_DOWNLOAD_MIN_ENABLED = 2

/** 同一章连续失败达此次数后不再重试 (原版 `downloadFailChapters` 判据)。 */
private const val PRE_DOWNLOAD_MAX_FAIL = 3

/** 反向预载上限章数 (原版 `min(5, preDownloadNum)`)。 */
private const val PRE_DOWNLOAD_BACKWARD_LIMIT = 5

/** 未缓存章开下载前的让路延时 (原版 `delay(1000)`, 给当前章的网络请求腾带宽)。 */
private const val PRE_DOWNLOAD_DELAY_MS = 1000L

/**
 * 正文预下载 (文字 / 漫画共用)。
 *
 * 原版 `ReadBook.preDownload` 与 `ReadMangaViewModel.preDownload` 是逐行复制的两份
 * (仅状态访问器与 scope 不同, 已实证 30 行 diff 只差 3 行), KMP 化时连同 `downloadIndex`
 * 一起复制成三份 (含 `ReadBookShared` 旧引擎)。此处收敛为单一实现。
 *
 * 窗口口径与原版一致: 正向 `dur+2 .. dur+preDownloadNum`, 反向
 * `dur-2 downTo dur-min(5, preDownloadNum)`; `preDownloadNum < 2` 时不预下载, 只做目录检查。
 * 音视频不用本类 —— 它们的「正文」是带时效签名的播放直链, 预取多了到播时已失效,
 * 走 [io.legado.app.model.ResourceUrlPreloader] 的 ±1 直链预解析。
 *
 * @param scope 触发用作用域 (读配置 / 调 upToc)
 * @param downloadScope 下载任务作用域 (独立于 UI, 取消粒度按整批)
 * @param guard 装载守卫; 传 null 表示下载底层自带并发去重 (文字走 CacheBookShared 的
 *   onDownloadSet, 不占本守卫的标记 —— 其完成回调不经宿主, 占了无处释放会永久堵住该章)
 * @param preDownloadNum 预下载章数配置读取 (`AppConfig.preDownloadNum`)
 * @param chapterSize 目录长度
 * @param durChapterIndex 当前章序号
 * @param isLocalBook 本地书不预下载
 * @param upToc 目录检查 / 扩容; force=true 用于「目录里查不到这一章」
 * @param resolveChapter index → 章节实体 (内存优先库兜底, 见 [resolveChapter])
 * @param hasContent 本地缓存是否已有该章正文
 * @param syncFailCount 从下载底层回填失败次数 (文字: CacheBookShared.errorDownloadMap)
 * @param download 实际下载 (带 [semaphore] 限流)
 */
class ChapterPreDownloader(
    private val scope: CoroutineScope,
    private val downloadScope: CoroutineScope,
    private val guard: ChapterLoadingGuard?,
    private val preDownloadNum: () -> Int,
    private val chapterSize: () -> Int,
    private val durChapterIndex: () -> Int,
    private val isLocalBook: () -> Boolean,
    private val upToc: (force: Boolean) -> Unit,
    private val resolveChapter: suspend (index: Int) -> BookChapter?,
    private val hasContent: suspend (chapter: BookChapter) -> Boolean,
    private val syncFailCount: ((chapter: BookChapter) -> Int?)? = null,
    private val download: suspend (chapter: BookChapter, semaphore: Semaphore) -> Unit,
) {
    private val semaphore = Semaphore(PRE_DOWNLOAD_CONCURRENCY)
    private var task: Job? = null

    /** 已确认有缓存的章节 (原版 `downloadedChapters`)。 */
    val downloadedChapters = mutableSetOf<Int>()

    /** 章节失败次数 (原版 `downloadFailChapters`)。 */
    val failChapters = mutableMapOf<Int, Int>()

    /** 切书时清空记账。 */
    fun reset() {
        cancel()
        downloadedChapters.clear()
        failChapters.clear()
    }

    /** 记一次成功 (下载回调用)。 */
    fun markDownloaded(index: Int) {
        downloadedChapters.add(index)
        failChapters.remove(index)
    }

    /** 记一次失败 (下载回调用)。 */
    fun markFailed(index: Int) {
        failChapters[index] = (failChapters[index] ?: 0) + 1
    }

    /**
     * 触发一轮预下载 (原版 `preDownload`): 每次先作废上一轮。
     */
    fun preDownload() {
        if (isLocalBook()) return
        scope.launch {
            val num = preDownloadNum()
            if (num < PRE_DOWNLOAD_MIN_ENABLED) {
                upToc(false)
                return@launch
            }
            task?.cancel()
            task = downloadScope.launch {
                val durIndex = durChapterIndex()
                // 正向预下载 (dur±1 由三章窗口装载负责, 故从 +2 起)
                launch {
                    val maxIndex = min(durIndex + num, chapterSize())
                    for (i in durIndex.plus(2)..maxIndex) {
                        if (skip(i)) continue
                        downloadIndex(i)
                    }
                }
                // 反向预载 min(5, preDownloadNum) 章
                launch {
                    val minIndex = durIndex - min(PRE_DOWNLOAD_BACKWARD_LIMIT, num)
                    for (i in durIndex.minus(2) downTo minIndex) {
                        if (skip(i)) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    /**
     * 取消预下载 (原版 `cancelPreDownloadTask`)。
     *
     * 有意偏离原版: 不带原版文字模式的「当前章已装载」守卫 —— 该守卫会让「停在最后一章」
     * 或「下一章加载失败」时离开阅读页也不取消, 预下载继续跑到跑完。
     */
    fun cancel() {
        task?.cancel()
        downloadScope.coroutineContext.cancelChildren()
    }

    private fun skip(index: Int): Boolean =
        downloadedChapters.contains(index) ||
            (failChapters[index] ?: 0) >= PRE_DOWNLOAD_MAX_FAIL

    /**
     * 预下载单章 (原版 `downloadIndex`)。
     *
     * 越界 → 目录检查, 目录里查不到该章 → 强制目录检查 (原版漫画口径; 原版文字两处都静默
     * return, 是缺陷: 目录滞后时永远补不上后面的章, 不复刻)。
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize() - 1) {
            upToc(false)
            return
        }
        val chapter = resolveChapter(index) ?: run {
            upToc(true)
            return
        }
        if (hasContent(chapter)) {
            downloadedChapters.add(chapter.index)
            return
        }
        syncFailCount?.invoke(chapter)?.let { failChapters[index] = it }
        if ((failChapters[index] ?: 0) >= PRE_DOWNLOAD_MAX_FAIL) return
        delay(PRE_DOWNLOAD_DELAY_MS)
        if (guard == null || guard.tryAdd(index)) {
            download(chapter, semaphore)
        }
    }
}
