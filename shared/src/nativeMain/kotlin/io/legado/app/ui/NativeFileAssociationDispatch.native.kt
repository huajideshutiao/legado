package io.legado.app.ui

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.association.DeepLinkImportRequest
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.association.detectJsonType
import io.legado.app.ui.association.toDeepLinkImportType
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.File
import io.legado.app.utils.isJson
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * iOS/鸿蒙 文件关联导入分发链 (nativeMain 共用, 两端逻辑完全一致)。
 *
 * 对照 app 端 `FileAssociationViewModel.dispatchIntent` / `dispatch`:
 * 1. 压缩包 (archiveFileRegex) → 解压后逐个文件再分发;
 * 2. JSON → [detectJsonType] 识别类型, 经 [LegadoDeepLinkHandler.handleResolved] 交
 *    sharedUiMain `DeepLinkImportHost` 走对应 Import*ViewModelShared (与 deep link 同链,
 *    弹勾选对话框后入库);
 * 3. 书籍文件 (bookFileRegex) → [FileBook.importLocalFile] + 跳阅读路由;
 * 4. 都不是 → toast 明确不支持 (不静默吞掉)。
 *
 * 与 app 端差异: app 端结果推 LiveData 由 Fragment 弹窗, 这里直接触发路由/导入宿主。
 */
object NativeFileAssociationDispatch {

    /** 分发文件关联导入; [filePath] 为沙盒内绝对路径 (或 file:// URL)。 */
    fun dispatch(filePath: String) {
        val path = filePath.toLocalPath()
        val fileName = path.fileName()
        // 压缩包: 先解压再逐个分发 (对照 app 端 archive 分支)
        if (fileName.matches(AppPattern.archiveFileRegex)) {
            val extracted = runCatching { ArchiveProviders.get().deCompress(path) }.getOrElse { e ->
                Toasters.get().toast(e.message ?: "解压失败")
                AppLog.put("解压关联文件失败: $path", e)
                return
            }
            // 与 app 端一致只取压缩包内的书籍文件 (deCompress 的 filter 语义)
            val books = extracted.filter { it.fileName().matches(AppPattern.bookFileRegex) }
            if (books.isEmpty()) {
                Toasters.get().toast("压缩包内没有可导入的书籍文件")
                return
            }
            books.forEach { dispatchFile(it) }
            return
        }
        dispatchFile(path)
    }

    /** 单文件分发 (对照 app 端 dispatch: 先试 JSON, 再试书籍文件, 否则不支持)。 */
    private fun dispatchFile(path: String) {
        val fileName = path.fileName()
        // 先试 JSON: 与 app 端 InputStream.isJson() 一样只探首尾 128 字节, 命中才全量读
        // (epub/pdf 可能上百 MB, 不能为了判定就整份读进内存)
        val isJson = runCatching { probeIsJson(path) }
            .onFailure { AppLog.put("尝试导入为JSON文件失败\n${it.message}", it) }
            .getOrDefault(false)
        if (isJson) {
            val json = runCatching { File(path).readText() }
                .onFailure { AppLog.put("尝试导入为JSON文件失败\n${it.message}", it) }
                .getOrNull()
            if (json != null && dispatchJson(json)) return
        }

        if (fileName.matches(AppPattern.bookFileRegex)) {
            val book = runCatching { FileBook.importLocalFile(path) }.getOrElse { e ->
                Toasters.get().toast(e.message ?: "导入书籍失败")
                AppLog.put("导入关联书籍失败: $path", e)
                return
            }
            openBook(book)
            return
        }
        // 对照 app 端 notSupportedLiveData
        Toasters.get().toast("不支持的文件: $fileName")
        AppLog.put("文件关联导入: 不支持的文件 $path")
    }

    /**
     * JSON 文本嗅探类型后交 deep link 宿主导入 (Import*ViewModelShared 的
     * importSource/import 同时接受 URL 与纯 JSON 文本, 这里直接喂文本)。
     *
     * @return true 已识别为受支持的导入类型; false 格式不对, 由调用方继续按书籍文件尝试
     */
    private fun dispatchJson(json: String): Boolean {
        val type = detectJsonType(json) ?: return false
        LegadoDeepLinkHandler.handleResolved(
            DeepLinkImportRequest(type.toDeepLinkImportType(), json)
        )
        return true
    }

    /**
     * 只读首尾各 128 字节判断是否 JSON (对照 app 端 `InputStream.isJson()` 的探测策略:
     * 取头 128 字节 + 尾 128 字节拼起来判 `{...}` / `[...]`)。
     */
    private fun probeIsJson(path: String): Boolean {
        val handle = FileSystem.SYSTEM.openReadOnly(path.toPath())
        try {
            val size = handle.size()
            val probe = if (size <= 256L) {
                // 小文件直接整读
                ByteArray(size.toInt()).also { handle.read(0L, it, 0, it.size) }.decodeToString()
            } else {
                val head = ByteArray(128).also { handle.read(0L, it, 0, 128) }
                val tail = ByteArray(128).also { handle.read(size - 128L, it, 0, 128) }
                head.decodeToString().trim() + tail.decodeToString().trim()
            }
            return probe.isJson()
        } finally {
            handle.close()
        }
    }

    private fun openBook(book: Book) {
        AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
    }

    /** file:// URL 转沙盒绝对路径 (与 NativeFileBookAccessor.resolveLocalFile 剥离规则一致)。 */
    private fun String.toLocalPath(): String = if (startsWith("file:")) {
        val afterScheme = substringAfter("file://")
        val slashIdx = afterScheme.indexOf('/')
        if (slashIdx > 0) afterScheme.substring(slashIdx) else afterScheme
    } else this

    private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')
}
