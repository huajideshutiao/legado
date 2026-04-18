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
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO

class BookInfoViewModel(application: Application) : BaseReadViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val webFiles = mutableListOf<WebFile>()
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
                    val bookWebDav = AppWebDav.defaultBookWebDav
                        ?: throw NoStackTraceException("webDav没有配置")
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

    override fun loadWebFile(book: Book) {
        execute {
            webFiles.clear()
            val fileNameNoExtension = if (book.author.isBlank()) book.name
            else "${book.name} 作者：${book.author}"
            book.downloadUrls!!.map {
                val analyzeUrl = AnalyzeUrl(
                    it, source = curBookSource,
                    coroutineContext = coroutineContext
                )
                val mFileName = UrlUtil.getFileName(analyzeUrl)
                    ?: "${fileNameNoExtension}.${analyzeUrl.type}"
                WebFile(it, mFileName)
            }
        }.onError {
            context.toastOnUi("LoadWebFileError\n${it.localizedMessage}")
        }.onSuccess {
            webFiles.addAll(it)
        }
    }

    /* 导入或者下载在线文件 */
    fun <T> importOrDownloadWebFile(webFile: WebFile, success: ((T) -> Unit)?) {
        curBookSource ?: return
        execute {
            waitDialogData.postValue(true)
            if (webFile.isSupported) {
                val book = FileBook.importFileOnLine(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    curBookSource
                )
                changeToLocalBook(book)
            } else {
                FileBook.saveBookFile(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    curBookSource
                )
            }
        }.onSuccess {
            @Suppress("unchecked_cast")
            success?.invoke(it as T)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it)
                    context.toastOnUi("ImportWebFileError\n${it.localizedMessage}")
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
            AppLog.put("getArchiveEntriesName Error:\n${it.localizedMessage}", it)
            context.toastOnUi("getArchiveEntriesName Error:\n${it.localizedMessage}")
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    fun importArchiveBook(
        archiveFileUri: Uri,
        archiveEntryName: String,
        success: ((Book) -> Unit)? = null
    ) {
        execute {
            val suffix = archiveEntryName.substringAfterLast(".")
            FileBook.importArchiveFile(
                archiveFileUri,
                bookData.value!!.getExportFileName(suffix)
            ) {
                it.contains(archiveEntryName)
            }.first()
        }.onSuccess {
            val book = changeToLocalBook(it)
            success?.invoke(book)
        }.onError {
            AppLog.put("importArchiveBook Error:\n${it.localizedMessage}", it)
            context.toastOnUi("importArchiveBook Error:\n${it.localizedMessage}")
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
            if (!FileBook.downloadRemoteBook(book)) {
                throw Exception("下载失败，请检查网络或WebDav配置")
            }
        }.onSuccess {
            context.toastOnUi("下载成功")
            bookData.postValue(book)
        }.onError {
            AppLog.put("下载远程书籍<${book.name}>失败", it)
            context.toastOnUi("下载失败: ${it.localizedMessage}")
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

    data class WebFile(
        val url: String,
        val name: String,
    ) {

        override fun toString(): String {
            return name
        }

        // 后缀
        val suffix: String = UrlUtil.getSuffix(name)

        // txt epub umd pdf等文件
        val isSupported: Boolean = AppPattern.bookFileRegex.matches(name)

        // 压缩包形式的txt epub umd pdf文件
        val isSupportDecompress: Boolean = AppPattern.archiveFileRegex.matches(name)

    }

}
