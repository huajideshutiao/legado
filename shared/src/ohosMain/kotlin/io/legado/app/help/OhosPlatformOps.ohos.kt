package io.legado.app.help

import io.legado.app.help.ui.OpenUrlProviders

// ohos 平台 openURL / 剪贴板 / 文件选择 工具 (对照 iosMain IosPlatformOps.ios.kt)

/** 用系统浏览器打开外部链接 (对照 iOS openURL / desktop browseUrl)。 */
fun openURL(url: String) {
    OpenUrlProviders.get().openUrl(url)
}

/** 复制文本到系统剪贴板 (对照 iOS copyToClipboard / desktop Toolkit.systemClipboard)。 */
fun copyToClipboard(text: String) {
    // TODO: 桥接 ohos pasteboard (ohos.pasteboard.SystemPasteboard)
    println("[Clipboard] copy: ${text.take(64)}")
}

/** 读取系统剪贴板文本 (对照 iOS readFromClipboard / desktop Toolkit.systemClipboard)。 */
fun readFromClipboard(): String? {
    // TODO: 桥接 ohos pasteboard (ohos.pasteboard.SystemPasteboard)
    return null
}
