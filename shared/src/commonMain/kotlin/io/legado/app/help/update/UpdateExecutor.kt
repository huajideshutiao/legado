package io.legado.app.help.update

import io.legado.app.ui.root.PlatformServiceProviders
import kotlin.concurrent.Volatile

/**
 * 更新执行方式 (与检测层正交, 各端自由组合)。
 */
enum class UpdateAction {
    /** Android: 下载 apk → 跳系统安装器。 */
    DIRECT_INSTALL,

    /** Desktop: 下载安装包 (msi/dmg/deb/rpm) 后提示用户运行。 */
    DOWNLOAD_AND_PROMPT,

    /** iOS: `sidestore://install?url=` / `altstore://install?url=` 拉起侧载工具。 */
    SIDELOAD_DEEP_LINK,

    /** 侧载兜底: 浏览器打开 release 页 / 安装包直链, 用户自行安装。 */
    OPEN_DOWNLOAD_PAGE,

    /** 预留: 跳 App Store / 华为应用市场 (上架后启用)。 */
    OPEN_STORE,
}

/**
 * 更新执行层接口。
 *
 * shared 只提供跨端兜底实现 [OpenPageUpdateExecutor] (走 [PlatformServiceProviders] 的浏览器服务);
 * 需要真正下载/安装的端 (Android/desktop) 在自己那侧实现并注册。
 */
interface UpdateExecutor {
    /**
     * 执行更新。
     *
     * @return false 表示本端无法完成该动作, 调用方降级为 [OpenPageUpdateExecutor]
     */
    suspend fun execute(action: UpdateAction, info: UpdateCheckInfo): Boolean
}

/**
 * 跨端兜底执行器: 一律用系统浏览器打开直链或 release 页, 不做下载/安装。
 *
 * iOS 的 [UpdateAction.SIDELOAD_DEEP_LINK] 在这里也只能退化成打开页面 ——
 * 拉起 SideStore/AltStore 需要 `UIApplication.canOpenURL` 探测, 由 iOS 端执行器实现,
 * deep link 串由 [sideloadDeepLinks] 统一生成。
 */
object OpenPageUpdateExecutor : UpdateExecutor {
    override suspend fun execute(action: UpdateAction, info: UpdateCheckInfo): Boolean {
        val url = info.landingUrl.ifBlank { info.downloadUrl }
        if (url.isBlank()) return false
        val browser = PlatformServiceProviders.getOrNull()?.browser ?: return false
        browser.openUrl(url)
        return true
    }
}

/**
 * iOS 侧载工具 deep link。
 *
 * 实测 (2026-07, 读两个仓库的 Info.plist + URLHandler):
 * - SideStore 只注册 `sidestore://`, AltStore 只注册 `altstore://`, 二者不互通
 * - 两者的 URLHandler 都有 `install` host, 取 query 参数 `url` 作为 ipa 直链
 *
 * 因此按 sidestore → altstore 顺序探测, 全失败退回 release 页。
 * iOS 端需在 Info.plist 的 `LSApplicationQueriesSchemes` 登记这两个 scheme,
 * 否则 `canOpenURL` 恒返回 false。
 */
fun sideloadDeepLinks(ipaUrl: String): List<String> {
    if (ipaUrl.isBlank()) return emptyList()
    val encoded = encodeQueryValue(ipaUrl)
    return listOf(
        "sidestore://install?url=$encoded",
        "altstore://install?url=$encoded",
    )
}

// commonMain 无 URLEncoder, 只转义 query 里会产生歧义的字符
private fun encodeQueryValue(raw: String): String = buildString {
    raw.forEach { c ->
        when (c) {
            in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '_', '.', '~', ':', '/' -> append(c)
            else -> c.toString().encodeToByteArray().forEach { b ->
                append('%')
                append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/** [UpdateExecutor] 容器: 各端启动时注册自己的实现, 未注册则用 [OpenPageUpdateExecutor]。 */
object UpdateExecutors {
    @Volatile
    private var impl: UpdateExecutor? = null

    fun register(executor: UpdateExecutor) {
        impl = executor
    }

    fun get(): UpdateExecutor = impl ?: OpenPageUpdateExecutor
}
