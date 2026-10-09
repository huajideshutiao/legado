package io.legado.app.web

import io.legado.app.ui.compose.platform.syncGetString
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

/**
 * 枚举本机 IPv4 非回环地址 (对齐 app 端 NetworkUtils.getLocalIPAddress 的筛选口径)。
 *
 * 鸿蒙无 java.net.NetworkInterface, 改用 posix getifaddrs (在 linux target 属 platform.linux);
 * 失败或无网卡时回落 127.0.0.1。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun localIPv4Addresses(): List<String> = memScoped {
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

/**
 * 鸿蒙网络可用事件 → 刷新 Web 服务地址 (对照 app 端 `WebService` 里
 * `networkChangedListener.onNetworkChanged` 那段中共享的一半: 重算地址 + 发事件)。
 *
 * 事件由 ArkTS 侧 `@ohos.net.connection` 的 `on('netAvailable')` 经统一平台事件通道回送
 * (见 OhosPlatformEvents.kt 的 `networkAvailable` 分支)。不重启服务器 (HttpServer 绑
 * 0.0.0.0, IP 变了不影响服务器, 只影响展示给用户的地址)。
 *
 * 与 Android 的差别 (如实): Android 在 `WebService` 存活期注册网络回调, 服务没开就没有监听;
 * 鸿蒙无常驻 Service, 监听挂在进程级, 故这里显式加 [WebServerManager.isRun] 闸门,
 * 保证"服务没开时不改状态也不发事件", 与 Android 观感一致。
 */
fun refreshOhosWebServerAddresses() {
    if (!WebServerManager.isRun) return
    val port = WebServerManager.getPort()
    val addresses = localIPv4Addresses().map { "http://$it:$port" }
    WebServerManager.updateAddresses(addresses, syncGetString(UNAVAILABLE_KEY))
}

/** 无可用地址时的占位文案 key (原版 R.string.network_connection_unavailable)。 */
private const val UNAVAILABLE_KEY = "network_connection_unavailable"
