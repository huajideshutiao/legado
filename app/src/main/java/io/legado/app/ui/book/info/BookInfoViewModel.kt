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
        get() = bookData.value
        set(value) {
            value?.let { bookData.postValue(it) }
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
                refreshWebDavBook(book)
            } else {
                refreshBookSourceName(book)
            }
        }.onError {
            if (it is ObjectNotFoundException) {
                book.origin = BookType.localTag
            } else {
                AppLog.put("下载远程书籍<${book.name}>失败", it)
            }
        }.onFinally {
            execute { loadBookInfo(book) }
        }.start()
    }

    private suspend fun refreshWebDavBook(book: Book) {
        book.getRemoteUrl()?.let { remoteUrl ->
            val bookWebDav =
                AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
            val remoteBook = bookWebDav.getRemoteBook(remoteUrl)
            if (remoteBook == null) {
                book.origin = BookType.localTag
                return
            }
            if (remoteBook.lastModify > book.lastCheckTime) {
                val uri = bookWebDav.downloadRemoteBook(remoteBook)
                book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
                book.lastCheckTime = remoteBook.lastModify
            }
        }
    }

    private fun refreshBookSourceName(book: Book) {
        curBookSource?.let { source ->
            if (book.originName != source.bookSourceName) {
                book.originName = source.bookSourceName
            }
        }
    }

    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    fun importWebFile(webFile: WebFile, success: ((Book) -> Unit)?) {
        execute {
            waitDialogData.postValue(true)
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            val fileName = book.getExportFileName(webFile.suffix)
            val uri = FileBook.saveBookFile(webFile.url, fileName, curBookSource)
            changeToLocalBook(FileBook.mergeBook(FileBook.importLocalFile(uri), book))
        }.onSuccess {
            success?.invoke(it)
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

    fun downloadWebFile(webFile: WebFile, success: ((Uri) -> Unit)?) {
        execute {
            waitDialogData.postValue(true)
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            val fileName = book.getExportFileName(webFile.suffix)
            FileBook.saveBookFile(webFile.url, fileName, curBookSource)
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("DownloadWebFileError\n${it.localizedMessage}", it, true)
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
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            FileBook.importFromArchive(
                archiveFileUri, book.getExportFileName(suffix)
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
                book.order = appDb.bookDao.minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }
    }

    fun saveBook(book: Book?, success: (() -> Unit)? = null) {
        book ?: return
        curBook = book
        addToBookshelf(success)
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
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            BookHelp.clearCache(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.clearTextChapter()
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
