package io.legado.app.ui.book.import.remote

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.addType
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.fileBook.CbzFile
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Collections

class RemoteBookViewModel(application: Application) : BaseViewModel(application) {
    var sortKey = RemoteBookSort.Default
    var sortAscending = false
    val dirList = arrayListOf<RemoteBook>()
    val permissionDenialLiveData = MutableLiveData<Int>()

    var dataCallback: DataCallback? = null

    val dataFlow = callbackFlow<List<RemoteBook>> {
        val list = Collections.synchronizedList(ArrayList<RemoteBook>())
        dataCallback = object : DataCallback {
            override fun setItems(remoteFiles: List<RemoteBook>) {
                list.clear()
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun addItems(remoteFiles: List<RemoteBook>) {
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun screen(key: String?) {
                if (key.isNullOrBlank()) {
                    trySend(list)
                } else {
                    trySend(list.filter { it.filename.contains(key) })
                }
            }
        }
        awaitClose { dataCallback = null }
    }.map { list ->
        val secondaryComparator = when (sortKey) {
            RemoteBookSort.Name -> compareBy(AlphanumComparator) { it: RemoteBook -> it.filename }
            else -> compareBy { it: RemoteBook -> it.lastModify }
        }.let { if (sortAscending) it else it.reversed() }
        list.sortedWith(compareBy<RemoteBook> { !it.isDir }.then(secondaryComparator))
    }.flowOn(Dispatchers.IO)

    private var remoteBookWebDav: RemoteBookWebDav? = null
    var isDefaultWebdav = false

    fun initData(onSuccess: () -> Unit) {
        execute {
            isDefaultWebdav = false
            appDb.serverDao.get(AppConfig.remoteServerId)?.getWebDavConfig()?.let {
                remoteBookWebDav =
                    RemoteBookWebDav(it.url, Authorization(it), AppConfig.remoteServerId)
            } ?: run {
                isDefaultWebdav = true
                remoteBookWebDav =
                    AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
            }
        }.onError {
            context.toastOnUi("初始化webDav出错:${it.localizedMessage}")
        }.onSuccess {
            onSuccess()
        }
    }

    fun loadRemoteBookList(path: String?, loadCallback: (loading: Boolean) -> Unit) {
        executeLazy {
            val bookWebDav = remoteBookWebDav ?: throw NoStackTraceException("没有配置webDav")
            dataCallback?.clear()
            dataCallback?.setItems(bookWebDav.getRemoteBookList(path ?: bookWebDav.rootBookUrl))
        }.onStart {
            loadCallback(true)
        }.onFinally {
            loadCallback(false)
        }.onError {
            AppLog.put("获取webDav书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi("获取webDav书籍出错\n${it.localizedMessage}")
        }.start()
    }

    fun addToBookshelf(remoteBooks: HashSet<RemoteBook>, finally: () -> Unit) {
        execute {
            val bookWebDav = remoteBookWebDav ?: throw NoStackTraceException("没有配置webDav")
            remoteBooks.forEach { remoteBook ->
                val origin = BookType.webDavTag + CustomUrl(remoteBook.path)
                    .putAttribute("serverID", bookWebDav.serverID).toString()

                val importAsImage: suspend () -> Unit = {
                    val bookUrl = if (remoteBook.size > 30 * 1024 * 1024) origin
                    else bookWebDav.downloadRemoteBook(remoteBook).toString()
                    FileBook.importImageBook(
                        bookUrl,
                        remoteBook.filename,
                        remoteBook.filename,
                        remoteBook.lastModify,
                        origin
                    )
                }

                when {
                    remoteBook.filename.endsWith(".cbz", true) -> importAsImage()

                    ArchiveUtils.isArchive(remoteBook.filename) -> {
                        findTxtEntryInRemoteZip(
                            bookWebDav.getWebDav(remoteBook.path),
                            remoteBook.filename,
                            remoteBook.size
                        )
                            ?.use { (remoteZip, entry) ->
                                val uri = FileBook.saveBookFile(
                                    remoteZip.getInputStream(entry)
                                        ?: throw NoStackTraceException("获取流失败"),
                                    entry.name
                                )
                                FileBook.importFile(uri).apply {
                                    this.origin = origin
                                    addType(BookType.archive)
                                    save()
                                }
                            } ?: importAsImage()
                    }

                    else -> bookWebDav.downloadRemoteBook(remoteBook).let { uri ->
                        (if (ArchiveUtils.isArchive(FileDoc.fromUri(uri, false).name)) {
                            FileBook.importFromArchive(uri) { it.matches(AppPattern.bookFileRegex) }
                        } else {
                            listOf(FileBook.importFile(uri))
                        }).forEach { book ->
                            book.origin = origin
                            book.save()
                        }
                    }
                }
                remoteBook.isOnBookShelf = true
            }
        }.onError {
            AppLog.put("导入出错\n${it.localizedMessage}", it, true)
            if (it is SecurityException) permissionDenialLiveData.postValue(1)
        }.onFinally {
            finally()
        }
    }

    private fun findTxtEntryInRemoteZip(
        webDav: WebDav,
        name: String,
        fileSize: Long
    ): RemoteZipEntry? {
        if (fileSize <= 0) return null
        val remoteZip = CbzFile.RemoteZipFile(webDav, name, fileSize)
        return try {
            remoteZip.entries().asSequence()
                .find { !it.isDirectory && it.name.endsWith(".txt", true) }
                ?.let { RemoteZipEntry(remoteZip, it) }
                ?: let {
                    remoteZip.close()
                    null
                }
        } catch (_: Exception) {
            remoteZip.close()
            null
        }
    }

    private class RemoteZipEntry(
        val remoteZip: CbzFile.RemoteZipFile,
        val entry: CbzFile.CbzEntry
    ) : AutoCloseable {
        operator fun component1() = remoteZip
        operator fun component2() = entry
        override fun close() {
            remoteZip.close()
        }
    }

    fun updateCallBackFlow(filterKey: String?) {
        dataCallback?.screen(filterKey)
    }

    interface DataCallback {

        fun setItems(remoteFiles: List<RemoteBook>)

        fun addItems(remoteFiles: List<RemoteBook>)

        fun clear()

        fun screen(key: String?)

    }
}
