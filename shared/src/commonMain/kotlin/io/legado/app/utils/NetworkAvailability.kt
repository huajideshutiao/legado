package io.legado.app.utils

/**
 * 网络可用性检查 (下沉自 app 端 `NetworkAvailability.kt`)。
 *
 * Android actual 走 connectivityManager (行为与原版逐字一致); 桌面/iOS/鸿蒙无等价
 * 权限模型, 恒返回 true, 由调用方自身的异常处理兜底 (与 AppWebDavShared 一致)。
 */
expect fun isNetworkAvailable(): Boolean

/**
 * 是否连接 WIFI (封面 loadOnlyWifi 拦截用, 下沉自 app 端 `Context.isWifiConnect`)。
 * Android actual 走 connectivityManager; 桌面/iOS/鸿蒙无等价概念, 恒 true。
 */
expect fun isWifiConnect(): Boolean
