package io.legado.app.model.chapter

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 章节装载的并发守卫 + 任务表。
 *
 * 收敛原先四份实现: `ReadBookViewModelShared`(守卫 + 任务表)、`ReadBookShared`、
 * `MangaReaderViewModelShared`、`AudioPlayManager` 的 `addLoading/removeLoading`。
 * 视频侧原用 `loadChapterToken` 轮次令牌另成一套, 也改走本类 (令牌语义 =
 * 「同章新任务替换旧任务」的特例)。
 *
 * # 能力
 * - [tryAdd] / [release]: 同章不并发装载 (原版 `addLoading` / `removeLoading`)。
 * - [launch]: 以 index 记账启动装载任务, **同章新任务取消并替换旧任务**。
 * - [launchIfIdle]: 抢到装载权才启动, 该章已在装载则放弃本次调用。
 * - [cancelOutside]: 切章后取消三章窗口外的在途任务。
 *
 * # 两种启动语义
 * - **[launch] (装载全程在任务内)**: 文字 / 视频 —— 正文获取是 suspend, 在任务内 await
 *   完成。任务被替换即旧装载真死, 故装载标记的生命周期与任务一致。
 * - **[launchIfIdle] (装载跨出任务)**: 漫画 —— 任务只负责启动 fire-and-forget 下载
 *   (跑在独立 downloadScope 上, 不随任务取消), 标记必须活到下载回调的
 *   `contentLoadFinish`。此时**不能**做同章替换: 取消一个已把活交出去的任务毫无意义,
 *   却会让新任务被旧任务留下的标记挡住而空跑, 最终谁都不再释放, 永久堵死该章。
 *
 * # 锁纪律
 * 锁内只做纯内存的容器读写, `cancel()` / `launch()` 一律出锁执行 ——
 * atomicfu 的锁在 Native 端不可重入, 锁内 cancel 会同线程直跑 `invokeOnCompletion`
 * 处理器, 而处理器要取同一把锁, 即死锁。
 *
 * @param scope 装载任务宿主作用域
 * @param onExpired 任务被取消/清理时的附带清理 (如段评 Deferred), 参数为受影响的 index
 */
class ChapterLoadingGuard(
    private val scope: CoroutineScope,
    private val onExpired: ((indices: List<Int>, clearAll: Boolean) -> Unit)? = null,
) {
    private val lock = SynchronizedObject()
    private val loadingChapters = mutableSetOf<Int>()
    private val jobs = mutableMapOf<Int, Job>()

    /**
     * 标记「本协程是 [launch] 启动的第 index 章装载任务」。
     *
     * [release] 靠它区分两类调用方: 装载任务本体 (要做身份校验, 防被替换的旧任务
     * 清掉新任务的标记) 与下载回调等外部协程 (无身份可比, 直接释放)。
     */
    private class GuardedChapter(val index: Int) :
        AbstractCoroutineContextElement(GuardedChapter) {
        companion object Key : CoroutineContext.Key<GuardedChapter>
    }

    /**
     * 抢占该章的装载权; 已在装载中返回 false (原版 `addLoading`)。
     */
    fun tryAdd(index: Int): Boolean = synchronized(lock) {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        true
    }

    /**
     * [job] 是否仍是第 [index] 章当前登记的装载任务。
     *
     * 供装载任务在异常处理与 finally 里判定“我这一轮是否已被替换”: 连点切章时旧一轮被取消,
     * 它的异常回调与收尾不得把新一轮刚置的加载态打回去 (轮次令牌的等价判定)。
     */
    fun isCurrentJob(index: Int, job: Job?): Boolean =
        synchronized(lock) { job != null && jobs[index] === job }

    /**
     * 释放装载标记。
     *
     * 由装载任务自己调用时做身份校验: 被同章新任务替换掉的旧任务, 其 finally 会晚于
     * 新任务的 [tryAdd] 执行, 无校验就会清掉新任务刚设的标记, 让后续调用越过互斥重复装载。
     * 下载回调等外部协程 (不带 [GuardedChapter] 标记) 直接释放 —— 它们本就是「装载真正
     * 结束」的信号源 (原版 removeLoading 只在 contentLoadFinish 入口)。
     */
    suspend fun release(index: Int) {
        val context = currentCoroutineContext()
        val tag = context[GuardedChapter]
        val job = context[Job]
        synchronized(lock) {
            if (tag != null && tag.index == index) {
                val owner = jobs[index]
                if (owner != null && job != null && owner !== job) return@synchronized
            }
            loadingChapters.remove(index)
        }
    }

    /** 无协程上下文时的强制释放 (非 suspend 调用点, 如失败早退分支)。 */
    fun releaseNow(index: Int) {
        synchronized(lock) { loadingChapters.remove(index) }
    }

    /**
     * 以 [index] 记账启动装载任务; **同章旧任务取消并被替换** (文字 / 视频)。
     *
     * 装载标记的生命周期与任务一致: 登记时清掉上一轮的标记, 任务收尾时清掉自己的。
     */
    fun launch(index: Int, block: suspend CoroutineScope.() -> Unit): Job {
        // 先建 LAZY 任务并登记, 再取消旧任务, 最后启动: 旧任务的 finally 无论何时跑,
        // 看到的 jobs[index] 都已是新任务, [release] 的身份校验就能拦住它 —— 先 cancel
        // 后登记会留一个窗口: 新任务已 tryAdd 而旧任务的 finally 看到空表, 把新标记清掉。
        val job = scope.launch(GuardedChapter(index), CoroutineStart.LAZY, block)
        val expired = synchronized(lock) {
            loadingChapters.remove(index)
            jobs.put(index, job)
        }
        // cancel 出锁再做, 见类注释的锁纪律
        expired?.cancel()
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[index] === job) {
                    jobs.remove(index)
                    loadingChapters.remove(index)
                }
            }
        }
        job.start()
        return job
    }

    /**
     * 抢到装载权才启动任务; 该章已在装载中则不启动并返回 null (漫画)。
     *
     * 不做同章替换, 与原版漫画一致 (`addLoading` 失败即放弃本次调用)。
     * 标记的释放归调用方: 交接给下载则由下载回调释放, 未交接 (早退 / 抛错 / 取消)
     * 则由调用方 finally 释放 —— 本方法不能代它清: 任务正常结束时下载才刚起步。
     */
    fun launchIfIdle(index: Int, block: suspend CoroutineScope.() -> Unit): Job? {
        // 先建 LAZY 任务, 使「抢标记 + 登记任务表」在同一把锁内完成: 分两次取锁会让
        // 切书的 [clear] 从中间插进来, 本任务既逃过取消又占着标记。
        val job = scope.launch(GuardedChapter(index), CoroutineStart.LAZY, block)
        val acquired = synchronized(lock) {
            if (loadingChapters.contains(index)) {
                false
            } else {
                loadingChapters.add(index)
                jobs[index] = job
                true
            }
        }
        if (!acquired) {
            // LAZY 任务未 start, cancel 只是丢弃壳子, block 不会执行
            job.cancel()
            return null
        }
        // 只摘任务表, 不动标记 (标记比本任务活得长, 见 KDoc)
        job.invokeOnCompletion {
            synchronized(lock) { if (jobs[index] === job) jobs.remove(index) }
        }
        job.start()
        return job
    }

    /**
     * 取消 [center] 三章窗口外的在途任务; [clearAll] = true 时全部取消 (切书 / 销毁)。
     *
     * 一并清掉这些章的装载标记: 任务已死, 标记留着会永久堵住该章的重新装载。
     */
    fun cancelOutside(center: Int, clearAll: Boolean = false) {
        val expiredJobs = arrayListOf<Job>()
        val expiredIndices = arrayListOf<Int>()
        synchronized(lock) {
            val iterator = jobs.iterator()
            while (iterator.hasNext()) {
                val (index, job) = iterator.next()
                if (clearAll || !isInChapterWindow(index, center)) {
                    expiredJobs.add(job)
                    expiredIndices.add(index)
                    iterator.remove()
                    loadingChapters.remove(index)
                }
            }
            if (clearAll) loadingChapters.clear()
        }
        for (i in expiredJobs.indices) expiredJobs[i].cancel()
        onExpired?.invoke(expiredIndices, clearAll)
    }

    /** 清空所有标记与任务 (切书)。 */
    fun clear() {
        cancelOutside(center = 0, clearAll = true)
    }
}
