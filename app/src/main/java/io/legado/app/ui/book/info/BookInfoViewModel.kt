package io.legado.app.ui.book.info

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBook.WebFile
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO

class BookInfoViewModel(application: Application) : BaseReadViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val waitDialogData = MutableLiveData<Boolean>()
    val actionLive = MutableLiveData<String>()

    override var curBook: Book?
        get() = getBook(false)
        set(value) {
            if (value == null) return
            bookData.postValue(value)
        }

    fun initData() {
        execute {
            if (curBook != null) return@execute
            IntentData.book?.let { upBook(it) }
        }.onError {
            AppLog.put(it.localizedMessage, it)
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun refreshBook(book: Book) {
        executeLazy(executeContext = IO) {
            if (book.isLocal && !book.isImage) {
                book.getRemoteUrl()?.let {
                    val bookWebDav =
                        AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
                    val remoteBook = bookWebDav.getRemoteBook(it)
                    if (remoteBook == null) {
                        book.origin = BookType.localTag
                    } else if (remoteBook.lastModify > book.lastCheckTime) {
                        val uri = bookWebDav.downloadRemoteBook(remoteBook)
                        book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
                        book.lastCheckTime = remoteBook.lastModify
                    }
                }
            } else {
                val bs = curBookSource ?: return@executeLazy
                if (book.originName != bs.bookSourceName) {
                    book.originName = bs.bookSourceName
                }
            }
        }.onError {
            when (it) {
                is ObjectNotFoundException -> {
                    book.origin = BookType.localTag
                }

                else -> {
                    AppLog.put("下载远程书籍<${book.name}>失败", it)
                }
            }
        }.onFinally {
            execute { loadBookInfo(book) }
        }.start()
    }

    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    /* 导入或者下载在线文件 */
    fun <T> importOrDownloadWebFile(webFile: WebFile, success: ((T) -> Unit)?) {
        execute {
            waitDialogData.postValue(true)
            val fileName = bookData.value!!.getExportFileName(webFile.suffix)
            val uri = FileBook.saveBookFile(
                webFile.url, fileName, curBookSource
            )
            val result = if (!webFile.isSupported) uri
            else FileBook.mergeBook(FileBook.importLocalFile(uri), bookData.value!!)
            if (result is Book) changeToLocalBook(result)
            result
        }.onSuccess {
            @Suppress("unchecked_cast") success?.invoke(it as T)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it, true)
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun getArchiveFilesName(archiveFileUri: Uri, onSuccess: (List<String>) -> Unit) {
        execute {
            ArchiveUtils.getArchiveFilesName(archiveFileUri) {
                AppPattern.bookFileRegex.matches(it)
            }
        }.onError {
            AppLog.put("getArchiveEntriesName Error:\n${it.localizedMessage}", it, true)
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    fun importBookFromArchive(
        archiveFileUri: Uri, archiveEntryName: String, success: ((Book) -> Unit)? = null
    ) {
        execute {
            val suffix = archiveEntryName.substringAfterLast(".")
            FileBook.importFromArchive(
                archiveFileUri, bookData.value!!.getExportFileName(suffix)
            ) {
                it.contains(archiveEntryName)
            }.first()
        }.onSuccess {
            success?.invoke(changeToLocalBook(it))
        }.onError {
            AppLog.put("importArchiveBook Error:\n${it.localizedMessage}", it, true)
        }
    }

    fun topBook() {
        execute {
            bookData.value?.let { book ->
                val minOrder = appDb.bookDao.minOrder
                book.order = minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }
    }

    fun saveBook(book: Book?, success: (() -> Unit)? = null) {
        book ?: return
        execute {
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            appDb.bookDao.getBook(book.name, book.author)?.let {
                book.durChapterIndex = it.durChapterIndex
                book.durChapterPos = it.durChapterPos
                book.durChapterTitle = it.durChapterTitle
            }
            book.save()
        }.onSuccess {
            success?.invoke()
        }
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = bookData.value
        if (toastNull && book == null) {
            context.toastOnUi("book is null")
        }
        return book
    }

    fun downloadToLocal(book: Book) {
        execute {
            FileBook.downloadRemoteBook(book)
        }.onSuccess {
            context.toastOnUi("下载成功")
            bookData.postValue(book)
        }.onError {
            AppLog.put("下载远程书籍<${book.name}>失败", it, true)
        }
    }

    fun uploadBook(book: Book) {
        execute {
            waitDialogData.postValue(true)
            val bookWebDav =
                AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("未配置webDav")
            bookWebDav.upload(book)
            book.lastCheckTime = System.currentTimeMillis()
            book.save()
        }.onSuccess {
            context.toastOnUi("上传成功")
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun clearCache() {
        execute {
            BookHelp.clearCache(bookData.value!!)
            if (ReadBook.book?.bookUrl == bookData.value!!.bookUrl) {
                ReadBook.clearTextChapter()
            }
            if (ReadManga.book?.bookUrl == bookData.value!!.bookUrl) {
                ReadManga.clearMangaChapter()
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

    fun upEditBook() {
        bookData.postValue(IntentData.book as? Book)
    }

    private fun changeToLocalBook(localBook: Book): Book {
        return FileBook.mergeBook(localBook, bookData.value).let {
            execute { loadChapterList(it) }
            inBookshelf = true
            it
        }
    }

}
