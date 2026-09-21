package io.legado.app.web

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.legado.app.web.api.DebugWsHandler
import io.legado.app.web.api.SearchWsHandler
import io.legado.app.web.api.WsHandler
import io.legado.app.web.api.WsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ktor WebSocket 薄适配: 把原生帧/关闭事件转发给平台无关的 [WsHandler]，
 * 并把自身作为 [WsSession] 供 handler 回推。30s ping 心跳留在本壳。
 *
 * 对齐 [io.legado.app.web.socket.NanoWsSession] (jvmAndAndroidMain NanoWSD 壳) 的设计:
 * - send/close 经 launch 委托到 WebSocketServerSession 的协程作用域
 *   (NanoWsSession.send 是同步阻塞写流; Ktor 的 session.send 是 suspend, 用 launch 包装)
 * - 协程作用域委托给 WebSocketServerSession (session 关闭时自动取消所有子协程)
 *
 * # 帧排序保证
 * Ktor WebSocketSession 内部用单通道 outgoing, send 按入队顺序发送;
 * handler 调 send(msg) 后 close(reason), 两个 launch 的帧按调度顺序入队, 实际发送有序。
 */
class KtorWsSession(
    private val session: WebSocketServerSession,
) : WsSession, CoroutineScope by session {

    override fun send(text: String) {
        launch { session.send(Frame.Text(text)) }
    }

    override fun close(reason: String) {
        launch { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
    }
}

/**
 * Ktor WebSocket routing 配置: handshake.uri 分派到 [DebugWsHandler] / [SearchWsHandler]。
 *
 * 对齐 [io.legado.app.web.WebSocketServer] (jvmAndAndroidMain NanoWSD 壳) 的 openWebSocket:
 * - /bookSourceDebug -> DebugWsHandler
 * - /searchBook -> SearchWsHandler
 * - cannotEmptyMsg 由 [KtorWebServerPlatform] 注入 (来源 WebStringsProviders)
 *
 * # 与 NanoWSD 的差异
 * NanoWSD 在 openWebSocket 返回 NanoWsSession 实例, NanoWSD 内部驱动 onOpen/onMessage/onClose;
 * Ktor 在 webSocket{} 块内手动读 incoming 通道, 转发到 handler, 关闭时调 onClose。
 */
fun Application.configureWsRouting(cannotEmptyMsg: String) {
    routing {
        webSocket("/bookSourceDebug") {
            handleWsSession(this) { session -> DebugWsHandler(session, cannotEmptyMsg) }
        }
        webSocket("/searchBook") {
            handleWsSession(this) { session -> SearchWsHandler(session, cannotEmptyMsg) }
        }
    }
}

/**
 * 驱动单次 WebSocket 会话生命周期 (对齐 NanoWsSession 的 onOpen/onMessage/onClose/onException)。
 *
 * @param wsSession Ktor 原生 WebSocketServerSession (incoming 通道 + 协程作用域)
 * @param handlerFactory 由 KtorWsSession (WsSession 适配器) 创建 handler; 收发解耦到 [io.legado.app.web.api]
 */
private suspend fun handleWsSession(
    wsSession: WebSocketServerSession,
    handlerFactory: (WsSession) -> WsHandler,
) {
    // 拉起 Service 续命 (app 端) / no-op (iOS/鸿蒙端), 对齐 NanoWSD WebSocketServer.openWebSocket
    WebServerManager.serve()
    val ktorSession = KtorWsSession(wsSession)
    val handler = handlerFactory(ktorSession)

    // 30s ping 心跳 (对齐 NanoWsSession.onOpen 的 ping 协程)
    wsSession.launch {
        while (isActive) {
            // Kotlin/Native 无 JVM 的 String.toByteArray(), 用 stdlib encodeToByteArray (UTF-8)
            wsSession.send(Frame.Ping("ping".encodeToByteArray()))
            delay(30000)
        }
    }

    try {
        for (frame in wsSession.incoming) {
            if (frame is Frame.Text) {
                handler.onMessage(frame.readText())
            }
        }
    } catch (_: Exception) {
        handler.onException()
    }
    handler.onClose()
}
