package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.data.entities.Book
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.importLocalFile
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isFileScheme
import io.legado.app.utils.isJson
import io.legado.app.utils.openInputStream
import io.legado.app.utils.printOnDebug

/**
 * 文件关联导入 (txt/epub/zip/json 书源文件等)。
 *
 * 深链 (legado:// / yuedu://) 已统一走 shared [LegadoDeepLinkHandler] (见
 * [FileAssociationFragment] onCreate 与 MainActivity.handleExternalIntent), 本类不再承担
 * 在线导入/下载嗅探; 原 `getBytes`/`importReadConfig`/`determineType` 已删除, 其逻辑由
 * commonMain [SchemeImportOps] 承担 (deep link 宿主统一走共享实现)。
 */
class FileAssociationViewModel(application: Application) : BaseAssociationViewModel(application) {
    val importBookLiveData = MutableLiveData<Uri>()
    val openBookLiveData = MutableLiveData<Book>()
    val notSupportedLiveData = MutableLiveData<Pair<Uri, String>>()

    fun dispatchIntent(uri: Uri) {
        execute {
            //如果是普通的url，需要根据返回的内容判断是什么
            if (uri.isContentScheme() || uri.isFileScheme()) {
                val fileDoc = FileDoc.fromUri(uri, false)
                val fileName = fileDoc.name
                if (fileName.matches(AppPattern.archiveFileRegex)) {
                    ArchiveUtils.deCompress(fileDoc, ArchiveUtils.TEMP_PATH) {
                        it.matches(bookFileRegex)
                    }.forEach {
                        dispatch(FileDoc.fromFile(it))
                    }
                } else {
                    dispatch(fileDoc)
                }
            } else {
                // 非文件 scheme (legado/yuedu 深链等): 统一交 shared 解析器,
                // 非法格式静默丢弃 (对照 MainActivity.handleExternalIntent)
                LegadoDeepLinkHandler.handle(uri.toString())
            }
        }.onError {
            it.printOnDebug()
            val msg = "无法打开文件\n${it.localizedMessage}"
            errorLive.postValue(msg)
            AppLog.put(msg, it)
        }
    }

    private fun dispatch(fileDoc: FileDoc) {
        kotlin.runCatching {
            if (fileDoc.openInputStream().getOrNull().isJson()) {
                importJson(fileDoc.uri)
                return
            }
        }.onFailure {
            it.printOnDebug()
            AppLog.put("尝试导入为JSON文件失败\n${it.localizedMessage}", it)
        }
        if (fileDoc.name.matches(bookFileRegex)) {
            importBookLiveData.postValue(fileDoc.uri)
            return
        }
        notSupportedLiveData.postValue(Pair(fileDoc.uri, fileDoc.name))
    }

    fun importBook(uri: Uri) {
        val book = FileBook.importLocalFile(uri)
        openBookLiveData.postValue(book)
    }
}
