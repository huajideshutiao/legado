package io.legado.app.help.coroutine

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.model.analyzeRule.ConcurrentRecord
import io.legado.app.utils.platformSleep
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * 书源并发访问限流器。原 app 侧实现整体下沉 shared jvmAndAndroidMain 后再下沉 commonMain
 * (零行为变化)。
 *
 * - 按 [source.concurrentRate] 配置限流: 单纯"毫秒间隔"或"次数/毫秒"双模式。
 * - [concurrentRecordMap] 按 [BaseSource.getKey] 隔离每个源的限流状态。
 * - suspend 路径走 [delay], 阻塞路径 (JsExtensions.get/head/post 非 suspend 调用) 走 [platformSleep]
 *   (JVM/Android actual 委托 Thread.sleep, commonMain 无 Thread.sleep 故抽出 expect/actual)。
 *
 * 依赖面: BaseSource (shared commonMain) + ConcurrentException (shared commonMain)
 * + ConcurrentRecord (shared commonMain, 原 AnalyzeUrl 内部类抽出)
 * + platformSleep (shared commonMain expect, JVM actual = Thread.sleep)。
 */
class ConcurrentRateLimiter(val source: BaseSource?) {

    companion object {
        private val concurrentRecordMap = hashMapOf<String, ConcurrentRecord>()
        // 修复 Native 端 synchronized 传入非 SynchronizedObject 问题 (atomicfu 要求)
        private val mapLock = SynchronizedObject()
    }

    /**
     * 开始访问,并发判断
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        source ?: return null
        val concurrentRate = source.concurrentRate
        if (concurrentRate.isNullOrEmpty() || concurrentRate == "0") {
            return null
        }
        val rateIndex = concurrentRate.indexOf("/")
        var fetchRecord = concurrentRecordMap[source.getKey()]
        if (fetchRecord == null) {
            synchronized(mapLock) {
                fetchRecord = concurrentRecordMap[source.getKey()]
                if (fetchRecord == null) {
                    fetchRecord = ConcurrentRecord(rateIndex > 0, Clock.System.now().toEpochMilliseconds(), 1)
                    concurrentRecordMap[source.getKey()] = fetchRecord
                    return fetchRecord
                }
            }
        }
        val waitTime: Int = synchronized(fetchRecord!!.lock) {
            try {
                if (!fetchRecord.isConcurrent) {
                    //并发控制非 次数/毫秒
                    if (fetchRecord.frequency > 0) {
                        //已经有访问线程,直接等待
                        return@synchronized concurrentRate.toInt()
                    }
                    //没有线程访问,判断还剩多少时间可以访问
                    val nextTime = fetchRecord.time + concurrentRate.toInt()
                    if (Clock.System.now().toEpochMilliseconds() >= nextTime) {
                        fetchRecord.time = Clock.System.now().toEpochMilliseconds()
                        fetchRecord.frequency = 1
                        return@synchronized 0
                    }
                    return@synchronized (nextTime - Clock.System.now().toEpochMilliseconds()).toInt()
                } else {
                    //并发控制为 次数/毫秒
                    val sj = concurrentRate.substring(rateIndex + 1)
                    val nextTime = fetchRecord.time + sj.toInt()
                    if (Clock.System.now().toEpochMilliseconds() >= nextTime) {
                        //已经过了限制时间,重置开始时间
                        fetchRecord.time = Clock.System.now().toEpochMilliseconds()
                        fetchRecord.frequency = 1
                        return@synchronized 0
                    }
                    val cs = concurrentRate.take(rateIndex)
                    if (fetchRecord.frequency > cs.toInt()) {
                        return@synchronized (nextTime - Clock.System.now().toEpochMilliseconds()).toInt()
                    } else {
                        fetchRecord.frequency += 1
                        return@synchronized 0
                    }
                }
            } catch (_: Exception) {
                return@synchronized 0
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime
            )
        }
        return fetchRecord
    }

    /**
     * 访问结束
     */
    fun fetchEnd(concurrentRecord: ConcurrentRecord?) {
        if (concurrentRecord != null && !concurrentRecord.isConcurrent) {
            synchronized(concurrentRecord.lock) {
                concurrentRecord.frequency -= 1
            }
        }
    }

    /**
     * 获取并发记录，若处于并发限制状态下则会等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime.toLong())
            }
        }
    }

    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                // 由非 suspend 的 JsExtensions.get/head/post 调用, 无 suspend 语境, 保留阻塞语义
                // JVM/Android actual 委托 Thread.sleep
                platformSleep(e.waitTime.toLong())
            }
        }
    }

    suspend inline fun <T> withLimit(block: () -> T): T {
        val concurrentRecord = getConcurrentRecord()
        try {
            return block()
        } finally {
            fetchEnd(concurrentRecord)
        }
    }

    inline fun <T> withLimitBlocking(block: () -> T): T {
        val concurrentRecord = getConcurrentRecordBlocking()
        try {
            return block()
        } finally {
            fetchEnd(concurrentRecord)
        }
    }

}
