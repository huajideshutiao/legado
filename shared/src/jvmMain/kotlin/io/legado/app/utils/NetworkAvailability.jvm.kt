package io.legado.app.utils

/** 桌面 JVM 无网络权限模型, 恒可用; 失败由调用方异常处理兜底。 */
actual fun isNetworkAvailable(): Boolean = true

/** 桌面 JVM 无"仅 wifi"概念, 恒视为 wifi; loadOnlyWifi 仅 Android 消费。 */
actual fun isWifiConnect(): Boolean = true
