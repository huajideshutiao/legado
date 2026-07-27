package io.legado.desktop.help.ui

import io.legado.app.help.ui.OpenUrlProvider
import io.legado.app.help.ui.OpenUrlProviders
import java.awt.Desktop
import java.net.URI

/**
 * [OpenUrlProvider] 桌面端实现。
 *
 * 用 `Desktop.browse` 调系统默认浏览器打开链接, 替代 app 端 `OpenUrlConfirmDialog`。
 * 在 main 入口经 [registerDesktopOpenUrlProvider] 注册到 [OpenUrlProviders]。
 */
object DesktopOpenUrlProviderImpl : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int
    ) {
        // 桌面端可能不支持 Desktop.API (无图形环境), 失败时回退控制台打印
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }.onFailure { it.printStackTrace() }
        println("[OpenUrl] $url")
    }
}

/** 桌面端 main 入口早期注册一次, 任何 JsExtensionsCommon.openUrl 调用之前。 */
fun registerDesktopOpenUrlProvider() {
    OpenUrlProviders.register(DesktopOpenUrlProviderImpl)
}
