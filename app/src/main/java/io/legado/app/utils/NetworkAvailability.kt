package io.legado.app.utils

import android.net.NetworkCapabilities
import splitties.systemservices.connectivityManager

/**
 * 网络可用性检查。原 [NetworkUtils] object 中纯 URL/IP 函数已下沉 shared jvmAndAndroidMain
 * (见 modules/shared/src/jvmAndAndroidMain/kotlin/io/legado/app/utils/NetworkUtils.kt),
 * 仅 [isAvailable] 走 connectivityManager 安卓绑定, 留 app 作为顶层 fun。
 *
 * 调用方原 `NetworkUtils.isAvailable()` 改为 `isNetworkAvailable()`。
 */
fun isNetworkAvailable(): Boolean {
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
