package io.legado.app.web

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.websockets.WebSockets
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.web.utils.AssetsWeb
import io.legado.app.web.utils.WebStringsProviders
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.freeifaddrs
import platform.posix.getifaddrs
import platform.posix.ifaddrs
import platform.posix.sockaddr_in

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
            WebServerStartResult(getLocalIPv4Addresses().map { "http://$it:$port" })
        } catch (e: Exception) {
            // 对齐 JvmWebServerPlatform catch: AppLog + 清理半启动状态 + 异常信息回传
            AppLog.put("startServers failed: ${e.localizedMessage}", e)
            runCatching { httpEngine.stop(1000, 2000) }
            runCatching { wsEngine.stop(1000, 2000) }
            this.httpEngine = null
            this.wsEngine = null
            WebServerStartResult(errorMsg = e.localizedMessage ?: "")
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
 * Native 端无 java.net.NetworkInterface, 改用 posix getifaddrs; 失败或无网卡时回落 127.0.0.1,
 * 保证本机仍可访问。
 */
@OptIn(ExperimentalForeignApi::class)
private fun getLocalIPv4Addresses(): List<String> = memScoped {
    val addresses = mutableListOf<String>()
    val ifap = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(ifap.ptr) != 0) return listOf(LOOPBACK)
    try {
        var cur = ifap.value
        while (cur != null) {
            val ifa = cur.pointed
            val addr = ifa.ifa_addr
            if (addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
                val ip = addr.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr.toIPv4String()
                // 排除回环 (对齐原版 !address.isLoopbackAddress)
                if (ip != LOOPBACK && !ip.startsWith("127.")) {
                    addresses.add(ip)
                }
            }
            cur = ifa.ifa_next
        }
    } finally {
        freeifaddrs(ifap.value)
    }
    addresses.ifEmpty { listOf(LOOPBACK) }
}

private const val LOOPBACK = "127.0.0.1"

/** in_addr.s_addr (网络字节序 32 位) 转点分十进制。 */
private fun UInt.toIPv4String(): String =
    "${this and 0xFFu}.${(this shr 8) and 0xFFu}.${(this shr 16) and 0xFFu}.${(this shr 24) and 0xFFu}"
