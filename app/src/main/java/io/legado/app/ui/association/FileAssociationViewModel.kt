package io.legado.app.ui.association

import android.net.Uri
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.openInputStream
import io.legado.app.utils.readJsonCandidateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单个待导入条目的分发结果: 完整批次一次给出, 由执行器逐项处理。
 */
sealed class DispatchOutcome {

    /**
     * JSON 文件: 识别出的导入类型 + 已读出的全文 (供导入 VM 直接解析)。
     * [type] 为 null = 是 JSON 但业务类型未识别 (执行器按"格式不对"统一呈现)。
     */
    data class Json(val type: DeepLinkImportType?, val source: String) : DispatchOutcome()

    /** 书籍文件地址 (已识别可按书导入, 执行器负责保存位置与落库)。 */
    data class Book(val uri: Uri) : DispatchOutcome()

    /** 其余文件: 原地址 + 文件名, 由执行器弹框确认是否仍按书导入。 */
    data class Unsupported(val uri: Uri, val name: String) : DispatchOutcome()
}

/**
 * 文件关联导入 (txt/epub/zip/json 书源文件等) 的文件分发。
 *
 * 深链 (legado:// / yuedu://) 由执行器入口直接分流到共享解析链, 本类只收文件;
 * 在线导入/下载嗅探由 commonMain [SchemeImportOps] 承担。
 */
class FileAssociationViewModel {

    /**
     * 完整分发: 压缩包解包后逐本展开 (包内仍只取书籍文件), 每个待导入条目一项
     * 一次性交出 —— 不存在单值多次投递被合并的缺口。
     */
    suspend fun dispatchIntent(uri: Uri, workPath: String = ArchiveUtils.TEMP_PATH): List<DispatchOutcome> = try {
        withContext(Dispatchers.IO) {
            val fileDoc = FileDoc.fromUri(uri, false)
            if (fileDoc.name.matches(AppPattern.archiveFileRegex)) {
                ArchiveUtils.deCompress(fileDoc, workPath) {
                    it.matches(bookFileRegex)
                }.map { dispatch(FileDoc.fromFile(it)) }
            } else {
                listOf(dispatch(fileDoc))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NoStackTraceException("无法打开文件\n${e.localizedMessage}")
    }

    private fun dispatch(fileDoc: FileDoc): DispatchOutcome {
        // JSON 判定与全文共用一次流: 探测后重开再读会让 JSON 内容过两遍, 不可寻址源两次打开
        // 还可能取到不同内容; 中途读取失败如实上抛 (执行器按"无法打开文件"呈现), 不降级为不支持
        val jsonText = fileDoc.openInputStream().getOrNull().readJsonCandidateText()
        if (jsonText != null) {
            return DispatchOutcome.Json(detectJsonType(jsonText)?.toDeepLinkImportType(), jsonText)
        }
        if (fileDoc.name.matches(bookFileRegex)) {
            return DispatchOutcome.Book(fileDoc.uri)
        }
        return DispatchOutcome.Unsupported(fileDoc.uri, fileDoc.name)
    }

    /** 单本导入落库 (调用方已在 IO 上)。 */
    fun importBook(uri: Uri, parent: kotlinx.coroutines.Job? = null): Book =
        FileBook.importLocalFile(uri.toString(), parent)
}
