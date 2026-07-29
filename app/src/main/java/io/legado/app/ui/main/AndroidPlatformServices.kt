package io.legado.app.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.legado.app.constant.AppLog
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
import io.legado.app.utils.ActivityResultLauncherAwait
import io.legado.app.utils.fullScreen
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File

/**
 * Android 端 PlatformServices 实现：聚合 10 项平台能力，
 * 经 PlatformServiceProviders.register 注册后供 shared LegadoApp 使用。
 *
 * 构造时持有 MainActivity 引用，用于 SAF launcher / 权限请求 / 窗口控制等需 Activity 上下文的能力。
 * SAF launcher 由 MainActivity 在 STARTED 前注册后传入, files 经 runBlocking 阻塞等待回调。
 */
class AndroidPlatformServices(
    private val activity: MainActivity,
    private val capabilitiesImpl: AndroidPlatformCapabilities,
    private val openDocumentPicker: ActivityResultLauncherAwait<Array<String>, Uri?>,
    private val openDocumentsPicker: ActivityResultLauncherAwait<Array<String>, List<Uri>>,
    private val createDocumentPicker: ActivityResultLauncherAwait<String, Uri?>,
    private val openDocumentTreePicker: ActivityResultLauncherAwait<Uri?, Uri?>,
) : PlatformServices {

    override val capabilities: PlatformCapabilities
        get() = capabilitiesImpl

    override val files: FilePickerService = AndroidFilePickerService(
        openDocumentPicker, openDocumentsPicker, createDocumentPicker, openDocumentTreePicker,
    )

    override val sharing: ShareService = AndroidShareService(activity)

    override val browser: BrowserService = AndroidBrowserService(activity)

    override val permissions: PermissionService = AndroidPermissionService(activity)

    override val window: WindowController = AndroidWindowController(activity)

    override val keyboard: KeyboardController = AndroidKeyboardController(activity)

    override val media: MediaService = AndroidMediaService()

    override val notifications: NotificationService = AndroidNotificationService()

    override val externalRequests: ExternalRequestService = AndroidExternalRequestService()
}

// SAF 文件选择/保存桥接: launcher 由 MainActivity 在 STARTED 前注册传入,
// 调用方均在 IoDispatcher 协程内, runBlocking 阻塞 IO 线程等待主线程 Activity Result 回调
private class AndroidFilePickerService(
    private val openDocumentPicker: ActivityResultLauncherAwait<Array<String>, Uri?>,
    private val openDocumentsPicker: ActivityResultLauncherAwait<Array<String>, List<Uri>>,
    private val createDocumentPicker: ActivityResultLauncherAwait<String, Uri?>,
    private val openDocumentTreePicker: ActivityResultLauncherAwait<Uri?, Uri?>,
) : FilePickerService {

    override fun pickFile(filter: FileFilter): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentPicker.launch(filter.toMimeTypes()) }?.toString()
    }

    override fun pickFiles(filter: FileFilter): List<String> = runBlocking {
        withContext(Dispatchers.Main) { openDocumentsPicker.launch(filter.toMimeTypes()) }
            .map { it.toString() }
    }

    override fun saveFile(suggestedName: String, defaultDir: String?): String? = runBlocking {
        // CreateDocument 由系统决定保存位置, defaultDir 不支持指定
        withContext(Dispatchers.Main) { createDocumentPicker.launch(suggestedName) }?.toString()
    }

    // 选目录: OpenDocumentTree (对照 app 端 HandleFileDialog.selectDocTree),
    // 选中后 takePersistableUriPermission 保证重启后仍可访问
    override fun pickDirectory(): String? = runBlocking {
        withContext(Dispatchers.Main) { openDocumentTreePicker.launch(null) }?.let { uri ->
            val modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                appCtx.contentResolver.takePersistableUriPermission(uri, modeFlags)
            }.onFailure {
                AppLog.put("AndroidFilePickerService.pickDirectory takePersistableUriPermission 失败: ${it.localizedMessage}")
            }
            uri.toString()
        }
    }

    // OpenDocument 仅接受 MIME 过滤, 缺省回落 */*
    private fun FileFilter.toMimeTypes(): Array<String> =
        if (mimeTypes.isNotEmpty()) mimeTypes.toTypedArray() else arrayOf("*/*")
}

private class AndroidShareService(
    private val activity: MainActivity,
) : ShareService {
    override fun shareText(text: String) {
        activity.share(text)
    }

    override fun shareFile(filePath: String, mimeType: String) {
        val uri = filePath.toUri()
        if (uri.scheme == "content" || uri.scheme == "file") {
            activity.share(uri, mimeType)
        } else {
            // 纯路径走 FileProvider 转换 (对照 Context.share(File) 扩展)
            kotlin.runCatching { activity.share(File(filePath), mimeType) }
                .onFailure { AppLog.put("AndroidShareService.shareFile 失败: ${it.localizedMessage}") }
        }
    }
}

private class AndroidBrowserService(
    private val activity: MainActivity,
) : BrowserService {
    override fun openUrl(url: String) {
        activity.openUrl(url)
    }

    // 简单实现: 直接走系统浏览器, 后续可接入内置 WebView 路由
    override fun openUrlInApp(url: String) {
        activity.openUrl(url)
    }
}

private class AndroidPermissionService(
    private val activity: MainActivity,
) : PermissionService {
    override fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED

    // 异步请求, 同步返回仅表示是否成功发起请求
    override fun requestPermission(permission: String): Boolean {
        return kotlin.runCatching {
            ActivityCompat.requestPermissions(activity, arrayOf(permission), 0)
            true
        }.getOrElse {
            AppLog.put("AndroidPermissionService.requestPermission 失败: ${it.localizedMessage}")
            false
        }
    }
}

private class AndroidWindowController(
    private val activity: MainActivity,
) : WindowController {
    override fun setFullscreen(enabled: Boolean) {
        if (enabled) {
            activity.fullScreen()
        } else {
            // 退出全屏: 清除 LAYOUT_FULLSCREEN, 恢复系统栏默认布局
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        activity.keepScreenOn(enabled)
    }

    override fun setOrientation(policy: OrientationPolicy) {
        activity.requestedOrientation = when (policy) {
            OrientationPolicy.Unspecified -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationPolicy.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPolicy.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationPolicy.Sensor -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
    }

    override fun setSystemBars(policy: SystemBarsPolicy) {
        val controller =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        when (policy) {
            SystemBarsPolicy.Default, SystemBarsPolicy.Visible -> {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }

            SystemBarsPolicy.Hidden, SystemBarsPolicy.Immersive -> {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

private class AndroidKeyboardController(
    private val activity: MainActivity,
) : KeyboardController {
    private val imm
        get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    override fun hideSoftInput() {
        val view = activity.currentFocus ?: activity.window.decorView
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun showSoftInput() {
        val view = activity.currentFocus ?: activity.window.decorView
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun setSoftInputPolicy(policy: SoftInputPolicy) {
        val mode = when (policy) {
            SoftInputPolicy.Default -> WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            SoftInputPolicy.Resize -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            SoftInputPolicy.Pan -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            SoftInputPolicy.Hidden -> WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        activity.window.setSoftInputMode(mode)
    }
}

// 简单实现: 暂不接入 MediaPlayer, 后续可委托 AudioPlay/ExoPlayer
private class AndroidMediaService : MediaService {
    override fun playMedia(url: String, headers: Map<String, String>) {
        AppLog.put("AndroidMediaService.playMedia: 暂未实现")
    }

    override fun pauseMedia() {
        AppLog.put("AndroidMediaService.pauseMedia: 暂未实现")
    }

    override fun stopMedia() {
        AppLog.put("AndroidMediaService.stopMedia: 暂未实现")
    }
}

private class AndroidNotificationService : NotificationService {
    override fun notify(id: Int, title: String, content: String) {
        kotlin.runCatching {
            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID_DEFAULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(id, notification)
        }.onFailure {
            AppLog.put("AndroidNotificationService.notify 失败: ${it.localizedMessage}")
        }
    }

    override fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    companion object {
        private const val CHANNEL_ID_DEFAULT = "platform_default"
    }
}

private class AndroidExternalRequestService : ExternalRequestService {
    // 解析 Intent 为 LaunchRequest: 委托给 [Intent.toLaunchRequest] (与 MainActivity 入口共用同一解析逻辑)
    override fun parseLaunchRequest(request: Any): LaunchRequest? {
        val intent = request as? Intent ?: return null
        return intent.toLaunchRequest()
    }

    // 实际处理由 LegadoApp.handleLaunchRequest 经 LaunchRequestBus 消费, 此处仅返回 false
    override fun handleLaunchRequest(request: LaunchRequest): Boolean {
        return false
    }
}

/**
 * 解析外部 Intent 为 [LaunchRequest]: 覆盖 VIEW(deep link / 文件关联) 与 PROCESS_TEXT / SEND。
 *
 * scheme 区分:
 * - content/file/app → [LaunchRequest.ImportFile] (走导入书籍流程)
 * - legado/yuedu/其他 → [LaunchRequest.DeepLink] (legado 系由 [LegadoDeepLinkHandler] 进一步接管导入对话框)
 */
fun Intent.toLaunchRequest(): LaunchRequest? {
    // 通知/外部入口携带 route extra: 转为 NavigateTo 请求
    getStringExtra("route")?.let { return LaunchRequest.NavigateTo(it) }
    return when (action) {
        Intent.ACTION_VIEW -> dataString?.let { url ->
            when (data?.scheme) {
                "content", "file", "app" -> LaunchRequest.ImportFile(url)
                else -> LaunchRequest.DeepLink(url)
            }
        }

        Intent.ACTION_PROCESS_TEXT ->
            getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let { LaunchRequest.ProcessText(it) }

        Intent.ACTION_SEND ->
            getStringExtra(Intent.EXTRA_TEXT)?.let { LaunchRequest.ProcessText(it) }

        else -> null
    }
}
