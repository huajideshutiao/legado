package io.legado.app.ui

import io.legado.app.help.openURL
import io.legado.app.ui.root.PlatformCapabilities

object IosPlatformCapabilities : PlatformCapabilities {
    override fun exitApplication() = Unit

    override fun openExternalUrl(url: String) {
        openURL(url)
    }

    override fun shareText(text: String) {
        // 分享面板属于 UIKit 系统能力，后续由唯一 UIViewController 入口注入真实 presenter。
    }
}
