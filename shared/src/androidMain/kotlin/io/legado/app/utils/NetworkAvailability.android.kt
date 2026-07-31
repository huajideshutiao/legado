package io.legado.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.legado.app.ui.platform.sharedAppContext

/**
 * [isNetworkAvailable] 的 Android actual, 逐字搬自 app 端原 `NetworkAvailability.kt`,
 * 仅把 splitties 的 connectivityManager 换成 [sharedAppContext] 取系统服务
 * (shared androidMain 不依赖 splitties)。
 */
actual fun isNetworkAvailable(): Boolean {
    val connectivityManager = sharedAppContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = connectivityManager.activeNetwork
    if (network != null) {
        val nc = connectivityManager.getNetworkCapabilities(network)
        if (nc != null) {
            // WIFI
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                // 移动数据
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                // 以太网
                nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                // VPN
                nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
    return false
}
