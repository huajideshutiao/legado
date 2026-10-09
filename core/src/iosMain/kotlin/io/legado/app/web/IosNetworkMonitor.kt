@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.web

import io.legado.app.ui.compose.platform.syncGetString
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 网络变化监听 (对照 app 端 `NetworkChangedListener` 的
 * `ConnectivityManager.NetworkCallback.onAvailable`)。
 *
 * 用 `nw_path_monitor` (klib `platform.Network`, 即 `nw_path_monitor_*` C 接口) 盯默认路径,
 * 只在路径变为满足态 (`nw_path_status_satisfied`) 时回调 —— 与 Android 的 `onAvailable`
 * 同语义 (只关心"网来了", 不管断开), 目的是刷新本机可访问地址。
 *
 * 与 `io.legado.app.help.utils.NetworkAvailability` 的一次性可达性查询不同: 它是持续观测;
 * 与 Android 的 `NetworkChangedListener` 也不同: 不在某个 Service 生命周期内注册/注销,
 * 而是进程级挂一次 (宿主启动早期调用 [startIosNetworkMonitor])。
 *
 * # 线程
 * 更新回调跑在 [dispatch_get_main_queue] 上, 故回调里读/改状态安全
 * (地址刷新本身在 [WebServerManager] 的同步块里)。
 */
object IosNetworkMonitor {

    private var started = false

    private val monitor = nw_path_monitor_create()

    /** 开始观测 (幂等)。 */
    fun start() {
        if (started) return
        started = true
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (path == null) return@nw_path_monitor_set_update_handler
            if (nw_path_get_status(path) == nw_path_status_satisfied) {
                refreshWebServerAddresses()
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    /** 停止观测 (幂等)。 */
    fun stop() {
        if (!started) return
        started = false
        nw_path_monitor_cancel(monitor)
    }
}

/** 安装 iOS 网络变化观测者 (宿主启动早期调用一次)。 */
fun startIosNetworkMonitor() {
    IosNetworkMonitor.start()
}

/**
 * 网络可用时刷新 Web 服务地址 (对照 app 端 `WebService` 里
 * `networkChangedListener.onNetworkChanged` 那段中共享的一半: 重算地址 + 发事件)。
 *
 * 不重启服务器 (HttpServer 绑 0.0.0.0, IP 变了不影响服务器, 只影响展示给用户的地址)。
 * 通知那半是 Android Service 专属, 这里没有通知可刷。
 *
 * iOS 无常驻 Service, 事件源挂在进程级, 故显式加 [WebServerManager.isRun] 闸门:
 * 服务没开时既不改状态也不发事件, 与 Android 只在 Service 存活期监听的观感一致。
 */
private fun refreshWebServerAddresses() {
    if (!WebServerManager.isRun) return
    val port = WebServerManager.getPort()
    val addresses = localIPv4Addresses().map { "http://$it:$port" }
    WebServerManager.updateAddresses(addresses, syncGetString(UNAVAILABLE_KEY))
}

/** 无可用地址时的占位文案 key (原版 R.string.network_connection_unavailable)。 */
private const val UNAVAILABLE_KEY = "network_connection_unavailable"
