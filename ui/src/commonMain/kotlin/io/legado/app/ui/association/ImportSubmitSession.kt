package io.legado.app.ui.association

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次导入提交的共享门与状态 (七个导入 VM 共用)。
 *
 * 只负责“同一批不可重入、真实提交终态不可倒退、取消未启动时收口为失败”；
 * 具体待写实体/分组/keep 选项仍由各 VM 在自己的 FrozenSubmit 里冻结。
 */
class ImportSubmitSession {
    private val submitting = atomic(false)
    private val states = ImportSubmitStateHolder()

    val state: StateFlow<ImportSubmitState> = states.state

    /** 已提交批次不允许再入库; 同一时刻只允许一个写入。成功后进入 Writing。 */
    fun begin(): Boolean {
        if (states.state.value is ImportSubmitState.Committed) return false
        if (!submitting.compareAndSet(false, true)) return false
        states.writing()
        return true
    }

    /** 唯一结算: 写入协程完成/取消/从未启动都走这里; 已 Committed 不会被改成失败。 */
    fun end() {
        submitting.value = false
        val cur = states.state.value
        if (cur is ImportSubmitState.Writing) {
            states.record(ImportSubmitResult.Failed("导入已取消"))
        } else if (cur is ImportSubmitState.Committed && cur.cacheRunning && !cur.cacheRetry) {
            // 提交已成功但缓存维护没有真实完成: 终结运行态, 给出可重试的“未完成”
            states.interruptImportCache("缓存维护未完成（导入已提交），可重试缓存维护")
        }
    }

    fun record(result: ImportSubmitResult) = states.record(result)

    fun committed(count: Int, warning: String? = null, cacheRunning: Boolean = false) =
        states.committed(count, warning, cacheRunning)

    fun cacheDone(count: Int) = states.cacheDone(count)

    fun cacheFailed(count: Int, warning: String) = states.cacheFailed(count, warning)

    fun tryStartCacheRetry(): Long? = states.tryStartCacheRetry()

    fun finishCacheRetry(attempt: Long, warning: String?): Boolean = states.finishCacheRetry(attempt, warning)

    fun interruptImportCache(warning: String): Boolean = states.interruptImportCache(warning)
}
