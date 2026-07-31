@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui

import io.legado.app.ui.root.BrowserService
import io.legado.app.ui.root.ExternalRequestService
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.FilePickerService
import io.legado.app.ui.root.KeyboardController
import io.legado.app.ui.root.LaunchRequest
import io.legado.app.ui.root.MediaService
import io.legado.app.ui.root.NotificationService
import io.legado.app.ui.root.OrientationPolicy
import io.legado.app.ui.root.PermissionService
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.PlatformServices
import io.legado.app.ui.root.ShareService
import io.legado.app.ui.root.SoftInputPolicy
import io.legado.app.ui.root.SystemBarsPolicy
import io.legado.app.ui.root.WindowController
import platform.UIKit.UIApplication
import platform.UIKit.sharedApplication

// iOS 平台服务聚合: 各子能力 no-op stub, 真实实现待后续接入 UIKit
object IosPlatformServices : PlatformServices {
    override val capabilities: PlatformCapabilities = IosPlatformCapabilities
    override val files: FilePickerService = IosFilePickerService
    override val sharing: ShareService = IosShareService
    override val browser: BrowserService = IosBrowserService
    override val permissions: PermissionService = IosPermissionService
    override val window: WindowController = IosWindowController
    override val keyboard: KeyboardController = IosKeyboardController
    override val media: MediaService = IosMediaService
    override val notifications: NotificationService = IosNotificationService
    override val externalRequests: ExternalRequestService = IosExternalRequestService
}

private object IosFilePickerService : FilePickerService {
    // TODO: 接入 UIDocumentPickerViewController
    override fun pickFile(filter: FileFilter): String? = null
    override fun pickFiles(filter: FileFilter): List<String> = emptyList()
    override fun saveFile(suggestedName: String, defaultDir: String?): String? = null
}

private object IosShareService : ShareService {
    // TODO: 接入 UIActivityViewController
    override fun shareText(text: String) = Unit
    override fun shareFile(filePath: String, mimeType: String) = Unit
}

private object IosBrowserService : BrowserService {
    // TODO: 接入 SFSafariViewController / UIApplication.openURL
    override fun openUrl(url: String) = Unit
    override fun openUrlInApp(url: String) = Unit
}

private object IosPermissionService : PermissionService {
    // TODO: 接入 Info.plist 权限申请
    override fun hasPermission(permission: String): Boolean = false
    override fun requestPermission(permission: String): Boolean = false
}

// UIKit 窗口控制: KeepScreenOn 经 UIApplication.isIdleTimerDisabled (对照 Android FLAG_KEEP_SCREEN_ON);
// 状态栏/全屏现代 API 在 VC 层 (prefersStatusBarHidden), 方向 iOS 不支持编程强制, 均为 no-op
private object IosWindowController : WindowController {
    override fun setFullscreen(enabled: Boolean) {
        // iOS 全屏等价于隐藏状态栏, 由 setSystemBars 统一承担 (此处不重复)
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        // true=禁止熄屏; 阅读/音频/视频路由保持常亮
        UIApplication.sharedApplication().isIdleTimerDisabled = enabled
    }

    override fun setOrientation(policy: OrientationPolicy) {
        // iOS 不支持编程强制方向 (UIDevice.setValue 已弃用且受 Info.plist 限制), no-op
    }

    override fun setSystemBars(policy: SystemBarsPolicy) {
        // 状态栏显隐现代 API 由 VC prefersStatusBarHidden 决定, UIApplication 级仅遗留 deprecated 接口, no-op
    }
}

private object IosKeyboardController : KeyboardController {
    // TODO: 接入 UIResponder 软输入控制
    override fun hideSoftInput() = Unit
    override fun showSoftInput() = Unit
    override fun setSoftInputPolicy(policy: SoftInputPolicy) = Unit
}

private object IosMediaService : MediaService {
    // TODO: 接入 AVPlayer
    override fun playMedia(url: String, headers: Map<String, String>) = Unit
    override fun pauseMedia() = Unit
    override fun stopMedia() = Unit
}

private object IosNotificationService : NotificationService {
    // TODO: 接入 UNUserNotificationCenter
    override fun notify(id: Int, title: String, content: String) = Unit
    override fun cancelNotification(id: Int) = Unit
}

private object IosExternalRequestService : ExternalRequestService {
    // TODO: 接入 iOS URL Scheme / Universal Link 解析
    override fun parseLaunchRequest(request: Any): LaunchRequest? = null
    override fun handleLaunchRequest(request: LaunchRequest): Boolean = false
}
