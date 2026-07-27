package io.legado.app.ui.book.toc

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 目录页 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `TocViewModel(application: Application) : BaseViewModel(application)`:
 * - 核心业务编排 (initBook / upBookTocRule / reverseToc) 不依赖 Android 专属 API,
 *   仅依赖 [AppDbProviders] / [IntentData] / 协程, 可以下沉 commonMain 供多端复用。
 * - 状态用 [MutableStateFlow] 替代 `androidx.lifecycle.MutableLiveData` (LiveData 不可 KMP)。
 *   Android 宿主用 `viewModelScope.launch { shared.bookData.collect { ... } }` 把 StateFlow
 *   转发到 MutableLiveData, 调用方 `observe` / `.value` 用法不变 (项目未引入
 *   lifecycle-livedata-ktx, 不用 `StateFlow.asLiveData()` 扩展)。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [IntentData] 已下沉 commonMain, 直接复用跨 Activity 临时大数据传递容器。
 *
 * # 留 app 端实现的部分 (Android-specific)
 *
 * 以下两类平台专属逻辑通过构造函数 lambda 注入, 不在 commonMain 硬编码:
 * - [localChapterListProvider]: app 端用 `FileBook.getChapterList(book)` 拉取本地文件书籍
 *   章节列表。FileBook 依赖 Android 专属的 Epub/Jar/Txt 解析器, 未下沉。
 * - [readBookChapterListUpdater]: app 端用 `ReadBook.onChapterListUpdated(book)` 通知
 *   单例 ReadBook 同步阅读状态。ReadBook 单例本身未下沉 (ReadBookShared 缺该方法)。
 *
 * 此外 `saveBookmark` / `saveBookmarkMd` 整个方法保留 app 端 TocViewModel 实现:
 * - 入参为 `android.net.Uri`, commonMain 不可见;
 * - 内部用 `FileDoc.fromUri` + `createFileIfNotExist` + `writeText` + `context.toastOnUi`,
 *   全部 Android 专属。
 *
 * # 设计选择 (避免超多继承与参数传递)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式:
 * - app 端 TocViewModel `extends BaseViewModel`, 内部持有 [TocViewModelShared] 实例;
 * - 通过 lambda 注入两个平台专属回调, 仅 2 个参数不算"超多";
 * - 转发 `initBook` / `upBookTocRule` / `reverseToc` / `bookUrl` / `bookData` 到 shared;
 * - Android 专属方法 (saveBookmark / saveBookmarkMd) 保留在 TocViewModel。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param localChapterListProvider 平台专属: 从本地文件书籍取章节列表
 *   (app 端用 `FileBook.getChapterList(book)`)
 * @param readBookChapterListUpdater 平台专属: 通知 ReadBook 单例章节列表已更新
 *   (app 端用 `ReadBook.onChapterListUpdated(book)`)
 */
class TocViewModelShared(
    private val scope: CoroutineScope,
    private val localChapterListProvider: (Book) -> List<BookChapter>,
    private val readBookChapterListUpdater: (Book) -> Unit,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    // region 状态流: 外部只读 StateFlow, 适配 Compose 重组 / Android asLiveData 桥接

    private val _bookData = MutableStateFlow<Book?>(null)
    val bookData: StateFlow<Book?> = _bookData.asStateFlow()
    // endregion

    /**
     * 当前书籍 bookUrl (initBook 写入, 后续 DAO 查询章节列表用)。
     *
     * 用 `var ... private set` 保持外部只读: app 端 TocViewModel 通过 getter 转发暴露。
     */
    var bookUrl: String = ""
        private set

    /**
     * 从 [IntentData.book] 取 book 写入 [bookData] 状态流 + [bookUrl]。
     *
     * 对照原 TocViewModel.initBook:
     * - 用 `_bookData.value = book` 替代 `bookData.postValue(book)`。
     *   StateFlow.value 同步赋值, app 端 `viewModelScope.launch { collect { ... } }` 桥接到
     *   MutableLiveData 后, LiveData observer 在主线程下一帧收到通知, 与原 postValue
     *   行为等价 (postValue 也是异步切主线程派发)。
     * - IntentData.book 类型为 BaseBook?, 这里强转 Book (与原 `it as Book` 一致)。
     */
    fun initBook() {
        IntentData.book?.let {
            // 强转 Book (对照原 app 端 `it as Book`, IntentData.book 类型为 BaseBook?,
            // 实际运行场景中总是 Book 类型, 此处保持原强转行为不变)
            val book = it as Book
            _bookData.value = book
            bookUrl = book.bookUrl
        }
    }

    /**
     * 更新书的 TOC 规则并刷新章节列表。
     *
     * 对照原 TocViewModel.upBookTocRule:
     * 1. `appDb.bookDao.update(book)` 持久化更新书信息 (tocUrl 等)
     * 2. [localChapterListProvider] 由宿主实现 (app 端用 FileBook.getChapterList) 取新章节列表
     * 3. `appDb.bookChapterDao.delByBook(book.bookUrl)` 删旧章节
     * 4. `appDb.bookChapterDao.insert(*chapters)` 写新章节
     * 5. `appDb.bookDao.update(book)` 再次持久化 (FileBook.getChapterList 可能改 book 字段)
     * 6. [readBookChapterListUpdater] 由宿主实现 (app 端用 ReadBook.onChapterListUpdated)
     *    通知 ReadBook 单例同步阅读状态
     * 7. `_bookData.value = book` 推送状态 (替代原 `bookData.postValue(book)`)
     * 8. complete 回调: null 表示成功, Throwable 表示失败
     *
     * 协程切到 [Dispatchers.IO] 是因为 DAO 方法是 suspend (Room 内部已切线程,
     * 但保留 IO 调度器与原 `execute { ... }` 默认 Dispatchers.IO 行为一致)。
     *
     * @param book 待更新的书 (含新 tocUrl 等)
     * @param complete 完成回调, 入参 null 表示成功, 否则为捕获的异常
     */
    fun upBookTocRule(book: Book, complete: (Throwable?) -> Unit) {
        scope.launch(IoDispatcher) {
            try {
                appDb.bookDao.update(book)
                val chapters = localChapterListProvider(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
                appDb.bookDao.update(book)
                readBookChapterListUpdater(book)
                _bookData.value = book
                complete.invoke(null)
            } catch (e: Throwable) {
                complete.invoke(e)
            }
        }
    }

    /**
     * 反转目录顺序 (用户点击反转按钮时调用)。
     *
     * 对照原 TocViewModel.reverseToc:
     * - 仅当 newToc 非空且 bookData 有值时执行
     * - `book.config.reverseToc` 翻转 (在内存中, 不会主动 bookDao.update 持久化;
     *   持久化由后续其他流程触发, 与原 TocViewModel.reverseToc 行为一致)
     * - `runCatching` 包住 insert: 非书架书可能没有 books 行, FK 约束会让 INSERT 抛异常,
     *   尽力持久化即可, UI 已由调用方 (TocActivity.reverseChapterList) 直接反转。
     *
     * @param newToc 反转后的完整章节列表 (含 index 已重排)
     */
    fun reverseToc(newToc: List<BookChapter>) {
        if (newToc.isEmpty()) return
        val book = _bookData.value ?: return
        scope.launch(IoDispatcher) {
            book.config.reverseToc = !book.config.reverseToc
            // 非书架书可能没有 books 行, FK 约束会让 INSERT 抛异常,
            // 尽力持久化即可, UI 已由调用方直接反转。
            runCatching {
                appDb.bookChapterDao.insert(*newToc.toTypedArray())
            }
        }
    }
}
