package io.legado.app.ui.book.info

import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.toast.Toasters
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.root.ImportConfirmAction
import io.legado.app.ui.root.ImportStageHost
import io.legado.app.ui.root.ensureBookSaveDirSelected
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.widget.dialog.WaitDialogHandle
import io.legado.app.ui.widget.dialog.awaitWaitDialogDismissed
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * 共享流程的文案 (各端用自己的字符串表构造, 共享层不猜资源键)。
 */
data class WebFileFlowTexts(
    val noWebFile: String,
    val selectDownloadItem: String,
    val selectArchiveEntry: String,
    val unsupportedTitle: String,
    val unsupportedMessage: (String) -> String,
    val openWithSystem: String,
    val unsupportedArchiveEntry: String,
)

/**
 * webFile 下载导入链的共享实现 (对照 app 端 `showWebFileDownloadAlert` +
 * `showDecompressFileImportAlert` + 不支持格式的 `open_fun` 分支)。
 *
 * 三条硬性质:
 * 1. **全链挂本请求**: 下载/列举/导入/打开都在调用方这条 Job 上顺序执行, 不起兄弟任务后提前返回;
 *    请求真撤时整条链随撤 (底层 accessor 已有父门, 见 `importBookFromArchive` 的 parent 传参)。
 * 2. **真回执**: 下载/列举/导入直接复用现成挂起实现 ([WebFileImportShared]), 失败与取消以原异常
 *    抛回原等待者, 不再用"只成功回调"的适配层 (那种适配在失败时永不结束)。
 * 3. **阶段归请求**: 候选/条目/确认经 [ImportStageHost] 挂起等真回执; 关闭与返回也是一次真回执。
 *
 * 打开已下载文件走 [PlatformCapabilityProviders] 的当前实现 (不捕获发起时的 Activity),
 * 配置重建由新宿主承接, 重建间隙挂起等待 —— 见各端 `openLocalFileWithSystem`。
 *
 * @return 导入成功的书; 用户取消或没有可导入项时 null
 */
suspend fun runWebFileReadFlow(
    book: Book,
    stages: ImportStageHost,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
    texts: WebFileFlowTexts,
): Book? {
    // 对照 BaseReadViewModel.loadWebFile: 本地书无书源, 其余按 origin 查源
    val source = if (book.origin != BookType.localTag) {
        withContext(IoDispatcher) { AppDbProviders.get().bookSourceDao.getBookSource(book.origin) }
    } else null
    // 构建 webFile 列表的平台无关段已下沉 WebFileImportShared (同 BaseReadViewModel.loadWebFile)
    val webFiles = withContext(IoDispatcher) { WebFileImportShared.buildWebFiles(book, source) }
    if (webFiles.isEmpty()) {
        Toasters.get().toast(texts.noWebFile)
        return null
    }
    val index = stages.choose(texts.selectDownloadItem, webFiles.map { it.name }) ?: return null
    val webFile = webFiles.getOrNull(index) ?: return null
    return when {
        webFile.isSupported -> importWebFile(book, webFile, onWaitDialog)

        webFile.isSupportDecompress -> {
            val path = downloadWebFile(book, webFile, onWaitDialog) ?: return null
            val names = withContext(IoDispatcher) { WebFileImportShared.getArchiveFilesName(path) }
            when {
                // 后缀/内容不是可导入压缩包: 如实提示并收口 (原版同文案)
                names.isEmpty() -> {
                    Toasters.get().toast(texts.unsupportedArchiveEntry)
                    null
                }
                // 只有一个条目: 没有可选项, 直接导入 (对照原版 size == 1 分支)
                names.size == 1 -> importFromArchive(path, names[0], book, onWaitDialog)

                else -> {
                    val entry = stages.choose(texts.selectArchiveEntry, names) ?: return null
                    val name = names.getOrNull(entry) ?: return null
                    importFromArchive(path, name, book, onWaitDialog)
                }
            }
        }

        else -> {
            // 原版 alert 只有"打开方式"(neutral) 与"取消"(negative) 两个出口, 没有确定键:
            // 多给一个只关闭而不打开的确定键会改变用户可做的选择
            val action = stages.confirm(
                title = texts.unsupportedTitle,
                message = texts.unsupportedMessage(webFile.name),
                neutralText = texts.openWithSystem,
            )
            if (action != ImportConfirmAction.Neutral) return null
            val path = downloadWebFile(book, webFile, onWaitDialog) ?: return null
            withContext(mainDispatcher) {
                PlatformCapabilityProviders.get().openLocalFileWithSystem(path)
            }
            null
        }
    }
}

/**
 * 保存位置未设时挂起等用户选定, 选定即重试一次 (原先只发一个单向动作就收口)。
 *
 * 用户取消选目录 **是正常终结** (返回 null), 不是失败: 不再拿 [InvalidBooksDirException]
 * 冒充失败上抛; 选择器真实失败的原异常由 [ensureBookSaveDirSelected] 原样抛回原等待者。
 */
private suspend fun <T> withBookSaveDir(block: suspend () -> T): T? = try {
    block()
} catch (e: InvalidBooksDirException) {
    if (!ensureBookSaveDirSelected(currentCoroutineContext()[Job])) null else block()
}

/**
 * 本请求的等待框: 开框 → 执行 → 收框。
 *
 * 开框也在保护范围内 (跨 dispatcher 的返回可被 prompt cancellation 丢弃, 发布在 try 外就漏清理)。
 *
 * 收框分两种终态:
 * - **确要接下一 UI 步** ([awaitDismissWhen] 判定, 例如导入成功且调用方随后要跳阅读页):
 *   先保证收起已发布, 再等本框真的离开 —— 保持"关框 → 打开页面"的顺序;
 * - 失败 / 用户取消 (如选目录返回 null) / 协程取消: **只**发布收起, 不等界面重绘 ——
 *   隐藏窗口不出帧, 等它会把终态挂住。终态仍按原异常/原返回值交回。
 *
 * 收起发布放 [NonCancellable] (必须执行); 等待本身留在可取消上下文里, 本请求撤销即停止等待。
 */
private suspend fun <T> withWaitDialog(
    awaitDismissWhen: (T?) -> Boolean,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
    block: suspend () -> T,
): T {
    val handle = WaitDialogHandle()
    var result: T? = null
    var finished = false
    try {
        withContext(mainDispatcher) { onWaitDialog(true, handle) }
        val value = block()
        result = value
        finished = true
        return value
    } finally {
        // 先保证收起被发布 (取消也不跳过)
        withContext(NonCancellable + mainDispatcher) { onWaitDialog(false, handle) }
        if (finished && awaitDismissWhen(result)) {
            // 等框离开; hide 重复调用幂等, 本请求取消则不再继续等
            awaitWaitDialogDismissed(
                { withContext(mainDispatcher) { onWaitDialog(false, handle) } },
                handle,
            )
        }
    }
}

/**
 * 导入 webFile 并落目录 (对照 app 端 `BookInfoViewModel.importWebFile`)。
 *
 * 目录落库统一走 [BookChapterLoader.fetchFromSource] (与阅读页同一条链),
 * 不另写一份"取目录 + 落库"。
 */
private suspend fun importWebFile(
    book: Book,
    webFile: FileBook.WebFile,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
): Book? = withWaitDialog(
    awaitDismissWhen = { it != null },
    onWaitDialog = onWaitDialog,
) {
    withBookSaveDir {
        val merged = withContext(IoDispatcher) { WebFileImportShared.importWebFile(book, webFile) }
        withContext(IoDispatcher) {
            BookChapterLoader.fetchFromSource(merged, WebFileImportShared.bookSourceOf(merged))
        }
        merged
    }
}

/** 仅下载 webFile 并返回保存引用 (对照 app 端 `BookInfoViewModel.downloadWebFile`)。 */
private suspend fun downloadWebFile(
    book: Book,
    webFile: FileBook.WebFile,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
): String? = withWaitDialog(
    awaitDismissWhen = { it != null },
    onWaitDialog = onWaitDialog,
) {
    withBookSaveDir {
        withContext(IoDispatcher) { WebFileImportShared.downloadWebFile(book, webFile) }
    }
}

/** 从压缩包条目导入并落目录 (对照 app 端 `importBookFromArchive`)。 */
private suspend fun importFromArchive(
    archivePath: String,
    archiveEntryName: String,
    book: Book,
    onWaitDialog: (Boolean, WaitDialogHandle) -> Unit,
): Book? = withWaitDialog(
    awaitDismissWhen = { it != null },
    onWaitDialog = onWaitDialog,
) {
    withBookSaveDir {
        val imported = withContext(IoDispatcher) {
            WebFileImportShared.importBookFromArchive(archivePath, archiveEntryName, book)
        }
        withContext(IoDispatcher) {
            BookChapterLoader.fetchFromSource(imported, WebFileImportShared.bookSourceOf(imported))
        }
        imported
    }
}