@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.image

import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.topMostViewController
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * iOS 端图片选择器 (PHPickerViewController) 桥接, 供阅读背景图选择使用。
 *
 * 用 suspendCancellableCoroutine 封装 PHPickerViewController 回调;
 * 选中图片经 NSItemProvider.loadFileRepresentation 取临时文件 URL,
 * 拷贝到沙盒 Documents/bg/ 持久化后 resume 返回持久化 NSURL。
 *
 * 临时文件 URL 仅在 loadFileRepresentation completion 执行期间有效,
 * 系统在 completion 返回后即删除, 故必须在此期间同步拷贝到持久化目录。
 *
 * delegate 持有: PHPickerViewController.delegate 为 weak 引用,
 * 由 [PickerHolder] 单例强持有 (picker 为模态, 同一时刻仅一次 pick 操作)。
 *
 * 线程: present 必须主线程, dispatch_async 切主线程;
 * loadFileRepresentation completion 在内部队列回调 (resume 线程安全)。
 *
 * 模式参考 Toaster.ios.kt (主线程切换 + topMostViewController) /
 * OkHttpUtils.await (suspendCancellableCoroutine) /
 * IosSystemTtsEngine (delegate 持有)。
 *
 * macOS 编译验证 (Windows 无法编译 iOS target):
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ```
 */
suspend fun pickImage(): NSURL? = suspendCancellableCoroutine { cont ->
    // 重入保护: 已有 pick 操作进行中时拒绝 (picker 模态, 正常不会触发)
    if (PickerHolder.delegate != null) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    val delegate = PickerDelegate(cont)
    PickerHolder.delegate = delegate  // picker.delegate 是 weak, 需强持有避免回调前被回收
    dispatch_async(dispatch_get_main_queue()) {
        val rootVc = topMostViewController()
        if (rootVc == null) {
            PickerHolder.delegate = null
            if (cont.isActive) cont.resume(null)
            return@dispatch_async
        }
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)
        picker.delegate = delegate
        rootVc.presentViewController(picker, animated = true, completion = null)
    }
}

/** 强持有当前 pickImage delegate (PHPickerViewController.delegate 为 weak)。 */
private object PickerHolder {
    var delegate: PickerDelegate? = null
}

/**
 * PHPickerViewController delegate: 回调后 loadFileRepresentation 取临时 URL,
 * 拷贝到持久化目录, resume 返回持久化 NSURL。
 */
private class PickerDelegate(
    private val cont: CancellableContinuation<NSURL?>,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        PickerHolder.delegate = null
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
        if (provider == null || !provider.hasItemConformingToTypeIdentifier("public.image")) {
            // 用户取消 (空 results) 或 item 不支持图片类型
            if (cont.isActive) cont.resume(null)
            return
        }
        // 临时 URL 仅在 completion 执行期间有效, 必须在此期间同步拷贝
        provider.loadFileRepresentationForTypeIdentifier("public.image") { url, _ ->
            if (!cont.isActive) return@loadFileRepresentationForTypeIdentifier
            if (url == null) {
                cont.resume(null)
                return@loadFileRepresentationForTypeIdentifier
            }
            cont.resume(copyToPersistentDir(url))
        }
    }
}

/**
 * 拷贝临时文件到沙盒 Documents/bg/ 下, 返回持久化 NSURL (失败返回 nil)。
 * 文件名用 NSUUID 避免重名 (copyItemAtPath 目标已存在会失败);
 * 目录选 bg/ 与 [io.legado.app.help.config.ReadBookConfigShared.clearBgAndCache] 清理逻辑一致。
 */
private fun copyToPersistentDir(tempUrl: NSURL): NSURL? {
    val tempPath = tempUrl.path ?: return null
    val filesDir = AppFilesDirs.get().filesDir
    val bgDir = "$filesDir/bg"
    val fm = NSFileManager.defaultManager
    if (!fm.fileExistsAtPath(bgDir)) {
        fm.createDirectoryAtPath(
            path = bgDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
    val ext = tempUrl.pathExtension?.ifBlank { null } ?: "png"
    val dest = "$bgDir/${NSUUID().UUIDString}.$ext"
    val ok = fm.copyItemAtPath(tempPath, dest, null)
    return if (ok) NSURL.fileURLWithPath(dest) else null
}


