@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.sharedApplication

// iOS 平台 openURL / 剪贴板 / 顶层 vc 查找 工具 (对照 desktop java.awt.Desktop / Toolkit.systemClipboard)

/** 用系统浏览器打开外部链接 (对照 desktop browseUrl)。 */
fun openURL(url: String) {
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication().openURL(it) }
}

/** 复制文本到系统剪贴板 (对照 desktop Toolkit.systemClipboard.setContents)。 */
fun copyToClipboard(text: String) {
    UIPasteboard.generalPasteboard().string = text
}

/** 读取系统剪贴板文本 (对照 desktop Toolkit.systemClipboard.getData)。 */
fun readFromClipboard(): String? {
    return UIPasteboard.generalPasteboard().string
}

/**
 * 获取最顶层 [UIViewController]: 从 keyWindow.rootViewController
 * 遍历 presentedViewController 链取最顶层的 vc。
 *
 * 供 [IosToaster] / [IosFilePicker] 等需 present 的组件共用 (避免各自重复实现)。
 * 拿不到 keyWindow/rootVc 时返回 null, 调用方自行降级。
 */
internal fun topMostViewController(): UIViewController? {
    val window = findKeyWindow() ?: return null
    val root = window.rootViewController ?: return null
    var top = root
    while (top.presentedViewController != null) {
        top = top.presentedViewController!!
    }
    return top
}

/**
 * 查找当前 active 的 key [UIWindow]。
 *
 * iOS 13+ `UIApplication.keyWindow` deprecated (多 scene), 但多数场景仍可用;
 * 若为 nil 返回 null 让调用方降级 (遍历 connectedScenes 复杂度高,
 * 且 Kotlin/Native UIScene API 映射不全, 此处简化)。
 */
@Suppress("DEPRECATION")
internal fun findKeyWindow(): UIWindow? {
    return UIApplication.sharedApplication().keyWindow
}
