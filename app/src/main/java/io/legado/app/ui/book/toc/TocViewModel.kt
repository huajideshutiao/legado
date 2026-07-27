package io.legado.app.ui.book.toc


import android.app.Application
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.ReadBook
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeText
import kotlinx.coroutines.launch

/**
 * 目录页 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 核心业务编排 (initBook / upBookTocRule / reverseToc) 已下沉到 shared commonMain
 * [TocViewModelShared], 用 `StateFlow<Book?>` 替代 `MutableLiveData<Book>` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 `TocViewModelShared`:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` / `viewModelScope`),
 *   Kotlin 单继承无法同时继承 [TocViewModelShared];
 * - 平台专属逻辑通过 lambda 注入 [shared]:
 *   - [FileBook.getChapterList] 取本地文件书籍章节列表 (Android 专属, 未下沉)
 *   - [ReadBook.onChapterListUpdated] 通知单例 ReadBook 同步阅读状态 (ReadBook 未下沉)
 * - 仅 2 个 lambda 参数, 不违反"避免超多继承与参数传递"原则。
 *
 * # 调用方兼容
 *
 * [TocActivity] 调用方式保持不变:
 * - `viewModel.bookData.observe(this) { ... }` (LiveData)
 * - `viewModel.bookData.value?.bookUrl`
 * - `viewModel.bookUrl`
 * - `viewModel.initBook()` / `viewModel.upBookTocRule(book, complete)` / `viewModel.reverseToc(list)`
 * - `viewModel.saveBookmark(uri)` / `viewModel.saveBookmarkMd(uri)` (本类保留, 涉及 Uri/FileDoc/toast)
 *
 * # 状态桥接
 *
 * [bookData] 仍是 `LiveData<Book>` (供 Activity observe), 内部用 [viewModelScope]
 * 协程订阅 [shared.bookData] (StateFlow) 转发到 MutableLiveData:
 * - StateFlow 是 hot flow, collect 时立即收到当前值, 不会错过 initBook 推送的初始状态
 * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致
 * - 不用 `androidx.lifecycle.asLiveData()` 扩展: 项目未显式引入 lifecycle-livedata-ktx,
 *   用 viewModelScope.launch + collect 自己桥接确保编译通过
 *
 * # 待 app 端实现的方法
 *
 * [saveBookmark] / [saveBookmarkMd] 涉及 `android.net.Uri` + `FileDoc.fromUri` +
 * `context.toastOnUi`, 全部 Android 专属, 不下沉 commonMain。原实现完整保留。
 */
class TocViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 与两个平台专属 lambda。
     *
     * - `localChapterListProvider`: 调 [FileBook.getChapterList] 取本地书章节列表
     *   (FileBook 依赖 Android 专属的 Epub/Txt 解析器, 未下沉)
     * - `readBookChapterListUpdater`: 调 [ReadBook.onChapterListUpdated] 同步 ReadBook 单例
     *   (用 lambda 包装是因为 method reference 不接受默认参数 loadContent=true)
     */
    private val shared: TocViewModelShared = TocViewModelShared(
        scope = viewModelScope,
        localChapterListProvider = { FileBook.getChapterList(it) },
        readBookChapterListUpdater = { ReadBook.onChapterListUpdated(it) },
    )

    /**
     * 当前书籍 LiveData, 暴露给 [TocActivity] observe。
     *
     * 内部订阅 [shared.bookData] (StateFlow<Book?>) 转发到 MutableLiveData:
     * - StateFlow 启动 collect 时立即收到当前值, 不会错过 [initBook] 推送的初始状态;
     * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致。
     *
     * 注: 不用 `androidx.lifecycle.asLiveData()` 扩展 (项目未显式引入
     * lifecycle-livedata-ktx), 用 viewModelScope.launch + collect 自己桥接。
     */
    private val _bookData = MutableLiveData<Book>()
    val bookData: LiveData<Book> get() = _bookData

    /** 当前书籍 bookUrl, 转发到 [shared.bookUrl]。 */
    val bookUrl: String get() = shared.bookUrl

    init {
        // 订阅 shared.bookData (StateFlow), 把变化推到 _bookData (LiveData)
        // 一次性订阅, viewModelScope cancel 时自动结束
        viewModelScope.launch {
            shared.bookData.collect { book ->
                if (book != null) _bookData.postValue(book)
            }
        }
    }

    /**
     * 初始化书籍 (从 IntentData 取)。
     *
     * 转发到 [shared.initBook]。原 `bookData.postValue(it as Book)` 改为
     * `_bookData.value = book` (shared 内部 StateFlow.value 同步赋值, 经上方
     * `collect` 订阅推到 `_bookData.postValue`, 行为等价)。
     */
    fun initBook() = shared.initBook()

    /**
     * 更新书的 TOC 规则并刷新章节列表。
     *
     * 转发到 [shared.upBookTocRule]。原 `execute { ... }.onSuccess { ... }.onError { ... }`
     * 改为 shared 内部 `scope.launch(Dispatchers.IO) { try { ... } catch (e) { ... } }`,
     * 行为等价 (Coroutine.async 也是 try/catch 包装, onSuccess/onError 由 complete 回调等价表达)。
     *
     * @param book 待更新的书 (含新 tocUrl 等)
     * @param complete 完成回调, 入参 null 表示成功, 否则为捕获的异常
     */
    fun upBookTocRule(book: Book, complete: (Throwable?) -> Unit) =
        shared.upBookTocRule(book, complete)

    /**
     * 反转目录顺序 (用户点击反转按钮时)。
     *
     * 转发到 [shared.reverseToc]。原 `execute { book.config.reverseToc = !...; runCatching { insert } }`
     * 改为 shared 内部 `scope.launch(Dispatchers.IO) { ... }`, 行为等价。
     *
     * @param newToc 反转后的完整章节列表 (含 index 已重排)
     */
    fun reverseToc(newToc: List<BookChapter>) = shared.reverseToc(newToc)

    // region 以下两个方法涉及 android.net.Uri + FileDoc + context.toastOnUi, 保留 app 端实现

    /**
     * 导出书签为 JSON 文件 (用户选目录后调用)。
     *
     * 平台专属依赖:
     * - [Uri] (Android 专属)
     * - [FileDoc.fromUri] / [createFileIfNotExist] / [writeText] (Android 专属)
     * - [context.getString] / [context.toastOnUi] (Android 专属)
     *
     * 完整保留原实现, 行为不变。
     */
    fun saveBookmark(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.json"
            val doc = FileDoc.fromUri(treeUri, true)
            doc.createFileIfNotExist(fileName).writeText(
                GSON.toJson(
                    appDb.bookmarkDao.getByBook(book.name, book.author)
                )
            )
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    /**
     * 导出书签为 Markdown 文件 (用户选目录后调用)。
     *
     * 平台专属依赖:
     * - [Uri] (Android 专属)
     * - [FileDoc.fromUri] / [createFileIfNotExist] / [openOutputStream] (Android 专属)
     * - [context.getString] / [context.toastOnUi] (Android 专属)
     *
     * 完整保留原实现, 行为不变。
     */
    fun saveBookmarkMd(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.md"
            val treeDoc = FileDoc.fromUri(treeUri, true)
            val fileDoc = treeDoc.createFileIfNotExist(fileName)
                .openOutputStream()
                .getOrThrow()
            fileDoc.use { outputStream ->
                outputStream.write("## ${book.name} ${book.author}\n\n".toByteArray())
                appDb.bookmarkDao.getByBook(book.name, book.author).forEach {
                    outputStream.write("#### ${it.chapterName}\n\n".toByteArray())
                    outputStream.write("###### 原文\n ${it.bookText}\n\n".toByteArray())
                    outputStream.write("###### 摘要\n ${it.content}\n\n".toByteArray())
                }
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    // endregion
}
