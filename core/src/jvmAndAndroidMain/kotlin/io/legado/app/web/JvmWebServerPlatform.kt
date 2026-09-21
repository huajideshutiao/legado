package io.legado.app.web

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.utils.NetworkUtils
import io.legado.app.web.WebServerPlatform
import java.io.IOException

/**
 * [WebServerPlatform] 的 JVM 共用基类 (Android + 桌面 JVM)。
 *
 * 封装 HttpServer + WebSocketServer 的「起/停 + IP 枚举」共用逻辑, 对齐 app 端原
 * [io.legado.app.service.WebService.upWebServer] / [onDestroy] 的服务器生命周期管理。
 *
 * # 设计
 * - [startServers] / [stopServers] 在本基类实现, Android/桌面共用 (NanoHTTPD 纯 Java)
 * - [serve] 留给子类: Android actual 拉起 Service 续命 (wakelock), 桌面 actual no-op
 *
 * # 保真红线
 * - startServers 逐字对齐 WebService.upWebServer 的 try/catch + IOException 兜底
 * - stopServers 逐字对齐 WebService.onDestroy 的 isAlive 判定
 * - WebSocketServer 启动超时用 AppConst.timeLimit (对齐 WebService.upWebServer)
 * - URL 格式 "http://host:port" 对齐 R.string.http_ip ("http://%1\$s:%2\$d")
 */
abstract class JvmWebServerPlatform : WebServerPlatform {

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var webSocketServer: WebSocketServer? = null

    override fun startServers(port: Int): WebServerStartResult {
        // 先停旧实例 (原 upWebServer 首段: httpServer?.isAlive==true → stop; webSocketServer 同理)
        stopServers()
        val addressList = NetworkUtils.getLocalIPAddress()
        if (addressList.isEmpty()) return WebServerStartResult()
        val httpServer = HttpServer(port)
        val webSocketServer = WebSocketServer(port + 1)
        return try {
            httpServer.start()
            // 通信超时设置 (对齐 app 端 webSocketServer?.start(AppConst.timeLimit.toInt()))
            webSocketServer.start(AppConst.timeLimit.toInt())
            this.httpServer = httpServer
            this.webSocketServer = webSocketServer
            // URL 格式 "http://host:port" 对齐 R.string.http_ip
            WebServerStartResult(addressList.map { "http://${it.hostAddress}:$port" })
        } catch (e: IOException) {
            // 对齐 app 端 upWebServer catch(IOException): 异常信息回传给平台壳 toast
            AppLog.put("startServers failed: ${e.localizedMessage}", e)
            // 清理半启动状态
            runCatching { httpServer.stop() }
            runCatching { webSocketServer.stop() }
            this.httpServer = null
            this.webSocketServer = null
            WebServerStartResult(errorMsg = e.localizedMessage ?: "")
        }
    }

    override fun stopServers() {
        // 逐字对齐 WebService.onDestroy: isAlive 判定后 stop
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        httpServer = null
        webSocketServer = null
    }
}
