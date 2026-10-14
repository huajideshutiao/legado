@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

// iOS 平台 openURL / 剪贴板 / 顶层 vc 查找 工具 (对照 desktop java.awt.Desktop / Toolkit.systemClipboard)

/** 用系统浏览器打开外部链接 (对照 desktop browseUrl)。 */
fun openURL(url: String) {
    NSURL.URLWithString(url)?.let { UIApplication.sharedApplication().openURL(it) }
}

/**
 * 尝试用指定 URL scheme 打开系统 App, 返回是否已交给系统。
 *
 * 用带 completionHandler 的异步版而**不是**返回布尔值的同步版: 同步版在部分系统版本上
 * 恒返回 true (仅表示“已发起”), 拿不到“系统是否真的接了”的结果, 失败回落就成不了事。
 * 异步版回传的 accepted 才是真结果。
 *
 * 不用 `canOpenURL:` 先探测: iOS 9 起它对未在 Info.plist 白名单里登记的自定义 scheme
 * 恒返回 false, 会把“能打开”误判成“打不开”。
 */
fun openURLIfPossible(url: String, onResult: (Boolean) -> Unit) {
    val nsUrl = NSURL.URLWithString(url) ?: run {
        onResult(false)
        return
    }
    UIApplication.sharedApplication().openURL(
        url = nsUrl,
        options = emptyMap<Any?, Any?>(),
        completionHandler = { accepted -> onResult(accepted) },
    )
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
fun topMostViewController(): UIViewController? {
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
 * iOS 13+ `UIApplication.keyWindow` deprecated (多 scene); 本应用 `UIApplicationSupportsMultipleScenes`
 * = false (单场景), 该属性仍指向唯一窗口, 拿不到时返回 null 让调用方降级。
 *
 * 这里不遍历 `connectedScenes`: 单场景下两者等价, 而 keyWindow 只是一次属性读取。
 * (UIWindowScene 相关 API 在 Kotlin/Native 侧是齐的 —— 需要场景对象时直接遍历
 * connectedScenes 取前台场景即可, 见视频全屏的方向请求。)
 */
@Suppress("DEPRECATION")
fun findKeyWindow(): UIWindow? {
    return UIApplication.sharedApplication().keyWindow
}
