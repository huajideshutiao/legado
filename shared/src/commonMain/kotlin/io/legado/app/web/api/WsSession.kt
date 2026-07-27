package io.legado.app.web.api

/**
 * 平台无关的 WebSocket 会话出站句柄 (零 android import)。
 *
 * handler 只认本接口回推消息/关闭; nanohttpd(NanoWSD.WebSocket)、Ktor(webSocket{})
 * 各自提供薄适配实现。ping 心跳等平台机制留在各壳。
 */
interface WsSession {
    /** 回推文本帧 */
    fun send(text: String)

    /** 正常关闭 (NormalClosure)，reason 为关闭原因 */
    fun close(reason: String)
}
