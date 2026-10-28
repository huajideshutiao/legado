package io.legado.app.ui.association

import kotlinx.atomicfu.atomic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一次导入提交的统一终态。
 *
 * - [Committed] 数据已写入: [cacheWarning] 非空只表示提交后的缓存维护失败,
 *   不能再按“未提交”重试或保留可点无效的确认。
 * - [Failed] 数据未提交: 可修正后重试, 文案归 UI 展示。
 */
sealed interface ImportSubmitResult {
    data class Committed(
        val cacheWarning: String? = null,
        /** 本次真实写入的条目数 (提交点先记录, 取消返回路径也保留)。 */
        val count: Int? = null,
    ) : ImportSubmitResult
    data class Failed(val message: String) : ImportSubmitResult
}

/** 唯一提交入口: 区分真实成功(含缓存告警)、失败与取消; 取消原样上抛。 */
suspend fun runImportSubmit(block: suspend () -> Unit): ImportSubmitResult = try {
    block()
    ImportSubmitResult.Committed()
} catch (e: CancellationException) {
    throw e
} catch (e: ImportPostCommitException) {
    ImportSubmitResult.Committed(e.message)
} catch (e: Throwable) {
    ImportSubmitResult.Failed(e.message ?: e.toString())
}


/** 一次导入在会话内的提交态 (归 VM 持有, 重建对话框后仍可读)。 */
sealed interface ImportSubmitState {
    data object Idle : ImportSubmitState
    data object Writing : ImportSubmitState
    data class Committed(
        val warning: String?,
        val count: Int? = null,
        /** 已提交但缓存维护仍在运行: 不得当作失败/不得开放第二个重试。 */
        val cacheRunning: Boolean = false,
        /** 运行中的缓存维护是否来自 cache-only 重试 (旧导入的 end/record 不得结束它)。 */
        val cacheRetry: Boolean = false,
        /** 本次 cache-only 重试的身份; 旧重试 Job 不能结束新重试。 */
        val cacheAttempt: Long = 0,
    ) : ImportSubmitState
    data class Failed(val message: String) : ImportSubmitState
}

/** 7 个导入 VM 共用的提交态容器。 */
class ImportSubmitStateHolder {
    private val _state = MutableStateFlow<ImportSubmitState>(ImportSubmitState.Idle)
    // 重试身份单调递增, 不随显示/告警状态清零; 旧 Job 的 attempt 永不复用
    private val cacheAttemptSeq = atomic(0L)
    val state: StateFlow<ImportSubmitState> = _state.asStateFlow()

    fun writing() {
        _state.update { cur -> if (cur is ImportSubmitState.Committed) cur else ImportSubmitState.Writing }
    }

    /**
     * 真实提交点的记录 (在 withContext(IO) 内、返回 Main 之前): 即使返回途中被取消,
     * 结果仍是 Committed, 不能由 endSubmit 误判为失败。只允许更新告警, 不回退数量。
     */
    fun committed(count: Int, warning: String? = null, cacheRunning: Boolean = false) {
        _state.update { cur ->
            if (cur is ImportSubmitState.Committed) {
                cur.copy(warning = warning, cacheRunning = cacheRunning, cacheRetry = false, cacheAttempt = 0)
            } else {
                ImportSubmitState.Committed(warning, count, cacheRunning, false, 0)
            }
        }
    }

    /** 缓存维护真实完成: 清告警并结束运行态。 */
    fun cacheDone(count: Int) {
        _state.update { cur ->
            if (cur is ImportSubmitState.Committed && !cur.cacheRetry) {
                cur.copy(warning = null, count = count, cacheRunning = false, cacheRetry = false)
            } else cur
        }
    }

    /** 缓存维护真实失败/被取消: 结束运行态并保留可重试原因。 */
    fun cacheFailed(count: Int, warning: String) {
        _state.update { cur ->
            if (cur is ImportSubmitState.Committed && !cur.cacheRetry) {
                cur.copy(warning = warning, count = count, cacheRunning = false, cacheRetry = false)
            } else cur
        }
    }

    /** 只有真实可重试的已提交状态才能被 CAS 接纳; 返回本次重试身份, null=未接纳。 */
    fun tryStartCacheRetry(): Long? {
        while (true) {
            val cur = _state.value
            if (cur !is ImportSubmitState.Committed || cur.cacheRunning || cur.warning == null) return null
            val attempt = cacheAttemptSeq.incrementAndGet()
            val next = cur.copy(warning = null, cacheRunning = true, cacheRetry = true, cacheAttempt = attempt)
            if (_state.compareAndSet(cur, next)) return attempt
        }
    }

    /** 只结束本次身份的重试; 旧重试 Job 的迟到结果不能结束新重试。 */
    fun finishCacheRetry(attempt: Long, warning: String?): Boolean {
        while (true) {
            val cur = _state.value
            if (cur !is ImportSubmitState.Committed || !cur.cacheRetry || cur.cacheAttempt != attempt) return false
            val next = cur.copy(warning = warning, cacheRunning = false, cacheRetry = false)
            if (_state.compareAndSet(cur, next)) return true
        }
    }

    /** 导入自身的缓存维护被取消: 原子检查它不是 retry 后才结束。 */
    fun interruptImportCache(warning: String): Boolean {
        while (true) {
            val cur = _state.value
            if (cur !is ImportSubmitState.Committed || !cur.cacheRunning || cur.cacheRetry) return false
            val next = cur.copy(warning = warning, cacheRunning = false)
            if (_state.compareAndSet(cur, next)) return true
        }
    }

    fun record(result: ImportSubmitResult) {
        when (result) {
            is ImportSubmitResult.Committed -> _state.update { cur ->
                if (cur is ImportSubmitState.Committed && cur.cacheRunning && cur.cacheRetry) {
                    // 旧导入结果不得结束正在进行的 cache-only 重试
                    cur.copy(count = result.count ?: cur.count)
                } else {
                    val count = result.count ?: (cur as? ImportSubmitState.Committed)?.count ?: 0
                    ImportSubmitState.Committed(
                        result.cacheWarning, count, cacheRunning = false, cacheRetry = false, cacheAttempt = 0,
                    )
                }
            }
            is ImportSubmitResult.Failed -> _state.update { cur ->
                if (cur is ImportSubmitState.Committed) cur else ImportSubmitState.Failed(result.message)
            }
        }
    }
}
