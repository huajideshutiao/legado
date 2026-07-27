package io.legado.app.web

import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.utils.postEvent
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Web 服务纯逻辑编排 (shared commonMain, 零平台依赖)。
 *
 * 原 app 端 `io.legado.app.service.WebService` (Android Service) 中的「端口校验 + isRun/
 * hostAddress 状态 + 起停协调 + EventBus 通知」上移至此; Android Service 壳 (通知/wakelock/
 * 网络监听/Tile) 留 app 端, 桌面端直接调 [start]/[stop]。平台服务器生命周期经
 * [WebServerPlatforms] 注入, IP 枚举由平台 actual 完成。
 *
 * # 线程安全
 * commonMain 无 @Synchronized, 用 SynchronizedObject + synchronized 块替代 (语义等价)。
 *
 * # 保真红线
 * 起停/端口/状态机逐字对齐 app 端 WebService.upWebServer / onDestroy / getPort / isRun / hostAddress,
 * 仅把「起 HttpServer/WebSocketServer + 算 IP」下沉到 [WebServerPlatform], 不改变行为。
 */
object WebServerManager {

    private val lock = SynchronizedObject()

    /** 服务是否运行 (原 WebService.isRun)。 */
    @Volatile
    var isRun: Boolean = false
        private set

    /** 当前主访问地址 (原 WebService.hostAddress), 形如 "http://192.168.1.2:1122"。 */
    @Volatile
    var hostAddress: String = ""
        private set

    /** 所有可访问地址 (供通知/二维码列表用, 原 WebService.notificationList 的 URL 部分)。 */
    @Volatile
    var hostAddresses: List<String> = emptyList()
        private set

    /**
     * 端口校验 (原 WebService.getPort):
     * 读 AppConfigAccessor.webPort, 不在 1024..65530 则回退 1122。
     */
    fun getPort(): Int {
        val port = AppConfigProviders.get().webPort
        return if (port in 1024..65530) port else 1122
    }

    /**
     * 起 Web 服务 (原 WebService.upWebServer 的共享部分):
     * 先停旧实例 → 起 HttpServer+WebSocketServer → 回填 isRun/hostAddress → 发 EventBus。
     *
     * @return 本机可访问 URL 列表 (空=无可用 IP, 调用方据此报错/stopSelf)
     */
    fun start(): List<String> = startWithResult().addresses

    /**
     * 同 [start], 但额外回传启动异常信息, 供平台壳 toast (原 WebService.upWebServer 的 catch 分支)。
     */
    fun startWithResult(): WebServerStartResult = synchronized(lock) {
        // 先停旧实例 (原 upWebServer 首段: httpServer?.isAlive==true → stop; webSocketServer 同理)
        WebServerPlatforms.get().stopServers()
        val port = getPort()
        val result = WebServerPlatforms.get().startServers(port)
        val addresses = result.addresses
        if (addresses.isNotEmpty()) {
            isRun = true
            hostAddresses = addresses
            hostAddress = addresses.first()
        } else {
            isRun = false
            hostAddresses = emptyList()
            hostAddress = ""
        }
        // 对齐 app 端 postEvent(EventBus.WEB_SERVICE, hostAddress)
        postEvent(EventBus.WEB_SERVICE, hostAddress)
        result
    }

    /**
     * 停 Web 服务 (原 WebService.onDestroy 的共享部分):
     * 停 HttpServer+WebSocketServer → 清状态 → 发 EventBus。
     */
    fun stop(): Unit = synchronized(lock) {
        WebServerPlatforms.get().stopServers()
        isRun = false
        hostAddress = ""
        hostAddresses = emptyList()
        // 对齐 app 端 onDestroy: postEvent(EventBus.WEB_SERVICE, "")
        postEvent(EventBus.WEB_SERVICE, "")
    }

    /**
     * 平台 Service 壳创建时置运行态 (原 WebService.onCreate 的 `isRun = true`)。
     * 原版在 onCreate 就置位, 使 Service 已创建但服务器尚未起时 UI 也显示"运行中"。
     */
    fun markRunning(): Unit = synchronized(lock) {
        isRun = true
    }

    /**
     * app 端 Service 续命 (原 WebService.serve); 桌面端 actual 为 no-op。
     * 由 HttpServer/WebSocketServer 收到请求时调用, 拉起 Service 重新 acquire wakelock。
     */
    fun serve() {
        WebServerPlatforms.get().serve()
    }

    /**
     * 网络变化时刷新本机地址列表 (原 WebService.onNetworkChanged 的共享部分):
     * 重新计算 hostAddress/hostAddresses → 发 EventBus。不重启服务器 (原 app 端行为:
     * HttpServer 绑定 0.0.0.0, IP 变化不影响服务器, 只需更新通知文案)。
     *
     * @param addresses 平台端枚举的本机可访问 URL 列表 (形如 "http://192.168.1.2:1122"),
     *   空列表表示无可用网络 (调用方据此显示"网络不可用")
     * @param unavailableText 无可用地址时 [hostAddress] 的占位文案 (原版 R.string.network_connection_unavailable);
     *   由平台壳传入本地化字符串, 空串则沿用原空值语义
     */
    fun updateAddresses(addresses: List<String>, unavailableText: String = "") = synchronized(lock) {
        if (addresses.isNotEmpty()) {
            hostAddresses = addresses
            hostAddress = addresses.first()
        } else {
            hostAddresses = emptyList()
            hostAddress = unavailableText
        }
        postEvent(EventBus.WEB_SERVICE, hostAddress)
    }
}
