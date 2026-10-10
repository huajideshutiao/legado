package io.legado.app.ui.book.import.local

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.config.AppConfig
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.importLocalFile
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.list
import io.legado.app.utils.mapParallel
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.Collections

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = AppConfig.localBookImportSort
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    var filterKey: String? = null
    val dataFlow = callbackFlow<List<ImportBook>> {

        val list = Collections.synchronizedList(ArrayList<ImportBook>())

        dataCallback = object : DataCallback {

            override fun setItems(fileDocs: List<FileDoc>) {
                list.clear()
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun addItems(fileDocs: List<FileDoc>) {
                fileDocs.mapTo(list) {
                    ImportBook(it)
                }
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun upAdapter() {
                trySend(list)
            }
        }

        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        val docList = docList.toList()
        val filterKey = filterKey
        val skipFilter = filterKey.isNullOrBlank()
        val comparator = when (sort) {
            2 -> compareBy<ImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        docList.asSequence().filter {
            skipFilter || it.name.contains(filterKey)
        }.sortedWith(comparator).toList()
    }.flowOn(IO)

    /**
     * 上架选中条目。
     *
     * [onNeedBooksDir] 供"压缩包导入需要书籍保存位置而尚未设置"的场景接到"书籍保存位置"
     * 出口 (与"其它设置 → 书籍保存位置"同一选择器/同一偏好键), 返回 true 再重试该条导入;
     * 返回 false (用户取消) 或传 null 时按导入出错处理。普通书文件导入不经它。
     */
    fun addToBookshelf(
        bookList: HashSet<ImportBook>,
        onNeedBooksDir: (suspend () -> Boolean)? = null,
        finally: () -> Unit
    ) {
        execute {
            bookList.forEach { importBook ->
                importBookToBookshelf(importBook, onNeedBooksDir)
            }
        }.onError {
            AppLog.put("导入出错\n${it.localizedMessage}", it)
            context.toastOnUi("导入出错\n${it.localizedMessage}")
        }.onFinally {
            finally.invoke()
        }
    }

    /** 导入单本; 保存位置未设置时先选目录再重试一次 (其余导入异常照旧上抛)。 */
    private suspend fun importBookToBookshelf(
        importBook: ImportBook,
        onNeedBooksDir: (suspend () -> Boolean)?
    ) {
        try {
            FileBook.importLocalFile(importBook.file)
        } catch (e: InvalidBooksDirException) {
            if (onNeedBooksDir?.invoke() != true) throw e
            FileBook.importLocalFile(importBook.file)
        }
        importBook.isOnBookShelf = true
    }

    fun deleteDoc(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            bookList.forEach {
                it.file.delete()
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val docList = fileDoc.list { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDir -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            dataCallback?.setItems(docList!!)
        }.onError {
            context.toastOnUi("获取文件列表出错\n${it.localizedMessage}")
        }
    }

    suspend fun scanDoc(fileDoc: FileDoc) {
        dataCallback?.clear()
        val channel = Channel<FileDoc>(UNLIMITED)
        var n = 1
        channel.trySend(fileDoc)
        val list = arrayListOf<FileDoc>()
        channel.consumeAsFlow()
            .mapParallel(16) { fileDoc ->
                fileDoc.list()!!
            }.onEach { fileDocs ->
                n--
                list.clear()
                fileDocs.forEach {
                    if (it.isDir) {
                        n++
                        channel.trySend(it)
                    } else if (it.name.matches(bookFileRegex)
                        || it.name.matches(archiveFileRegex)
                    ) {
                        list.add(it)
                    }
                }
                dataCallback?.addItems(list)
            }.takeWhile {
                n > 0
            }.catch {
                context.toastOnUi("扫描文件夹出错\n${it.localizedMessage}")
            }.collect()
    }

    fun updateCallBackFlow(filterKey: String?) {
        this.filterKey = filterKey
        dataCallback?.upAdapter()
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>)

        fun addItems(fileDocs: List<FileDoc>)

        fun clear()

        fun upAdapter()

    }

}