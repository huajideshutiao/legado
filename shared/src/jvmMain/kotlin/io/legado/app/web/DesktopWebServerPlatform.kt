package io.legado.app.web

import io.legado.app.web.WebServerPlatforms

/**
 * [WebServerPlatform] 的桌面 JVM actual 实现。
 *
 * 继承 [JvmWebServerPlatform] 共用 HttpServer/WebSocketServer 起/停逻辑;
 * [serve] 桌面端无 Service 概念, no-op (对齐 WebServerPlatform.serve 注释「非 Android 端 no-op」)。
 *
 * # 调用时机
 * desktop main(), 在任何 commonMain 代码调用 `WebServerManager.start()/stop()` 之前。
 */
class DesktopWebServerPlatform : JvmWebServerPlatform() {

    override fun serve() {
        // 桌面端无 Android Service 续命机制, no-op
        // (HttpServer.serve -> WebServerManager.serve -> WebServerPlatform.serve 链路在桌面端空实现)
    }
}

/**
 * 桌面宿主启动早期注册 [WebServerPlatform] 的 actual 实现。
 *
 * 模式参考 `registerDesktopServiceLauncher`。
 */
fun registerDesktopWebServerPlatform() {
    WebServerPlatforms.register(DesktopWebServerPlatform())
}
