package io.legado.app.model.analyzeRule

import kotlinx.atomicfu.locks.SynchronizedObject

/**
 * 并发访问记录。原为 [AnalyzeUrl] 内部 data class, 抽出独立下沉 shared commonMain
 * 以解除 ConcurrentRateLimiter 对 AnalyzeUrl 的反向依赖 (AnalyzeUrl 仍留 app)。
 *
 * 字段语义:
 * - [isConcurrent]: 是否按"次数/毫秒"模式限流 (true) vs 单纯"毫秒间隔"模式 (false)
 * - [time]: 当前窗口开始访问时间 (epoch millis)
 * - [frequency]: 当前窗口正在访问的线程数
 */
data class ConcurrentRecord(
    /** 是否按频率 */
    val isConcurrent: Boolean,
    /** 开始访问时间 */
    var time: Long,
    /** 正在访问的个数 */
    var frequency: Int
) {
    // 限流同步锁; 不参与 data class equals/hashCode/copy
    internal val lock = SynchronizedObject()
}
