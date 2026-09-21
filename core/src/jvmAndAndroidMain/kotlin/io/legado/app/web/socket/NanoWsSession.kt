package io.legado.app.web.socket

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.web.api.WsHandler
import io.legado.app.web.api.WsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * nanohttpd(NanoWSD) WebSocket 薄适配: 把原生帧/关闭事件转发给平台无关的 [WsHandler]，
 * 并把自身作为 [WsSession] 供 handler 回推。30s ping 心跳留在本壳。
 *
 * @param handlerFactory 由 session (this) 创建 handler; 收发解耦到 [io.legado.app.web.api]。
 *
 * # 下沉说明 (原 app 端 io.legado.app.web.socket.NanoWsSession)
 * 零 android 依赖, 逐字等价下沉到 jvmAndAndroidMain (Android + 桌面 JVM 共用)。
 */
class NanoWsSession(
    handshakeRequest: NanoHTTPD.IHTTPSession,
    handlerFactory: (WsSession) -> WsHandler,
) : NanoWSD.WebSocket(handshakeRequest),
    WsSession,
    CoroutineScope by MainScope() {

    private val handler: WsHandler = handlerFactory(this)

    override fun send(text: String) {
        // NanoWSD.WebSocket.send(String) 可能抛 IOException
        super.send(text)
    }

    override fun close(reason: String) {
        close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, reason, false)
    }

    override fun onOpen() {
        launch(IO) {
            kotlin.runCatching {
                while (isOpen) {
                    ping("ping".toByteArray())
                    delay(30000)
                }
            }
        }
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode,
        reason: String,
        initiatedByRemote: Boolean
    ) {
        cancel()
        handler.onClose()
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        handler.onMessage(message.textPayload)
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {

    }

    override fun onException(exception: IOException) {
        handler.onException()
    }
}
