package io.legado.app.utils

import io.legado.app.constant.AppLog

/**
 * 桌面端 (JVM) 打开外链: 用 [java.awt.Desktop.browse] 启动系统默认浏览器。
 *
 * 替代 app 端 `Intent/Uri` 方式 (Android 专属); 不支持时记 [AppLog] 静默降级。
 * 下沉自桌面端 AboutScreen / ReadRssScreen 等 9+ 处重复实现。
 */
fun browseUrl(url: String) {
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(java.net.URI(url))
            } else {
                AppLog.put("桌面端不支持 BROWSE 动作, 无法打开: $url")
            }
        } else {
            AppLog.put("桌面端 Desktop 不支持, 无法打开: $url")
        }
    } catch (e: Exception) {
        AppLog.put("打开链接失败: $url\n${e.localizedMessage}", e)
    }
}
