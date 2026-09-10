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
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.isJson
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 文件关联导入分发链 (iOS/鸿蒙/桌面共用, 各端逻辑完全一致)。
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
object FileAssociationDispatch {

    /** 分发文件关联导入; [filePath] 为绝对路径 (或 file:// URL)。 */
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
            val json = runCatching { FileSystem.SYSTEM.read(path.toPath()) { readUtf8() } }
                .onFailure { AppLog.put("尝试导入为JSON文件失败\n${it.message}", it) }
                .getOrNull()
            if (json != null) {
                if (dispatchJson(json)) return
                // 嗅探为 JSON 但业务类型未知: 明确报告格式错误并终止, 严禁继续向下命中文本书籍正则当成小说导入
                Toasters.get().toast(syncGetString("wrong_format"))
                AppLog.put("文件关联导入: 格式不对 (未知 JSON 业务类型) $path")
            }
            return
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
        LegadoDeepLinkHandler.enqueue(
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
        // getOrNull: 文件关联可能在 UI 就绪前把系统传来的文件路径投进来
        AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
    }

    /**
     * 将文件路径或 file:// URL 转为本地绝对路径。
     * - 支持标准 RFC 8089 file: URI (file:///..., file://localhost/..., file:/...)
     * - 支持 Windows UNC 网络路径 (file://server/share/...)
     * - 补全 URL percent-decoding (如 %20、中文等 UTF-8 编码路径)
     * - Windows 盘符前导斜杠剥离 (如 /C:/... → C:/...)
     */
    private fun String.toLocalPath(): String {
        if (!startsWith("file:", ignoreCase = true)) return this
        val rawAfterScheme = substring(5).percentDecode()
        val path = when {
            rawAfterScheme.startsWith("///") || rawAfterScheme.startsWith("\\\\\\") ->
                rawAfterScheme.substring(2)

            rawAfterScheme.startsWith("//") || rawAfterScheme.startsWith("\\\\") -> {
                val slashIdx = rawAfterScheme.indexOfAny(charArrayOf('/', '\\'), startIndex = 2)
                if (slashIdx < 0) {
                    rawAfterScheme.substring(2)
                } else {
                    val authority = rawAfterScheme.substring(2, slashIdx)
                    val rest = rawAfterScheme.substring(slashIdx)
                    if (authority.isEmpty() || authority.equals("localhost", ignoreCase = true)) {
                        rest
                    } else {
                        "//$authority$rest"
                    }
                }
            }

            else -> rawAfterScheme
        }
        return if (path.length >= 3 &&
            (path[0] == '/' || path[0] == '\\') &&
            (path[1] in 'a'..'z' || path[1] in 'A'..'Z') &&
            path[2] == ':' &&
            (path.length == 3 || path[3] == '/' || path[3] == '\\')
        ) {
            path.substring(1)
        } else {
            path
        }
    }

    private fun String.percentDecode(): String {
        if (!contains('%')) return this
        val sb = StringBuilder(length)
        val byteBuf = ArrayList<Byte>()
        fun flushBytes() {
            if (byteBuf.isNotEmpty()) {
                sb.append(byteBuf.toByteArray().decodeToString())
                byteBuf.clear()
            }
        }

        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '%' && i + 2 < length) {
                val hi = this[i + 1].digitToIntOrNull(16)
                val lo = this[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    byteBuf.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            flushBytes()
            sb.append(c)
            i++
        }
        flushBytes()
        return sb.toString()
    }

    private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')
}
