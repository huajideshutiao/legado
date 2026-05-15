package io.legado.app.ui.association

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.data.entities.Book
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isFileScheme
import io.legado.app.utils.isJson
import io.legado.app.utils.openInputStream
import io.legado.app.utils.printOnDebug
import okhttp3.MediaType.Companion.toMediaType
import splitties.init.appCtx

class FileAssociationViewModel(application: Application) : BaseAssociationViewModel(application) {
    val importBookLiveData = MutableLiveData<Uri>()
    val onLineImportLive = MutableLiveData<Uri>()
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
                onLineImportLive.postValue(uri)
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

    fun getBytes(url: String, success: (bytes: ByteArray) -> Unit) {
        execute {
            okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }.bytes()
        }.onSuccess {
            success.invoke(it)
        }.onError {
            errorLive.postValue(
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    fun importReadConfig(bytes: ByteArray, finally: (title: String, msg: String) -> Unit) {
        execute {
            val config = ReadBookConfig.import(bytes)
            ReadBookConfig.configList.forEachIndexed { index, c ->
                if (c.name == config.name) {
                    ReadBookConfig.configList[index] = config
                    return@execute config.name
                }
            }
            ReadBookConfig.configList.add(config)
            config.name
        }.onSuccess {
            finally.invoke(context.getString(R.string.success), "导入排版成功")
        }.onError {
            finally.invoke(
                context.getString(R.string.error),
                it.localizedMessage ?: context.getString(R.string.unknown_error)
            )
        }
    }

    fun determineType(url: String, finally: (title: String, msg: String) -> Unit) {
        execute {
            val rs = okHttpClient.newCallResponseBody {
                if (url.endsWith("#requestWithoutUA")) {
                    url(url.substringBeforeLast("#requestWithoutUA"))
                    header(AppConst.UA_NAME, "null")
                } else {
                    url(url)
                }
            }
            when (rs.contentType()) {
                "application/zip".toMediaType(),
                "application/octet-stream".toMediaType() -> {
                    importReadConfig(rs.bytes(), finally)
                }

                else -> {
                    val inputStream = rs.byteStream()
                    val file = FileUtils.createFileIfNotExist(
                        appCtx.externalCache,
                        "download",
                        "scheme_import_cache.json"
                    )
                    file.outputStream().use { out ->
                        inputStream.use {
                            it.copyTo(out)
                        }
                    }
                    importJson(file.toUri())
                }
            }
        }
    }
}