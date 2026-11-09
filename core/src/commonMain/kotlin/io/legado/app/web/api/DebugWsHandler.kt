package io.legado.app.web.api

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * web 端书源调试的平台无关处理器 (零 android import)。
 *
 * 出站经注入的 [WsSession]; 平台壳负责帧解码与 30s ping。
 * @param cannotEmptyMsg 由平台壳注入的「不能为空」本地化文案 (原 R.string.cannot_empty)
 *
 * 下沉 commonMain: 依赖 (appDb/Debug/GSON/printStackTraceOnDebug) 均已下沉, 原 app 端 runOnIO
 * (CoroutineScope 扩展, app 专属) 等价替换为 launch(IO) (本类即 CoroutineScope, 语义一致)。
 */
class DebugWsHandler(
    private val session: WsSession,
    private val cannotEmptyMsg: String,
) : WsHandler, Debug.Callback, CoroutineScope by MainScope() {

    private data class HandlerSession(
        val debugRequestId: Long,
        val messageId: Long,
    )

    private val notPrintState = arrayOf(10, 20, 30, 40, 50)

    /** handler 状态提交域: 消息序号、当前会话快照、关闭受理一起线性化。 */
    private val stateLock = SynchronizedObject()

    /** 供 Debug 回调无锁读取的消息序号 (实际推进在 stateLock 内)。 */
    private val messageSerial = atomic(0L)

    /** Debug 回调只读这份不可变快照, 不反向获取 handler 锁。 */
    private val sessionSnapshot = atomic<HandlerSession?>(null)

    /** 本连接已受理关闭: 新消息不再受理, 旧终态不能再关闭新连接。 */
    private val closeAccepted = atomic(false)

    override fun onMessage(text: String) {
        // 同步入口在同一提交域分配消息序号和 Debug token; launch 在其后。
        var pending: HandlerSession? = null
        synchronized(stateLock) {
            if (closeAccepted.value) return
            val messageId = messageSerial.incrementAndGet()
            val requestId = Debug.newDebugRequestId()
            pending = HandlerSession(requestId, messageId)
        }
        val request = pending ?: return

        launch(IoDispatcher) {
            if (closeAccepted.value || messageSerial.value != request.messageId) return@launch
            kotlin.runCatching {
                if (!text.isJson()) {
                    closeMalformed(request.messageId, "数据必须为Json格式")
                    return@launch
                }
                val debugBean = GSON.fromJsonObject<Map<String, String>>(text).getOrNull()
                if (debugBean == null) {
                    closeMalformed(request.messageId, "数据必须为Json格式")
                    return@launch
                }
                val tag = debugBean["tag"]
                val key = debugBean["key"]
                if (tag.isNullOrBlank() || key.isNullOrBlank()) {
                    closeMalformed(request.messageId, cannotEmptyMsg)
                    return@launch
                }

                val source = AppDbProviders.get().bookSourceDao.getBookSource(tag)
                // DAO 返回后、启动前核验当前连接与消息世代; 锁外不持网络。
                if (closeAccepted.value || messageSerial.value != request.messageId) return@runCatching
                if (source == null) {
                    closeMalformed(request.messageId, "未找到书源")
                    return@launch
                }

                // “仍最新→发布本次回顾→Debug.startDebug”在同一提交域。
                synchronized(stateLock) {
                    if (closeAccepted.value || messageSerial.value != request.messageId) {
                        return@synchronized
                    }
                    sessionSnapshot.value = request
                    Debug.startDebug(
                        this,
                        source,
                        key,
                        requestId = request.debugRequestId,
                        callback = this@DebugWsHandler,
                    )
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.put("调试WS消息处理失败: ${e.message}", e)
                closeAfterFailure(request.messageId)
            }
        }
    }

    /** JSON/空字段路径: 受理与自身 token 分开; 无 token 也照样 send/close。 */
    private fun closeMalformed(messageId: Long, text: String) {
        if (!acceptMessageClose(messageId)) return
        // 关闭已 sealed, 快照不会再变; 无已启动 token 时只是 0, 不影响 send/close。
        val ownId = sessionSnapshot.value?.debugRequestId
        try {
            try {
                session.send(text)
            } catch (e: Throwable) {
                reportTransportFailure(e)
            }
        } finally {
            try {
                session.close("调试结束")
            } catch (e: Throwable) {
                reportTransportFailure(e)
            } finally {
                finishConnection(ownId)
            }
        }
    }

    /** 关闭受理: 只表示本连接是否受理, 自身 token 由 sealed 快照另行读取。 */
    private fun acceptMessageClose(messageId: Long): Boolean = synchronized(stateLock) {
        if (closeAccepted.value || messageSerial.value != messageId) {
            false
        } else {
            closeAccepted.value = true
            true
        }
    }

    /** 真实传输失败记录到 AppLog; 正常取消仍重抛, 不静默成功。 */
    private fun reportTransportFailure(e: Throwable) {
        if (e is CancellationException) throw e
        AppLog.put("调试WS连接失败: ${e.message}", e)
    }

    /** 当前消息处理失败: 必须在同锁内仍是本次消息才封口; 已 sealed 时不再动新会话。 */
    private fun closeAfterFailure(messageId: Long) {
        if (!acceptMessageClose(messageId)) return
        val ownId = sessionSnapshot.value?.debugRequestId
        try {
            session.close("调试结束")
        } catch (e: Throwable) {
            reportTransportFailure(e)
        } finally {
            finishConnection(ownId)
        }
    }

    /** 终态发送/关闭失败: 只有仍是当前会话才许可关闭; 旧终态不碰新连接。 */
    private fun closeTerminalAfterFailure(requestId: Long) {
        if (!acceptTerminalClose(requestId)) return
        try {
            session.close("调试结束")
        } catch (e: Throwable) {
            reportTransportFailure(e)
        } finally {
            finishConnection(requestId)
        }
    }

    /**
     * 连接关闭受理: “只受理一次”与“资源清理幂等”分离。
     * 即使之前已 sealed, 仍返回当前自身 snapshot token; 新消息已被拒绝, 快照不会变。
     */
    private fun acceptConnectionClose(): Long? = synchronized(stateLock) {
        closeAccepted.value = true
        sessionSnapshot.value?.debugRequestId
    }

    /** 所有关闭/异常路径共用的自身资源收尾: 只取消自身 scope 与传入的自身 token。 */
    private fun finishConnection(ownId: Long?) {
        cancel()
        if (ownId != null && ownId != 0L) Debug.cancelDebug(true, ownId)
    }

    /** 终态关闭: 必须仍是当前快照且消息未前进; 通过后锁外取消/物理关闭。 */
    private fun acceptTerminalClose(requestId: Long): Boolean = synchronized(stateLock) {
        val current = sessionSnapshot.value
        if (closeAccepted.value ||
            current?.debugRequestId != requestId ||
            current.messageId != messageSerial.value
        ) {
            false
        } else {
            closeAccepted.value = true
            true
        }
    }

    override fun onClose() {
        finishConnection(acceptConnectionClose())
    }

    override fun onException() {
        finishConnection(acceptConnectionClose())
    }

    override fun printLog(state: Int, msg: String) {
        val id = sessionSnapshot.value?.debugRequestId ?: return
        printLog(state, msg, id)
    }

    override fun printLog(state: Int, msg: String, requestId: Long) {
        if (state in notPrintState || closeAccepted.value) return
        val current = sessionSnapshot.value ?: return
        if (requestId == 0L ||
            requestId != current.debugRequestId ||
            !Debug.isActiveRequest(requestId) ||
            current.messageId != messageSerial.value
        ) {
            return
        }
        // 原 app 端 runOnIO { ... } (CoroutineScope 扩展) 等价替换为 launch(IO) { ... }
        launch(IoDispatcher) {
            if (closeAccepted.value) return@launch
            val currentNow = sessionSnapshot.value ?: return@launch
            if (!Debug.isActiveRequest(requestId) ||
                requestId != currentNow.debugRequestId ||
                currentNow.messageId != messageSerial.value
            ) {
                return@launch
            }
            try {
                session.send(msg)
                if (state == -1 || state == 1000) {
                    // 关闭意图在锁内线性化; 通过后才取消自身 token 与物理关闭。
                    if (acceptTerminalClose(requestId)) {
                        Debug.cancelDebug(true, requestId)
                        try {
                            session.close("调试结束")
                        } finally {
                            finishConnection(requestId)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                AppLog.put("调试WS日志发送失败: ${e.message}", e)
                closeTerminalAfterFailure(requestId)
            }
        }
    }
}
