package io.legado.app.ui.book.info

import android.app.Application
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseReadViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
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
import io.legado.app.help.book.save
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.FileBook.WebFile
import io.legado.app.model.fileBook.importFromArchive
import io.legado.app.model.fileBook.importLocalFile
import io.legado.app.model.fileBook.saveBookFile
import io.legado.app.ui.book.read.ReviewListDialog
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch

/**
 * 书籍详情页 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 核心业务编排 (loadGroup / topBook / refreshBookSourceName / upEditBook) 已下沉到 shared
 * commonMain [BookInfoViewModelShared], 用 `StateFlow<Book?>` / `StateFlow<Boolean?>` /
 * `StateFlow<String?>` 替代 `MutableLiveData<...>` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 [BookInfoViewModelShared]:
 * - 本类必须继承 [BaseReadViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope` / `loadBookInfo` / `addToBookshelf` / `delBook` / `chapterListData` /
 *   `webFiles` / `curBookSource` / `inBookshelf`), Kotlin 单继承无法同时继承
 *   [BookInfoViewModelShared];
 * - 仅注入 `scope = viewModelScope` 一个 lambda 参数, 不违反"避免超多继承与参数传递"原则。
 *
 * # 状态桥接
 *
 * [bookData] / [waitDialogData] / [actionLive] 仍是 `LiveData<...>` (供 BookInfoActivity observe),
 * 内部用 [viewModelScope] 协程订阅 [shared] 的三个 StateFlow, 转发到 MutableLiveData:
 * - StateFlow 是 hot flow, collect 时立即收到当前值, 但 [shared] 初始值为 null, 桥接时
 *   过滤 null 不触发 LiveData observer, 避免初始假触发;
 * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致;
 * - 不用 `androidx.lifecycle.asLiveData()` 扩展: 项目未显式引入 lifecycle-livedata-ktx,
 *   用 viewModelScope.launch + collect 自己桥接确保编译通过。
 *
 * # BaseReadViewModel.curBook 与 shared._bookData 同步
 *
 * [curBook] getter 读 `shared.bookData.value` (同步, 线程安全), setter 写 `shared.upBook(value)`
 * (同步, StateFlow 内部 atomic)。这样 BaseReadViewModel 的方法 (loadBookInfo / loadChapterList /
 * addToBookshelf / delBook) 通过 `curBook = book` 写状态时, 同步推到 [shared._bookData],
 * 桥接 LiveData 后 UI 在下一帧收到, 与原 `bookData.postValue(it)` 行为等价。
 *
 * # 待 app 端实现的方法 (Android-specific)
 *
 * 以下方法涉及 Android 专属依赖 (Uri / FileBook / AppWebDav / Toast / ReadBook),
 * 保留 app 端实现:
 * - [initData]: 调 BaseReadViewModel.upBook (依赖 FileBook / ImageLoader 等未下沉)
 * - [refreshBook]: 用 executeLazy + refreshWebDavBook (`Uri.isContentScheme` / `uri.path`)
 *   + loadBookInfo (BaseReadViewModel)
 * - [importWebFile] / [downloadWebFile] / [getArchiveFilesName] / [importBookFromArchive]:
 *   入参或返回 `android.net.Uri`, 且依赖 FileBook / ArchiveUtils
 * - [saveBook] / [getBook] / [downloadToLocal] / [uploadBook] / [clearCache]:
 *   依赖 `context.toastOnUi` / BookHelp.clearCache / ReadBook.clearTextChapter
 * - [changeToLocalBook]: 调 FileBook.mergeBook + loadChapterList (BaseReadViewModel)
 * - [openCommentDialog]: 用 AppCompatActivity / showDialogFragment / ReviewListDialog
 */
class BookInfoViewModel(application: Application) : BaseReadViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 供 shared 内部协程使用。
     *
     * - 平台专属逻辑 (Uri / FileBook / AppWebDav 等) 通过本类方法保留, 不下沉;
     * - shared 仅承担状态托管与无 Android 依赖的纯业务方法。
     */
    private val shared: BookInfoViewModelShared = BookInfoViewModelShared(
        scope = viewModelScope,
    )

    /**
     * 当前书籍 LiveData, 暴露给 [BookInfoActivity] observe。
     *
     * 内部订阅 [shared.bookData] (StateFlow<Book?>) 转发到 MutableLiveData:
     * - StateFlow 启动 collect 时立即收到当前值 (初始 null), 桥接时过滤 null;
     * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致。
     *
     * 注: 不用 `androidx.lifecycle.asLiveData()` 扩展 (项目未显式引入
     * lifecycle-livedata-ktx), 用 viewModelScope.launch + collect 自己桥接。
     */
    private val _bookData = MutableLiveData<Book>()
    val bookData: LiveData<Book> get() = _bookData

    /** 等待对话框显隐 LiveData, 内部订阅 [shared.waitDialogData] (过滤 null)。 */
    private val _waitDialogData = MutableLiveData<Boolean>()
    val waitDialogData: LiveData<Boolean> get() = _waitDialogData

    /** 动作事件 LiveData, 内部订阅 [shared.actionLive] (过滤 null)。 */
    private val _actionLive = MutableLiveData<String>()
    val actionLive: LiveData<String> get() = _actionLive

    init {
        // 订阅 shared 三个 StateFlow, 转发到 MutableLiveData (供 BookInfoActivity observe)
        // 一次性订阅, viewModelScope cancel 时自动结束
        viewModelScope.launch {
            shared.bookData.collect { book ->
                // 过滤 null: shared 初始 null, upEditBook 时 IntentData.book 可能 null
                // BookInfoActivity.showBook 接收 Book (非空), null 会 NPE, 与原 LiveData<Book>
                // 行为一致 (原 postValue(null) 也会传 null 给 observer)
                if (book != null) _bookData.postValue(book)
            }
        }
        viewModelScope.launch {
            shared.waitDialogData.collect { show ->
                // 过滤 null: shared 初始 null, 避免初始假触发 upWaitDialogStatus(false)
                if (show != null) _waitDialogData.postValue(show)
            }
        }
        viewModelScope.launch {
            shared.actionLive.collect { action ->
                // 过滤 null: shared 初始 null, 避免初始假触发 BookInfoActivity.actionLive.observe
                if (action != null) _actionLive.postValue(action)
            }
        }
    }

    /**
     * 当前书籍 (BaseReadViewModel 抽象属性, 由本类重写)。
     *
     * - getter: 读 `shared.bookData.value` (同步, 线程安全), BaseReadViewModel 的方法
     *   (loadBookInfo / loadChapterList / addToBookshelf / delBook) 读 curBook 时拿到最新值。
     * - setter: 写 `shared.upBook(value)` (同步), BaseReadViewModel 的方法通过
     *   `curBook = book` 写状态时, 同步推到 shared._bookData, 经 init 中的 collect
     *   桥接推到 _bookData.postValue, UI 在下一帧收到。
     *
     * 与原 `bookData.postValue(it)` 的差异:
     * - 原代码 setter 用 postValue 异步派发, getter 读 `bookData.value` 是异步缓存 (旧值);
     * - 改造后 setter 同步写 shared._bookData.value, getter 同步读 shared.bookData.value (新值)。
     * - BaseReadViewModel 的方法 setter 后不立即读 curBook, 行为兼容;
     * - 改造后 curBook 读拿到最新值, 比原 MutableLiveData.value 更可靠。
     */
    override var curBook: Book?
        get() = shared.bookData.value
        set(value) {
            shared.upBook(value)
        }

    /** 详情页无章节上下文, 弹书籍级评论 (paragraphIndex=-1) */
    override fun openCommentDialog(activity: AppCompatActivity) {
        val book = curBook ?: return
        activity.showDialogFragment(ReviewListDialog(book, null, -1))
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
                // 转发到 shared (无 Android 依赖, 已下沉)
                shared.refreshBookSourceName(book, curBookSource)
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
                val uri = bookWebDav.downloadRemoteBook(remoteBook).toUri()
                book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
                book.lastCheckTime = remoteBook.lastModify
            }
        }
    }

    // refreshBookSourceName 已下沉到 shared (shared.refreshBookSourceName)

    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        // 转发到 shared (无 Android 依赖, 已下沉)
        shared.loadGroup(groupId, success)
    }

    fun importWebFile(webFile: WebFile, success: ((Book) -> Unit)?) {
        execute {
            // 走 shared 同步状态, 经 collect 桥接推到 _waitDialogData
            shared.upWaitDialog(true)
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            val fileName = book.getExportFileName(webFile.suffix)
            val uri = FileBook.saveBookFile(webFile.url, fileName, curBookSource)
            changeToLocalBook(FileBook.mergeBook(FileBook.importLocalFile(uri), book))
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            when (it) {
                is NoBooksDirException -> shared.postAction("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it, true)
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            shared.upWaitDialog(false)
        }
    }

    fun downloadWebFile(webFile: WebFile, success: ((Uri) -> Unit)?) {
        execute {
            shared.upWaitDialog(true)
            val book = bookData.value ?: throw NoStackTraceException("book is null")
            val fileName = book.getExportFileName(webFile.suffix)
            FileBook.saveBookFile(webFile.url, fileName, curBookSource)
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            when (it) {
                is NoBooksDirException -> shared.postAction("selectBooksDir")
                else -> {
                    AppLog.put("DownloadWebFileError\n${it.localizedMessage}", it, true)
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            shared.upWaitDialog(false)
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
            shared.upWaitDialog(true)
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
            AppLog.put("importArchiveBook Error\n${it.localizedMessage}", it, true)
        }.onFinally {
            shared.upWaitDialog(false)
        }
    }

    fun topBook() {
        // 转发到 shared (无 Android 依赖, 已下沉)
        shared.topBook()
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
            // 走 shared.upBook 让 BaseReadViewModel 也能读到最新状态
            // (原代码 bookData.postValue(book) 直接写 LiveData, 改造后 _bookData 从
            //  shared.bookData.collect 桥接, 不能直接 postValue, 必须走 shared.upBook)
            shared.upBook(book)
        }.onError {
            AppLog.put("下载远程书籍<${book.name}>失败", it, true)
        }
    }

    fun uploadBook(book: Book) {
        execute {
            shared.upWaitDialog(true)
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
            shared.upWaitDialog(false)
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
        // 转发到 shared (无 Android 依赖, 已下沉)
        shared.upEditBook()
    }

    private fun changeToLocalBook(localBook: Book): Book {
        return FileBook.mergeBook(localBook, bookData.value).let {
            execute { loadChapterList(it) }
            inBookshelf = true
            it
        }
    }

}
