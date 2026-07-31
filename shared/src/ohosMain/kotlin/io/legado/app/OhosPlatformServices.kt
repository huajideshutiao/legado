package io.legado.app

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.ui.OhosPlatformCapabilities
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

/**
 * 鸿蒙端 [PlatformServices] 实现: 10 项能力 no-op stub。
 * 经 PlatformServiceProviders.register 注册后供 shared LegadoApp 使用。
 * TODO: 各能力逐步接入 ArkTS napi 桥接真实实现 (对照 AndroidPlatformServices)。
 */
object OhosPlatformServices : PlatformServices {

    override val capabilities: PlatformCapabilities
        get() = OhosPlatformCapabilities

    // TODO: 桥接已有 OhosFilePicker (help/file/OhosFilePicker.ohos.kt) 的 pickDocuments/saveDocument
    override val files: FilePickerService = object : FilePickerService {
        override fun pickFile(filter: FileFilter): String? = null
        override fun pickFiles(filter: FileFilter): List<String> = emptyList()
        override fun saveFile(suggestedName: String, defaultDir: String?): String? = null
    }

    override val sharing: ShareService = object : ShareService {
        override fun shareText(text: String) = Unit
        override fun shareFile(filePath: String, mimeType: String) = Unit
    }

    override val browser: BrowserService = object : BrowserService {
        override fun openUrl(url: String) = Unit
        override fun openUrlInApp(url: String) = Unit
    }

    override val permissions: PermissionService = object : PermissionService {
        override fun hasPermission(permission: String): Boolean = false
        override fun requestPermission(permission: String): Boolean = false
    }

    // 窗口策略经 OhosNativeBridge tsfn 桥 dispatch 到 ArkTS @ohos.window (无 NDK C 接口);
    // 桥未就绪时降级 println (兼容 napi 未接入阶段)
    override val window: WindowController = object : WindowController {
        override fun setFullscreen(enabled: Boolean) {
            // window.setWindowLayoutFullScreen: 布局延伸到状态栏区域 (对照 Android immersive)
            OhosNativeBridge.setWindowFullScreenLayout(enabled)
        }

        override fun setKeepScreenOn(enabled: Boolean) {
            // window.setWindowKeepScreenOn (对照 Android FLAG_KEEP_SCREEN_ON)
            OhosNativeBridge.setWindowKeepScreenOn(enabled)
        }

        override fun setOrientation(policy: OrientationPolicy) {
            // window.setPreferredOrientation, 映射到 OHOS Orientation 枚举值
            val orientation = when (policy) {
                OrientationPolicy.Unspecified -> 0 // UNSPECIFIED
                OrientationPolicy.Portrait -> 1    // PORTRAIT
                OrientationPolicy.Landscape -> 2   // LANDSCAPE
                OrientationPolicy.Sensor -> 5      // AUTO_ROTATION
            }
            OhosNativeBridge.setWindowPreferredOrientation(orientation)
        }

        override fun setSystemBars(policy: SystemBarsPolicy) {
            // window.setWindowSystemBarEnable: Default/Visible 显示, Hidden/Immersive 隐藏
            val visible = policy == SystemBarsPolicy.Default || policy == SystemBarsPolicy.Visible
            OhosNativeBridge.setWindowSystemBarEnable(visible)
        }
    }

    override val keyboard: KeyboardController = object : KeyboardController {
        override fun hideSoftInput() = Unit
        override fun showSoftInput() = Unit
        override fun setSoftInputPolicy(policy: SoftInputPolicy) = Unit
    }

    override val media: MediaService = object : MediaService {
        override fun playMedia(url: String, headers: Map<String, String>) = Unit
        override fun pauseMedia() = Unit
        override fun stopMedia() = Unit
    }

    override val notifications: NotificationService = object : NotificationService {
        override fun notify(id: Int, title: String, content: String) = Unit
        override fun cancelNotification(id: Int) = Unit
    }

    override val externalRequests: ExternalRequestService = object : ExternalRequestService {
        override fun parseLaunchRequest(request: Any): LaunchRequest? = null
        override fun handleLaunchRequest(request: LaunchRequest): Boolean = false
    }
}
