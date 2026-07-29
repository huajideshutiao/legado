package io.legado.app.ui

import io.legado.app.help.openURL
import io.legado.app.ui.root.PlatformCapabilities

object OhosPlatformCapabilities : PlatformCapabilities {
    override fun exitApplication() = Unit

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    override fun shareText(text: String) {
        // 系统分享由 EntryAbility/NAPI 注入，业务页面不再直接持有 ArkTS context。
    }
}
