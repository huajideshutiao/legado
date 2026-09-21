package io.legado.app.web

import io.legado.app.web.WebServerPlatforms

/**
 * [WebServerPlatform] 的 Android actual 实现。
 *
 * 继承 [JvmWebServerPlatform] 共用 HttpServer/WebSocketServer 起/停逻辑;
 * [serve] 由 app 端注入回调 (拉起 [io.legado.app.service.WebService] 续命 wakelock),
 * 避免 shared androidMain 反向依赖 app 模块的 WebService class。
 *
 * # 调用时机
 * App.onCreate, 在任何 commonMain 代码调用 `WebServerManager.start()/stop()` 之前。
 *
 * @param serveCallback app 端注入的 Service 续命回调, 对齐原 WebService.serve():
 *   `appCtx.startService<WebService> { action = "serve" }`
 */
class AndroidWebServerPlatform(
    private val serveCallback: () -> Unit,
) : JvmWebServerPlatform() {

    override fun serve() {
        serveCallback()
    }
}

/**
 * 安卓宿主启动早期注册 [WebServerPlatform] 的 actual 实现。
 *
 * @param serveCallback app 端注入的 Service 续命回调 (对齐原 WebService.serve)
 *
 * 模式参考 `registerAndroidServiceLauncher`。
 */
fun registerAndroidWebServerPlatform(serveCallback: () -> Unit) {
    WebServerPlatforms.register(AndroidWebServerPlatform(serveCallback))
}
