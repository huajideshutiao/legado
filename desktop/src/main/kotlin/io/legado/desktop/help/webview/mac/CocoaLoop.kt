package io.legado.desktop.help.webview.mac

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/**
 * Cocoa 主线程投递: AWT 应用的 EDT 即 AppKit 主线程 (AWT 启动时初始化 NSApplication),
 * 所有 WKWebView/NSWindow 操作必须经这里投递到 EDT, 与 Compose/AWT 零冲突。
 *
 * 每个任务外包 @autoreleasepool (JNA 调用无系统 pool, autoreleased 对象会累积泄漏)。
 */
internal object CocoaLoop {

    /** 投递任务到主线程 (任意线程可调)。 */
    fun post(block: () -> Unit) {
        SwingUtilities.invokeLater {
            ObjC.withAutoreleasePool {
                runCatching { block() }
                    .onFailure { AppLog.put("Cocoa 主线程任务异常", it) }
            }
        }
    }

    /** 在主线程执行 [block] 并取回结果 (suspend 版)。 */
    suspend fun <T> await(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return ObjC.withAutoreleasePool { block() }
        val future = CompletableFuture<T>()
        SwingUtilities.invokeLater {
            ObjC.withAutoreleasePool {
                try {
                    future.complete(block())
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
        }
        return withTimeout(AppConst.timeLimit) { future.await() }
    }
}
