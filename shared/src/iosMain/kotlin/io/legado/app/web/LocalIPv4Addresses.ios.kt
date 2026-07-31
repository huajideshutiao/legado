package io.legado.app.web

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

/** [localIPv4Addresses] 的 iOS actual: getifaddrs/ifaddrs 在 iOS 属 platform.darwin。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun localIPv4Addresses(): List<String> = memScoped {
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
