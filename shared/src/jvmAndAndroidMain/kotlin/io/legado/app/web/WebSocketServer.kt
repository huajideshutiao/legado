package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.web.api.DebugWsHandler
import io.legado.app.web.api.SearchWsHandler
import io.legado.app.web.socket.NanoWsSession
import io.legado.app.web.utils.WebStringsProviders

/**
 * nanohttpd(NanoWSD) 薄壳: handshake.uri 分派到 [DebugWsHandler] / [SearchWsHandler]。
 *
 * # 下沉说明 (原 app 端 io.legado.app.web.WebSocketServer)
 * - `WebService.serve()` (app Android Service 续命) → [WebServerManager.serve] (commonMain)
 * - `appCtx.getString(R.string.cannot_empty)` (Android R 资源) → [WebStringsProviders.get().cannotEmpty]
 *   (各端 actual 注入, 桌面端硬编码中文 / 安卓端 R.string.cannot_empty)
 * - 其余逻辑逐字等价, 保真红线
 */
class WebSocketServer(port: Int) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        // 拉起 Service 续命 (app 端) / no-op (桌面端)
        WebServerManager.serve()
        val cannotEmptyMsg = WebStringsProviders.get().cannotEmpty
        return when (handshake.uri) {
            "/bookSourceDebug" -> {
                NanoWsSession(handshake) { session -> DebugWsHandler(session, cannotEmptyMsg) }
            }
            "/searchBook" -> {
                NanoWsSession(handshake) { session -> SearchWsHandler(session, cannotEmptyMsg) }
            }
            else -> null
        }
    }
}
