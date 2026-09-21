package io.legado.app.web.api

/**
 * 平台无关的 WebSocket 入站处理器 (零 android import)。
 *
 * 出站经构造注入的 [WsSession]; ping 心跳/帧解码等平台机制留在各壳。
 * nanohttpd/Ktor 壳把收到的文本帧转发到 [onMessage]，会话关闭/异常分别转发到
 * [onClose]/[onException] (与旧 NanoWSD 回调语义逐一对应)。
 */
interface WsHandler {
    /** 收到一条文本消息 */
    fun onMessage(text: String)

    /** 会话正常关闭 (对应 NanoWSD.onClose) */
    fun onClose()

    /** 会话异常 (对应 NanoWSD.onException) */
    fun onException()
}
