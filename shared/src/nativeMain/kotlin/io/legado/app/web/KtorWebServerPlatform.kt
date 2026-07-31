package io.legado.app.web

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.websocket.WebSockets
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.web.utils.AssetsWeb
import io.legado.app.web.utils.WebStringsProviders
import kotlin.concurrent.Volatile

/**
 * [WebServerPlatform] 的 iOS / 鸿蒙 (nativeMain) 共用基类 (Ktor server CIO 壳)。
 *
 * 对齐 [JvmWebServerPlatform] 的设计: HTTP server(=port) + WebSocket server(=port+1) 双服务器,
 * [serve] 留给子类 (iOS/鸿蒙均 no-op)。
 *
 * # 与 [JvmWebServerPlatform] 的差异
 * - HTTP 壳: NanoHTTPD → Ktor embeddedServer(CIO) (CIO 3.1.0 发布 iosArm64/linuxArm64 变体)
 * - WebSocket 壳: NanoWSD → Ktor embeddedServer(CIO) + WebSockets 插件
 * - IP 枚举: java.net.InetAddress → [localIPv4Addresses] (posix getifaddrs, 各端 actual)
 *
 * # 保真红线
 * - startServers 逐字对齐 WebService.upWebServer 的 try/catch 兜底
 * - stopServers 逐字对齐 WebService.onDestroy 的 stop 逻辑
 * - URL 格式 "http://host:port" 对齐 R.string.http_ip
 */
abstract class KtorWebServerPlatform : WebServerPlatform {

    private val assetsWeb = AssetsWeb("web")

    // embeddedServer 返回 EmbeddedServer 而非 ApplicationEngine; 星投影只用到与类型参数无关的 stop
    @Volatile
    private var httpEngine: EmbeddedServer<*, *>? = null

    @Volatile
    private var wsEngine: EmbeddedServer<*, *>? = null

    override fun startServers(port: Int): WebServerStartResult {
        // 先停旧实例 (对齐 JvmWebServerPlatform.startServers)
        stopServers()
        val cannotEmptyMsg = WebStringsProviders.get().cannotEmpty
        val httpEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            configureHttpRouting(assetsWeb)
        }
        val wsEngine = embeddedServer(CIO, port = port + 1, host = "0.0.0.0") {
            install(WebSockets) {
                // 对齐 app 端 webSocketServer.start(AppConst.timeLimit) 的通信超时语义
                pingPeriodMillis = AppConst.timeLimit
                timeoutMillis = AppConst.timeLimit
            }
            configureWsRouting(cannotEmptyMsg)
        }
        return try {
            httpEngine.start(wait = false)
            wsEngine.start(wait = false)
            this.httpEngine = httpEngine
            this.wsEngine = wsEngine
            // URL 格式 "http://host:port" 对齐 R.string.http_ip
            WebServerStartResult(localIPv4Addresses().map { "http://$it:$port" })
        } catch (e: Exception) {
            // 对齐 JvmWebServerPlatform catch: AppLog + 清理半启动状态 + 异常信息回传
            AppLog.put("startServers failed: ${e.message}", e)
            runCatching { httpEngine.stop(1000, 2000) }
            runCatching { wsEngine.stop(1000, 2000) }
            this.httpEngine = null
            this.wsEngine = null
            WebServerStartResult(errorMsg = e.message ?: "")
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

/**
 * 枚举本机 IPv4 非回环地址 (对齐 app 端 NetworkUtils.getLocalIPAddress 的筛选口径)。
 *
 * Native 端无 java.net.NetworkInterface, 改用 posix getifaddrs; 失败或无网卡时回落 127.0.0.1。
 * getifaddrs/ifaddrs 在 iOS 属 platform.darwin, 在鸿蒙 (linux) 属 platform.linux, 无公共导入,
 * 故拆成 expect/actual。
 */
internal expect fun localIPv4Addresses(): List<String>

internal const val LOOPBACK = "127.0.0.1"

/** in_addr.s_addr (网络字节序 32 位) 转点分十进制。 */
internal fun UInt.toIPv4String(): String =
    "${this and 0xFFu}.${(this shr 8) and 0xFFu}.${(this shr 16) and 0xFFu}.${(this shr 24) and 0xFFu}"
