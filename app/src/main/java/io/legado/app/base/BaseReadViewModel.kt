package io.legado.app.base

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.updateTo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.WebBook.getBookInfoAwait
import io.legado.app.model.webBook.WebBook.getChapterListAwait
import io.legado.app.utils.FileUtils
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take

/**
 * 阅读类 ViewModel 基类
 *
 * 提取了书籍详情(BookInfo)、文本阅读(ReadBook)、音频播放(AudioPlay)、
 * 视频播放(Video)、漫画阅读(ReadManga)等 ViewModel 中的公共逻辑:
 *
 * - [changeSourceCoroutine] 换源协程管理
 * - [changeTo] 换源通用流程
 * - [autoChangeSource] 自动换源
 * - [syncBookProgress] WebDAV 进度同步
 * - [removeFromBookshelf] 移出书架
 * - [saveImage] 保存图片
 * - [upSource] 更新书源
 *
 * 子类按需覆写 open 方法即可, 无强制实现要求
 */
abstract class BaseReadViewModel(application: Application) : BaseViewModel(application) {

    protected var changeSourceCoroutine: Coroutine<*>? = null

    /**
     * 获取当前正在阅读/播放的书籍
     * 唯一必须实现的属性, 被 changeTo/removeFromBookshelf/saveImage/upSource 使用
     */
    abstract var curBook: Book?
    open var inBookshelf: Boolean = false
    var isSearchBook: Boolean = false
    open var curBookSource: BookSource? = null
        protected set
    val chapterListData = MutableLiveData<List<BookChapter>>()

    /**
     * 换源成功后的初始化回调, 子类按需覆写
     */
    protected open fun onSourceChanged(book: Book, toc: List<BookChapter>) {}

    /**
     * 进度同步时设置进度, 子类按需覆写
     */
    protected open fun applyProgress(progress: BookProgress) {}

    /**
     * 进度同步提示消息, 子类按需覆写
     */
    protected open fun getSyncProgressMsg(): String = "已同步最新阅读进度"

    /**
     * 换源状态消息回调, 子类按需覆写
     */
    protected open fun onSourceChanging(msg: String) {}

    /**
     * 获取自动换源用的书源列表, 子类按需覆写
     * 默认返回 allTextEnabledPart, 不需要自动换源的子类返回空列表即可
     */
    protected open fun getTextEnabledSources() = appDb.bookSourceDao.allTextEnabledPart

    protected suspend fun upBook(book: BaseBook) {
        var book = if (book is SearchBook) {
            isSearchBook = true
            book.toBook()
        } else book as Book
        curBookSource = (IntentData.source as? BookSource)
            ?: if (book.isLocal) null else book.getBookSource()
        inBookshelf = !book.isNotShelf
        curBook = book
        if (inBookshelf && isSearchBook) book =
            appDb.bookDao.getBook(book.bookUrl) ?: appDb.bookDao.getBook(book.name, book.author)
                ?: book
        if (book.isRss) {
            book.tocUrl = book.bookUrl
            book.bookUrl = "data:"
        }
        if (book.tocUrl.isEmpty()) {
            loadBookInfo(book, runPreUpdateJs = inBookshelf)
        } else {
            val tmp = IntentData.chapterList
            when {
                tmp != null && tmp[0].bookUrl == book.bookUrl -> {
                    curBook = book
                    chapterListData.postValue(tmp)
                }

                !inBookshelf || book.totalChapterNum == 0 -> loadChapterList(book)

                else -> {
                    val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                    if (chapterList.isEmpty()) {
                        loadChapterList(book)
                    } else {
                        curBook = book
                        chapterListData.postValue(chapterList)
                    }
                }
            }
        }
    }

    suspend fun loadBookInfo(
        book: Book, canReName: Boolean = true, runPreUpdateJs: Boolean = true
    ) {
        if (book.isLocal) {
            val tmp = book.copy()
            LocalBook.upBookInfo(book)
            if (tmp.tocUrl != book.tocUrl || book.totalChapterNum == 0) loadChapterList(book)
            else curBook = book
        } else {
            val bookSource = curBookSource ?: let {
                chapterListData.postValue(emptyList())
                context.toastOnUi(R.string.error_no_source)
                return
            }
            try {
                getBookInfoAwait(bookSource, book, canReName)
                if (isSearchBook) {
                    val dbBook = appDb.bookDao.getBook(book.bookUrl) ?: appDb.bookDao.getBook(
                        book.name, book.author
                    )
                    if (dbBook != null && dbBook.origin == book.origin) {
                        /**
                         * book 来自搜索时(inBookshelf == false)，搜索的书名不存在于书架，但是加载详情后，书名更新，存在同名书籍
                         * 此时 book 的数据会与数据库中的不同，需要更新 #3652 #4619
                         * book 加载详情后虽然书名作者相同，但是又可能不是数据库中(书源不同)的那本书 #3149
                         */
                        dbBook.updateTo(book)
                        inBookshelf = true
                    } else {
                        book.addType(BookType.notShelf)
                        inBookshelf = false
                    }
                }
                if (inBookshelf) book.save()
                if (book.isWebFile) {
                    loadWebFile(book)
                    curBook = book
                } else {
                    loadChapterList(book, runPreUpdateJs)
                }
            } catch (e: Throwable) {
                AppLog.put("获取书籍信息失败\n${e.localizedMessage}", e)
                context.toastOnUi(R.string.error_get_book_info)
            }
        }
    }

    protected open fun loadWebFile(book: Book) {}

    protected suspend fun loadChapterList(
        book: Book, runPreUpdateJs: Boolean = true
    ) {
        if (book.isLocal) {
            try {
                LocalBook.getChapterList(book).let {
                    appDb.bookDao.update(book)
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    if (inBookshelf) appDb.bookChapterDao.insert(*it.toTypedArray())
                    if (book.isImage) {
                        ReadManga.onChapterListUpdated(book)
                    } else {
                        ReadBook.onChapterListUpdated(book)
                    }
                    curBook = book
                    chapterListData.postValue(it)
                }
            } catch (e: Throwable) {
                context.toastOnUi("LoadTocError:${e.localizedMessage}")
            }
        } else {
            val bookSource = curBookSource ?: let {
                chapterListData.postValue(emptyList())
                context.toastOnUi(R.string.error_no_source)
                return
            }
            val oldBook = book.copy()
            try {
                val tmp = getChapterListAwait(bookSource, book, runPreUpdateJs).getOrThrow()
                if (inBookshelf) {
                    appDb.bookDao.replace(oldBook, book)
                    /**
                     * runPreUpdateJs 有可能会修改 book 的 bookUrl
                     */
                    if (oldBook.bookUrl != book.bookUrl) {
                        BookHelp.updateCacheFolder(oldBook, book)
                    }
                    appDb.bookChapterDao.insert(*tmp.toTypedArray())
                }
                curBook = book
                chapterListData.postValue(tmp)
            } catch (e: Throwable) {
                chapterListData.postValue(emptyList())
                AppLog.put("获取目录失败\n${e.localizedMessage}", e)
                context.toastOnUi(R.string.error_get_chapter_list)
            }
        }
    }


    /**
     * 换源 - 通用流程
     * AudioPlay/BookInfo 有独立签名的 changeTo, 不走此方法
     */
    open fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = execute {
            curBookSource = source
            curBook?.migrateTo(book, toc)
            if (inBookshelf) {
                book.removeType(BookType.updateError)
                curBook?.delete()
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
            chapterListData.postValue(toc)
            onSourceChanged(book, toc)
        }.onError {
            AppLog.put("换源失败\n$it", it, true)
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    /**
     * 自动换源
     * ReadBook 和 ReadManga 的自动换源逻辑几乎一致, 提取到基类
     */
    protected fun autoChangeSource(name: String, author: String) {
        if (!AppConfig.autoChangeSource) return
        execute {
            val sources = getTextEnabledSources()
            flow {
                for (source in sources) {
                    source.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                onSourceChanging(context.getString(R.string.source_auto_changing))
            }.mapParallelSafe(AppConfig.threadCount, sources.size) { source ->
                val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
                if (book.tocUrl.isEmpty()) {
                    getBookInfoAwait(source, book)
                }
                val toc = getChapterListAwait(source, book).getOrThrow()
                val chapter = toc.getOrElse(book.durChapterIndex) {
                    toc.last()
                }
                val nextChapter = toc.getOrElse(chapter.index) {
                    toc.first()
                }
                WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    nextChapterUrl = nextChapter.url
                )
                book to toc
            }.take(1).onEach { (book, toc) ->
                changeTo(curBookSource!!, book, toc)
            }.onEmpty {
                throw NoStackTraceException("没有合适书源")
            }.onCompletion {
                onSourceChanging("")
            }.catch {
                AppLog.put("自动换源失败\n${it.localizedMessage}", it)
                context.toastOnUi("自动换源失败\n${it.localizedMessage}")
            }.collect()
        }
    }

    /**
     * 同步阅读进度 (WebDAV)
     * ReadBook 和 ReadManga 的进度同步逻辑几乎一致, 提取到基类
     */
    fun syncBookProgress(
        book: Book, alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        execute {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            progress ?: return@onSuccess
            if (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos == book.durChapterPos) {
                return@onSuccess
            }
            if (progress.durChapterIndex < book.durChapterIndex || (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos < book.durChapterPos)) {
                alertSync?.invoke(progress)
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                applyProgress(progress)
                AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                context.toastOnUi(getSyncProgressMsg())
            }
        }
    }

    /**
     * 移出书架
     * AudioPlay 有自己的实现
     */
    open fun removeFromBookshelf(success: (() -> Unit)?) {
        Coroutine.async {
            curBook?.delete()
        }.onSuccess {
            success?.invoke()
        }
    }

    /**
     * 保存图片
     * ReadBook 和 ReadManga 有自己的实现
     */
    open fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = curBook ?: return
        execute {
            val image = BookHelp.getImage(book, src)
            FileUtils.saveImage(image, uri)
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            context.toastOnUi("保存图片出错\n${it.localizedMessage}")
        }.onFinally {
            context.toastOnUi("保存图片成功")
        }
    }

    /**
     * 更新书源
     */
    open fun upSource() {
        execute {
            curBook?.let { book ->
                onUpSource(book)
            }
        }
    }

    /**
     * 子类实现具体的书源更新逻辑, 按需覆写
     */
    protected open fun onUpSource(book: Book) {}

    override fun onCleared() {
        super.onCleared()
        changeSourceCoroutine?.cancel()
    }

    fun addToBookshelf(success: (() -> Unit)?) {
        execute {
            curBook?.let { book ->
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                appDb.bookDao.getBook(book.name, book.author)?.let {
                    book.durChapterIndex = it.durChapterIndex
                    book.durChapterPos = it.durChapterPos
                    book.durChapterTitle = it.durChapterTitle
                }
                book.save()
            }
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
            }
            inBookshelf = true
        }.onSuccess {
            success?.invoke()
        }
    }

    fun delBook(deleteOriginal: Boolean = false, success: (() -> Unit)? = null) {
        execute {
            curBook?.let {
                appDb.bookChapterDao.delByBook(it.bookUrl)
                it.delete()
                try {
                    Glide.with(context).asFile().load(it.coverUrl).signature(ObjectKey("covers"))
                        .onlyRetrieveFromCache(true).submit().get()?.delete()
                } catch (_: Exception) {
                }
                inBookshelf = false
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                }
            }
        }.onSuccess {
            success?.invoke()
        }
    }
}
