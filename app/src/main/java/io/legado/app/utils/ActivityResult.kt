package io.legado.app.utils

import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

fun <I, O> AppCompatActivity.registerForActivityResult(contract: ActivityResultContract<I, O>): ActivityResultLauncherAwait<I, O> {
    // 队列: 并发 launch 各自入队 (FIFO), 结果到达时取队首 resume;
    // 单个 lateinit continuation 会被并发 launch 覆盖 → 前一个协程永久挂起
    val continuations = ArrayDeque<CancellableContinuation<O>>()
    val launcher = registerForActivityResult(contract) { result ->
        // 取出时机: 每次结果回调到达时取队首 (与 launch 顺序一一对应);
        // 结果先于 launch 到达 (进程重建重投) 时队列为空, 丢弃不崩
        val continuation = continuations.removeFirstOrNull()
        if (continuation != null && continuation.isActive) {
            continuation.resume(result)
        }
    }
    return object : ActivityResultLauncherAwait<I, O>() {
        override suspend fun launch(input: I, options: ActivityOptionsCompat?): O {
            return suspendCancellableCoroutine { continuation ->
                // 取消时移出队列, 避免死协程占据队首吞掉后续结果
                continuation.invokeOnCancellation { continuations.remove(continuation) }
                continuations.addLast(continuation)
                launcher.launch(input, options)
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
