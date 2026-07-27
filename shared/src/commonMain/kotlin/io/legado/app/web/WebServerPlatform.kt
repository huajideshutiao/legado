package io.legado.app.web

/**
 * Web 服务 (HttpServer + WebSocketServer) 平台实现抽象 (shared commonMain)。
 *
 * # 背景
 * app 端 [io.legado.app.service.WebService] 是 Android Service, 持有 HttpServer(NanoHTTPD) +
 * WebSocketServer(NanoWSD); 桌面端无 Service 概念, 复用同一套 NanoHTTPD 壳直接起服务。
 * iOS/鸿蒙暂不支持 web 服务。启停/端口/状态 (isRun/hostAddress) 等纯逻辑上移至
 * [WebServerManager], 本接口只承接平台相关的「起/停服务器 + 枚举本机 IP + keepalive」三件事。
 *
 * # 设计
 * - [startServers] 起 HttpServer(=port) + WebSocketServer(=port+1), 返回本机可访问地址列表
 *   (形如 "http://192.168.1.2:1122"), 供 [WebServerManager] 回填 hostAddress 与通知文案
 * - [stopServers] 停两个服务器
 * - [serve] 对应 app 端原 WebService.serve(): 拉起 Service 续命 (wakelock); 非 Android 端 no-op
 *
 * 模式参考 [io.legado.app.help.service.ServiceLaunchers]。
 */
interface WebServerPlatform {

    /**
     * 起 HttpServer + WebSocketServer。
     *
     * @param port HttpServer 端口; WebSocketServer 用 port+1 (对齐 app 端 [WebService.upWebServer])
     * @return 本机可访问 URL 列表 (空列表=无可用 IP, 调用方据此判定失败)
     */
    fun startServers(port: Int): List<String>

    /** 停 HttpServer + WebSocketServer (对齐 app 端 [WebService.onDestroy] / [WebService.upWebServer] 首段)。 */
    fun stopServers()

    /** app 端 Service 续命 (wakelock); 非 Android 端 no-op (对齐 app 端 WebService.serve)。 */
    fun serve()
}

/**
 * [WebServerPlatform] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 安卓端调用 `registerAndroidWebServerPlatform(context, cannotEmptyResId)` (见 androidMain),
 * 桌面端调用 `registerDesktopWebServerPlatform()` (见 jvmMain)。
 *
 * 模式参考 [io.legado.app.help.service.ServiceLaunchers]。
 */
object WebServerPlatforms {

    @Volatile
    private var impl: WebServerPlatform? = null

    /** 宿主启动早期注册一次 (任何 WebServerManager 调用之前)。 */
    fun register(impl: WebServerPlatform) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): WebServerPlatform =
        impl ?: error("WebServerPlatforms not registered; call registerAndroidWebServerPlatform() or registerDesktopWebServerPlatform() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}
