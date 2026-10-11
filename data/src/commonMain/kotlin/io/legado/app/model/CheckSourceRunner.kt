package io.legado.app.model

import io.legado.app.constant.AppConst
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.closeIfCloseable
import io.legado.app.help.coroutine.newFixedThreadPoolDispatcher
import io.legado.app.utils.onEachParallel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 书源校验并发编排 (commonMain, 四端共用)。
 *
 * 逐 id 取书源 flow 发射 → [onEachParallel] 限流并发跑 [CheckSourceShared.checkSource] →
 * 逐项完成计数并回调进度 → 校验完更新书源 → 完成回调。
 *
 * 对照原版 CheckSourceService.check 的 flow 编排; 进度呈现 (通知/任务栏/事件) 由各端注入
 * [onStart] (首帧) / [onProgress] (逐项) / [onComplete]。
 *
 * 并发与调度口径四端一份: 校验是网络阻塞调用 (搜索/详情/目录/正文探测), 取源、校验、落库与
 * [onStart]/[onProgress]/[onComplete] 三处回调都在本对象本轮的固定线程池上执行 (jvmAndAndroidMain
 * = 真实线程池, nativeMain = IO 限流视图, 见 [newFixedThreadPoolDispatcher]), 并行度 = 用户
 * threadCount 钳到 [AppConst.MAX_THREAD] (与 CacheBookShared 同一口径); 池随本轮建立并在结束
 * (含取消) 时关闭, 调用方无需管理线程池。
 */
object CheckSourceRunner {

    /** 校验并行度 (与 CacheBookShared 同一口径: 用户配置钳到 [AppConst.MAX_THREAD])。 */
    private val checkThreadCount: Int
        get() = min(AppConfigProviders.get().threadCount, AppConst.MAX_THREAD)

    /**
     * 跑一轮校验, 挂起至全部完成或协程被取消 (取消时 [onComplete] 仍会执行)。
     *
     * @param ids 待校验书源 url 列表
     * @param onStart 首帧回调 (总数; 完成数恒为 0): 尚无任何书源完成, 与"书源名为空"是两回事
     * @param onProgress 逐项完成回调 (书源名, 已完成数, 总数); 书源名可为空串
     * @param onComplete 全部结束回调 (正常完成与取消都会到达)
     */
    suspend fun run(
        ids: List<String>,
        onStart: (total: Int) -> Unit,
        onProgress: (name: String, finishCount: Int, total: Int) -> Unit,
        onComplete: () -> Unit
    ) {
        val appDb = AppDbProviders.get()
        var finishCount = 0
        val checkPool = newFixedThreadPoolDispatcher(checkThreadCount)
        try {
            withContext(checkPool) {
                flow<BookSource> {
                    for (origin in ids) {
                        appDb.bookSourceDao.getBookSource(origin)?.let { emit(it) }
                    }
                }.onStart {
                    onStart(ids.size)
                }.onEachParallel(checkThreadCount) {
                    CheckSourceShared.checkSource(it)
                }.onEach { source ->
                    finishCount++
                    onProgress(source.bookSourceName, finishCount, ids.size)
                    appDb.bookSourceDao.update(source)
                }.onCompletion {
                    onComplete()
                }.collect()
            }
        } finally {
            checkPool.closeIfCloseable()
        }
    }
}
