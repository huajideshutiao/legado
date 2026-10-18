package io.legado.app.utils

import io.legado.app.constant.AppLog
import java.net.URI

/**
 * 桌面端 (JVM) 打开外链: 用 [java.awt.Desktop.browse] 启动系统默认浏览器。
 *
 * 替代 app 端 `Intent/Uri` 方式 (Android 专属); 不支持时记 [AppLog] 静默降级。
 * 下沉自桌面端 AboutScreen / ReadRssScreen 等 9+ 处重复实现。
 */
fun browseUrl(url: String): Boolean {
    val uri = url.toBrowseUri() ?: run {
        AppLog.put("无法打开链接 (非法 URL): $url")
        return false
    }
    return try {
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(uri)
                true
            } else {
                AppLog.put("桌面端不支持 BROWSE 动作, 无法打开: $url")
                false
            }
        } else {
            AppLog.put("桌面端 Desktop 不支持, 无法打开: $url")
            false
        }
    } catch (e: Exception) {
        AppLog.put("打开链接失败: $url\n${e.localizedMessage}", e)
        false
    }
}

/**
 * 把书源给的 URL 转成 [URI] 供 [java.awt.Desktop.browse] 使用。
 *
 * 与网络请求共用 [canonicalizeUrl] 这一条规则 (JVM 端由 OkHttp 的宽松解析完成编码),
 * 再交给严格解析器校验; 不合法或非绝对地址返回 null (无法交给系统浏览器)。
 */
fun String.toBrowseUri(): URI? {
    val canonical = canonicalizeUrl(this)
    return runCatching { URI(canonical) }.getOrNull()?.takeIf { it.isAbsolute }
}
