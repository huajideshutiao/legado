package io.legado.app.ui.book.info

import io.legado.app.data.entities.Book
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.root.ImportConfirmAction
import io.legado.app.ui.root.ImportStageHost
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.widget.dialog.WaitDialogHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 下载项对话框与压缩包选条目的文案 (各端用自己的字符串表构造, 共享层不猜资源键)。
 */
data class WebFileFlowTexts(
    val selectDownloadItem: String,
    val selectArchiveEntry: String,
    val unsupportedTitle: String,
    val unsupportedMessage: (String) -> String,
    val openWithSystem: String,
    val unsupportedArchiveEntry: String,
)

/**
 * webFile 下载导入流程的共享实现 (对照 app 端 `showWebFileDownloadAlert` +
 * `showDecompressFileImportAlert` + 不支持格式的 `open_fun` 分支)。
 *
 * 之前四端各写一份同样的分支与各自的对话框 (安卓 `activity.selector`、桌面
 * `DesktopDialogRequest.SelectList`、iOS/鸿蒙 `presentSelectList` / `UIAlertController`): 框挂在
 * 各端宿主上, 宿主重建就消失, 而等待方 (页面请求) 还在飞 —— 请求再也拿不到回执。现在选择与确认
 * 都经 [ImportStageHost] 挂起到真回执, 框由共享 Compose 层从页面模型读阶段来画; 平台只剩
 * "下载"/"取压缩包条目"/"导入"/"交系统打开"四个叶子 (仍由各端能力实现)。
 *
 * @param openDownloadedFile 不支持格式时把已下载文件交系统打开 (各端叶子)
 */
suspend fun runWebFileReadFlow(
    book: Book,
    scope: CoroutineScope,
    stages: ImportStageHost,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
    webFiles: List<FileBook.WebFile>,
    texts: WebFileFlowTexts,
    openDownloadedFile: (String) -> Unit,
    onSuccess: ((Book) -> Unit)?,
) {
    val platform = PlatformCapabilityProviders.get()
    val index = stages.choose(
        title = texts.selectDownloadItem,
        items = webFiles.map { it.name },
    ) ?: return
    val webFile = webFiles.getOrNull(index) ?: return
    when {
        webFile.isSupported -> platform.importWebFile(
            book = book,
            webFile = webFile,
            scope = scope,
            onWaitDialog = onWaitDialog,
            success = { onSuccess?.invoke(it) },
        )

        webFile.isSupportDecompress -> {
            val path = downloadWebFile(book, webFile, scope, onWaitDialog) ?: return
            val names = archiveEntryNames(path, scope) ?: return
            when {
                names.isEmpty() -> Toasters.get().toast(texts.unsupportedArchiveEntry)
                // 只有一个条目: 没有可选项, 直接导入 (对照原版 size == 1 分支)
                names.size == 1 -> platform.importBookFromArchive(
                    archiveFilePath = path,
                    archiveEntryName = names[0],
                    book = book,
                    scope = scope,
                    onWaitDialog = onWaitDialog,
                    success = { onSuccess?.invoke(it) },
                )

                else -> {
                    val entry = stages.choose(
                        title = texts.selectArchiveEntry,
                        items = names,
                    ) ?: return
                    val name = names.getOrNull(entry) ?: return
                    platform.importBookFromArchive(
                        archiveFilePath = path,
                        archiveEntryName = name,
                        book = book,
                        scope = scope,
                        onWaitDialog = onWaitDialog,
                        success = { onSuccess?.invoke(it) },
                    )
                }
            }
        }

        else -> {
            val action = stages.confirm(
                title = texts.unsupportedTitle,
                message = texts.unsupportedMessage(webFile.name),
                neutralText = texts.openWithSystem,
            )
            if (action != ImportConfirmAction.Neutral) return
            val path = downloadWebFile(book, webFile, scope, onWaitDialog) ?: return
            withContext(mainDispatcher) { openDownloadedFile(path) }
        }
    }
}

/**
 * 下载叶子适配: 平台下载是回调式 (`success: (String) -> Unit`) 且失败只记日志、不回调,
 * 故这里按请求 scope 挂起等这一次成功回执; 请求撤销时随 scope 取消, 不再等一个不会到的回调。
 */
private suspend fun downloadWebFile(
    book: Book,
    webFile: FileBook.WebFile,
    scope: CoroutineScope,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
): String? = suspendCancellableCoroutine { cont ->
    PlatformCapabilityProviders.get().downloadWebFile(
        book = book,
        webFile = webFile,
        scope = scope,
        onWaitDialog = onWaitDialog,
    ) { path ->
        if (cont.isActive) cont.resume(path)
    }
}

/** 压缩包条目列举适配 (同上: 回调式叶子, 挂起等这一次结果)。 */
private suspend fun archiveEntryNames(
    archiveFilePath: String,
    scope: CoroutineScope,
): List<String>? = suspendCancellableCoroutine { cont ->
    PlatformCapabilityProviders.get().getArchiveFilesName(archiveFilePath, scope) { names ->
        if (cont.isActive) cont.resume(names)
    }
}