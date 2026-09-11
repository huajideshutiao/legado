package io.legado.app.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.SystemConfiguration.SCNetworkReachabilityCreateWithName
import platform.SystemConfiguration.SCNetworkReachabilityGetFlags
import platform.SystemConfiguration.kSCNetworkReachabilityFlagsReachable

/**
 * iOS 网络状态查询 (对照 Android ConnectivityManager / 原版 Context.isWifiConnect)。
 *
 * 用 SystemConfiguration 的 SCNetworkReachabilityCreateWithName + SCNetworkReachabilityGetFlags
 * 同步查询: 无状态、无订阅生命周期, 每次调用现查现用:
 * - [isNetworkAvailable]: flags 含 Reachable (蜂窝/WiFi/以太网均算可达)
 *
 * 查询失败一律 false, 依据 Android actual (拿不到 ConnectivityManager / activeNetwork /
 * NetworkCapabilities 时一律 `return false`, fail-closed); 鸿蒙端已按同一依据统一。
 */
@OptIn(ExperimentalForeignApi::class)
private fun reachabilityFlags(): UInt? = memScoped {
    // kCFAllocatorDefault 即 NULL, 直接传 null 避免 CFAllocatorRef 类型转换
    val reachability = SCNetworkReachabilityCreateWithName(null, "example.com") ?: return@memScoped null
    try {
        val flags = alloc<UIntVar>()
        if (!SCNetworkReachabilityGetFlags(reachability, flags.ptr)) return@memScoped null
        // SCNetworkReachabilityFlags 是 uint32_t typedef, K/N 绑定为 UInt;
        // 常量 (kSCNetworkReachabilityFlags*) 同为 UInt, 全程 UInt 位运算避免类型转换
        flags.value
    } finally {
        CFRelease(reachability)
    }
}

actual fun isNetworkAvailable(): Boolean {
    val flags = reachabilityFlags() ?: return false
    return flags and kSCNetworkReachabilityFlagsReachable != 0u
}
