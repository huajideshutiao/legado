package io.legado.app.web

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.websockets.WebSockets
import io.legado.app.constant.AppLog
import io.legado.app.web.utils.AssetsWeb
import io.legado.app.web.utils.WebStringsProviders

/**
 * [WebServerPlatform] 的 iOS / 鸿蒙 (nativeMain) 共用基类 (Ktor server CIO 壳)。
 *
 * 对齐 [JvmWebServerPlatform] 的设计: HTTP server(=port) + WebSocket server(=port+1) 双服务器,
 * [serve] 留给子类 (iOS/鸿蒙均 no-op)。
 *
 * # 与 [JvmWebServerPlatform] 的差异
 * - HTTP 壳: NanoHTTPD → Ktor embeddedServer(CIO) (CIO 3.1.0 发布 iosArm64/linuxArm64 变体)
 * - WebSocket 壳: NanoWSD → Ktor embeddedServer(CIO) + WebSockets 插件
 * - IP 枚举: java.net.InetAddress → 127.0.0.1 (Native 端无 InetAddress, LAN IP 枚举需平台 API)
 *
 * # 保真红线
 * - startServers 逐字对齐 WebService.upWebServer 的 try/catch 兜底
 * - stopServers 逐字对齐 WebService.onDestroy 的 stop 逻辑
 * - URL 格式 "http://host:port" 对齐 R.string.http_ip
 */
abstract class KtorWebServerPlatform : WebServerPlatform {

    private val assetsWeb = AssetsWeb("web")

    @Volatile
    private var httpEngine: ApplicationEngine? = null

    @Volatile
    private var wsEngine: ApplicationEngine? = null

    override fun startServers(port: Int): List<String> {
        // 先停旧实例 (对齐 JvmWebServerPlatform.startServers)
        stopServers()
        val cannotEmptyMsg = WebStringsProviders.get().cannotEmpty
        val httpEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            configureHttpRouting(assetsWeb)
        }
        val wsEngine = embeddedServer(CIO, port = port + 1, host = "0.0.0.0") {
            install(WebSockets)
            configureWsRouting(cannotEmptyMsg)
        }
        return try {
            httpEngine.start(wait = false)
            wsEngine.start(wait = false)
            this.httpEngine = httpEngine
            this.wsEngine = wsEngine
            // Native 端无 java.net.InetAddress, 返回 localhost (LAN IP 枚举需平台 API)
            listOf("http://127.0.0.1:$port")
        } catch (e: Exception) {
            // 对齐 JvmWebServerPlatform catch(IOException): AppLog + 清理半启动状态
            AppLog.put("startServers failed: ${e.localizedMessage}", e)
            runCatching { httpEngine.stop(1000, 2000) }
            runCatching { wsEngine.stop(1000, 2000) }
            this.httpEngine = null
            this.wsEngine = null
            emptyList()
        }
    }

    override fun stopServers() {
        // 对齐 JvmWebServerPlatform.stopServers: stop + null
        httpEngine?.stop(1000, 2000)
        wsEngine?.stop(1000, 2000)
        httpEngine = null
        wsEngine = null
    }
}
