package io.legado.app.utils

import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun <I, O> AppCompatActivity.registerForActivityResult(contract: ActivityResultContract<I, O>): ActivityResultLauncherAwait<I, O> {
    // 同一 launcher 串行: 新调用排队等旧调用真实回传; 排队时取消不启动系统调用,
    // 已启动后取消不提前解锁 (占用至真实回传), 旧结果只结束旧等待
    val mutex = Mutex()
    var waiter: CancellableContinuation<O>? = null
    val launcher = registerForActivityResult(contract) { result ->
        val continuation = waiter
        waiter = null
        // 真实回传才释放通道; 无占用时到达的结果 (登记期重投) 无人可交付, 只丢弃
        if (mutex.isLocked) {
            mutex.unlock()
        }
        if (continuation != null && continuation.isActive) {
            continuation.resume(result)
        }
    }
    return object : ActivityResultLauncherAwait<I, O>() {
        override suspend fun launch(input: I, options: ActivityOptionsCompat?): O {
            mutex.lock()
            // 快取锁路径不检查取消: 取到通道后先确认本次调用仍有资格, 取消则归还再上抛
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            } catch (e: Throwable) {
                mutex.unlock()
                throw e
            }
            return suspendCancellableCoroutine { continuation ->
                waiter = continuation
                if (!continuation.isActive) {
                    waiter = null
                    mutex.unlock()
                    return@suspendCancellableCoroutine
                }
                try {
                    launcher.launch(input, options)
                } catch (e: Throwable) {
                    // launch 失败: 通道从未占用成功, 现场释放, 等待按原异常结束
                    waiter = null
                    mutex.unlock()
                    continuation.resumeWithException(e)
                }
            }
        }

        override fun unregister() {
            launcher.unregister()
        }

        override fun getContract(): ActivityResultContract<I, *> {
            return launcher.contract
        }
    }
}

abstract class ActivityResultLauncherAwait<I, O> {

    suspend fun launch(input: I): O {
        return launch(input, null)
    }

    abstract suspend fun launch(input: I, options: ActivityOptionsCompat?): O

    abstract fun unregister()

    abstract fun getContract(): ActivityResultContract<I, *>

}
