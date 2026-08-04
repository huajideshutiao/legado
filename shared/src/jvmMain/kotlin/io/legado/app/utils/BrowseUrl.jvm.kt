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
 * 书源 URL 经常未百分号编码 (中文参数/空格等), app 端 `url.toUri()` (android.net.Uri.parse)
 * 宽松不抛异常; 而 `java.net.URI(url)` 单参构造遇非法字符直接抛 URISyntaxException,
 * 导致"启动浏览器无反应"(异常被静默吞掉)。这里先试严格解析, 失败再分段解析:
 * 拆成 scheme/authority/path/query/fragment 交给多参构造, 它只对非法字符做 quote,
 * 已合法的部分 (含 %XX 编码、保留字符) 原样保留。
 */
fun String.toBrowseUri(): URI? {
    // 严格解析: 合法绝对 URI 直接用; 相对 URI (无 scheme) 或含非法字符的走下面分段解析
    runCatching {
        val strict = URI(this)
        if (strict.isAbsolute) return strict
    }.onFailure { }
    return runCatching {
        val schemeIdx = indexOf(':')
        if (schemeIdx <= 0) return null // 无 scheme 无法 browse
        val scheme = substring(0, schemeIdx)
        val rest = substring(schemeIdx + 1)
        val (authority, pathQueryFragment) = if (rest.startsWith("//")) {
            val end = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
            rest.substring(2, end) to rest.substring(end)
        } else {
            null to rest
        }
        val hashIdx = pathQueryFragment.indexOf('#')
        val fragment = if (hashIdx >= 0) pathQueryFragment.substring(hashIdx + 1) else null
        val pathAndQuery =
            if (hashIdx >= 0) pathQueryFragment.substring(0, hashIdx) else pathQueryFragment
        val queryIdx = pathAndQuery.indexOf('?')
        val query = if (queryIdx >= 0) pathAndQuery.substring(queryIdx + 1) else null
        val path = if (queryIdx >= 0) pathAndQuery.substring(0, queryIdx) else pathAndQuery
        val uri = URI(scheme, authority, path, query, fragment)
        // JDK 多参构造只 quote 非法 ASCII, 非 ASCII (中文等) 会原样保留在 toString 里;
        // toASCIIString 把非 ASCII 按 UTF-8 编码成 %XX, 保证跨平台 (Linux/macOS 的
        // Desktop.browse 不走宽字符 ShellExecute) 都能正确打开
        URI(uri.toASCIIString())
    }.getOrNull()
}
