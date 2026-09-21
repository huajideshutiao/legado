@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.file

import io.legado.app.help.topMostViewController
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import io.legado.app.utils.File

/**
 * iOS 端文件选择器桥接: 用 [UIDocumentPickerViewController] 选文件, suspend 封装回调。
 *
 * # 模式参考
 * - [io.legado.app.help.toast.IosToaster]: `dispatch_async(dispatch_get_main_queue())` 主线程切换 +
 *   [topMostViewController] present 弹窗 (共用 [IosPlatformOps.ios.kt] 下沉的顶层函数);
 * - [io.legado.app.help.http.OkHttpUtils.kt] `KmpCall.await`: `suspendCancellableCoroutine`
 *   回调转 suspend 模式。
 *
 * # 设计要点
 * - **主线程**: UIKit present 必须主线程, 用 `dispatch_async(dispatch_get_main_queue())` 切换;
 * - **Import 模式**: 选中的文件会被系统复制到沙盒临时目录, URL 可直接读不需 security-scoped access;
 * - **delegate 强引用**: `picker.delegate` 是 weak 引用, 用 [activeDelegates] 持有强引用防止
 *   Kotlin/Native tracing GC 回收 delegate 导致回调失效 (自引用不构成 GC root, 需外部持有);
 * - **取消**: 协程取消时调 `picker.dismissViewControllerAnimated` 关闭弹窗, delegate 回调 resume 为 no-op。
 *
 * # macOS 编译验证 (Windows 无法编译 iOS target)
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ```
 */
private val activeDelegates = mutableListOf<Any>()

/**
 * 弹 [UIDocumentPickerViewController] 选文件, 返回选中的 [NSURL] 列表。
 *
 * @param contentTypes UTI 字符串列表 (如 "public.json", "public.text"), 传给
 *   `UIDocumentPickerViewController(documentTypes:in:)` 过滤可选文件类型;
 * @param allowsMultiple 是否允许多选 (默认 false)
 * @return 选中的 [NSURL] 列表, 用户取消返回 null
 */
suspend fun pickDocuments(
    contentTypes: List<String>,
    allowsMultiple: Boolean = false,
): List<NSURL>? = suspendCancellableCoroutine { block ->
    // 持有 picker 引用, 供协程取消时 dismiss (dispatch_async 异步, picker 在主线程块内才创建)
    var pickerRef: UIDocumentPickerViewController? = null
    dispatch_async(dispatch_get_main_queue()) {
        val vc = topMostViewController()
        if (vc == null) {
            // 拿不到 root vc: 直接返回 null (调用方自行降级处理)
            block.resume(null)
            return@dispatch_async
        }
        @Suppress("DEPRECATION")
        val picker = UIDocumentPickerViewController(
            documentTypes = contentTypes,
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.allowsMultipleSelection = allowsMultiple
        pickerRef = picker
        // holder 让 delegate 回调内引用自身以从 activeDelegates 移除 (避免自引用防 GC 失效)
        val holder = mutableListOf<Any?>()
        val delegate = DocumentPickerDelegate(
            onPick = { urls ->
                holder.firstOrNull()?.let { activeDelegates.remove(it) }
                block.resume(urls)
            },
            onCancel = {
                holder.firstOrNull()?.let { activeDelegates.remove(it) }
                block.resume(null)
            },
        )
        holder.add(delegate)
        activeDelegates.add(delegate)
        picker.delegate = delegate
        vc.presentViewController(picker, animated = true, completion = null)
    }
    // 协程取消: dismiss 已 present 的 picker (pickerRef 可能为 null, 表示主线程块尚未执行)
    block.invokeOnCancellation {
        dispatch_async(dispatch_get_main_queue()) {
            pickerRef?.dismissViewControllerAnimated(true, completion = null)
        }
    }
}

/**
 * 读取选中文件的内容为 [ByteArray]。
 *
 * 用 [NSData.dataWithContentsOfURL] 读取 (Import 模式下文件已复制到沙盒临时目录, 可直接读);
 * NSData → ByteArray 用 `bytes.readBytes` 拷贝 (模式同 [IosImageOps.toByteArray])。
 *
 * @param url 从 [pickDocuments] 返回的 [NSURL]
 * @return 文件字节内容, 读取失败返回 null
 */
fun pickDocumentContent(url: NSURL): ByteArray? {
    val nsData = NSData.dataWithContentsOfURL(url) ?: return null
    val size = nsData.length.toInt()
    if (size == 0) return ByteArray(0)
    return nsData.bytes?.readBytes(size) ?: ByteArray(0)
}

/**
 * 弹 [UIDocumentPickerViewController] 选目录 (备份路径等场景), 返回选中的 [NSURL]。
 *
 * 与 [pickDocuments] 区别:
 * - **模式**: 用 [UIDocumentPickerMode.Open] (Import 模式不支持目录);
 * - **UTI**: "public.folder" 过滤可选目录 (kUTTypeFolder);
 * - **单选**: 目录选择不允许多选 (allowsMultipleSelection = false);
 * - **Security-Scoped**: Open 模式返回的 [NSURL] 是 security-scoped URL,
 *   调用方访问前需 `startAccessingSecurityScopedResource()`, 用完 `stopAccessingSecurityScopedResource()`;
 *   持久化访问 (app 重启后) 需 `bookmarkData()` 保存 bookmark, 下次 `startAccessingSecurityScopedBookmark` 恢复。
 *
 * 对照 app 端 [io.legado.app.ui.file.HandleFileDialog.selectDocTree]
 * (`ActivityResultContracts.OpenDocumentTree`) + `takePersistableUriPermission`。
 *
 * @return 选中的目录 [NSURL], 用户取消返回 null
 */
suspend fun pickDirectory(): NSURL? = suspendCancellableCoroutine { block ->
    var pickerRef: UIDocumentPickerViewController? = null
    dispatch_async(dispatch_get_main_queue()) {
        val vc = topMostViewController()
        if (vc == null) {
            block.resume(null)
            return@dispatch_async
        }
        @Suppress("DEPRECATION")
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.folder"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen,
        )
        picker.allowsMultipleSelection = false
        pickerRef = picker
        val holder = mutableListOf<Any?>()
        val delegate = DocumentPickerDelegate(
            onPick = { urls ->
                holder.firstOrNull()?.let { activeDelegates.remove(it) }
                block.resume(urls.firstOrNull())
            },
            onCancel = {
                holder.firstOrNull()?.let { activeDelegates.remove(it) }
                block.resume(null)
            },
        )
        holder.add(delegate)
        activeDelegates.add(delegate)
        picker.delegate = delegate
        vc.presentViewController(picker, animated = true, completion = null)
    }
    block.invokeOnCancellation {
        dispatch_async(dispatch_get_main_queue()) {
            pickerRef?.dismissViewControllerAnimated(true, completion = null)
        }
    }
}

/**
 * 导出字节到用户选择的位置 (对照 app 端 [HandleFileContract.EXPORT] / desktop `FileDialog(SAVE)`)。
 *
 * 两步: 先把 [bytes] 写进沙盒临时目录 (`NSTemporaryDirectory()/legado-export/<fileName>`),
 * 再弹 `UIDocumentPickerViewController(forExportingURLs:)` 让用户挑目标位置 (iOS "文件" app,
 * 含 iCloud Drive / 本机 / 第三方网盘)。系统把临时文件**拷贝**到目标位置, 临时文件仍归我们管,
 * 弹窗关闭后即刻删除。
 *
 * 与 [pickDocuments] 的结构差异:
 * - **模式**: `forExportingURLs:` 专用构造器 (非 documentTypes/inMode), 无需声明 UTI;
 * - **回调语义**: 用户完成保存时 `didPickDocumentsAtURLs` 带回目标 URL (视为成功),
 *   取消时 `documentPickerDidCancel` (视为失败, 调用方降级到剪贴板)。
 *
 * @param fileName 建议文件名 (含扩展名, 如 "exportTxtTocRule.json"; 与 app 端 FileData.fileName 一致)
 * @param bytes 待写出的字节内容
 * @return true=用户完成保存; false=用户取消 / 临时文件写入失败 / 拿不到 root vc
 */
suspend fun exportFile(fileName: String, bytes: ByteArray): Boolean {
    // 1. 写沙盒临时文件 (kotlin.io.File 走 POSIX fs, 与 nativeMain FileDownloader 同模式)
    val tmpDir = File(NSTemporaryDirectory().trimEnd('/') + "/legado-export")
    val tmpFile = File(tmpDir.path + "/" + fileName)
    val prepared = runCatching {
        tmpDir.mkdirs()
        tmpFile.writeBytes(bytes)
    }.isSuccess
    if (!prepared) return false

    // 2. 弹系统保存器 (结构照抄 pickDocuments: 主线程 present + activeDelegates 强引用 + 取消 dismiss)
    val saved = suspendCancellableCoroutine<Boolean> { block ->
        var pickerRef: UIDocumentPickerViewController? = null
        dispatch_async(dispatch_get_main_queue()) {
            val vc = topMostViewController()
            if (vc == null) {
                block.resume(false)
                return@dispatch_async
            }
            val srcUrl = NSURL.fileURLWithPath(tmpFile.path)
            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(srcUrl))
            pickerRef = picker
            val holder = mutableListOf<Any?>()
            val delegate = DocumentPickerDelegate(
                // 导出完成: 系统回传目标 URL, 内容不再关心, 只取"成功"语义
                onPick = {
                    holder.firstOrNull()?.let { activeDelegates.remove(it) }
                    block.resume(true)
                },
                onCancel = {
                    holder.firstOrNull()?.let { activeDelegates.remove(it) }
                    block.resume(false)
                },
            )
            holder.add(delegate)
            activeDelegates.add(delegate)
            picker.delegate = delegate
            vc.presentViewController(picker, animated = true, completion = null)
        }
        block.invokeOnCancellation {
            dispatch_async(dispatch_get_main_queue()) {
                pickerRef?.dismissViewControllerAnimated(true, completion = null)
            }
        }
    }

    // 3. 清临时文件 (系统已把内容拷到目标位置, 临时副本无保留价值; 删除失败不影响导出结果)
    runCatching { tmpFile.delete() }
    return saved
}

/**
 * [UIDocumentPickerDelegate] 实现: 桥接 UIKit 回调到 suspend continuation。
 *
 * Kotlin/Native 实现 ObjC 协议用 `NSObject() + Protocol` 形式,
 * 方法签名与 [UIDocumentPickerDelegateProtocol] 一致 (K/N 自动映射 ObjC 方法名)。
 */
private class DocumentPickerDelegate(
    private val onPick: (List<NSURL>) -> Unit,
    private val onCancel: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        @Suppress("UNCHECKED_CAST")
        onPick(didPickDocumentsAtURLs as List<NSURL>)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancel()
    }
}
