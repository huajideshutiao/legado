package io.legado.app.utils

/**
 * 网络可用性检查 (下沉自 app 端 `NetworkAvailability.kt`)。
 *
 * - Android actual 走 connectivityManager (行为与原版逐字一致);
 * - iOS actual 走 SCNetworkReachabilityGetFlags (同步查询);
 * - 鸿蒙 actual 经 OhosNativeBridge napi 桥查 @ohos.net.connection (同步查询);
 * - 桌面无网络权限模型, 恒返回 true, 由调用方自身的异常处理兜底 (与 AppWebDavShared 一致)。
 */
expect fun isNetworkAvailable(): Boolean

/**
 * 是否连接 WIFI (封面 loadOnlyWifi 拦截用, 下沉自 app 端 `Context.isWifiConnect`)。
 *
 * - Android actual 走 connectivityManager (严格 TRANSPORT_WIFI);
 * - iOS actual 走 SCNetworkReachability (非蜂窝即视为 WiFi);
 * - 鸿蒙 actual 经 napi 桥查 bearerType == BEARER_WIFI;
 * - 桌面恒 true (无 WiFi 概念)。
 */
expect fun isWifiConnect(): Boolean
