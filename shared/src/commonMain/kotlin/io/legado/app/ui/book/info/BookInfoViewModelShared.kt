package io.legado.app.ui.book.info

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 书籍详情页 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `BookInfoViewModel(application: Application) : BaseReadViewModel(application)`:
 * - 核心业务编排 (loadGroup / topBook / refreshBookSourceName / upEditBook) 不依赖 Android 专属 API,
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
 * 以下方法涉及 Android 专属依赖 (Uri / FileBook / AppWebDav / Toast / ReadBook),
 * 整个方法保留 app 端 BookInfoViewModel 实现:
 * - initData: 调 BaseReadViewModel.upBook (依赖 FileBook / ImageLoader 等未下沉)
 * - refreshBook: 用 executeLazy + refreshWebDavBook (`Uri.isContentScheme` / `uri.path`)
 *   + loadBookInfo (BaseReadViewModel, 依赖 FileBook / WebBook / execute)
 * - importWebFile / downloadWebFile / getArchiveFilesName / importBookFromArchive:
 *   入参或返回 `android.net.Uri`, 且依赖 FileBook (Epub/Jar/Txt 解析器) / ArchiveUtils
 * - saveBook / getBook / downloadToLocal / uploadBook / clearCache:
 *   依赖 `context.toastOnUi` / BookHelp.clearCache / ReadBook.clearTextChapter
 * - changeToLocalBook: 调 FileBook.mergeBook + loadChapterList (BaseReadViewModel)
 * - openCommentDialog: 用 AppCompatActivity / showDialogFragment / ReviewListDialog
 *
 * # 设计选择 (避免超多继承与参数传递)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseReadViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用**组合委托**模式:
 * - app 端 BookInfoViewModel `extends BaseReadViewModel`, 内部持有 [BookInfoViewModelShared] 实例;
 * - 仅注入 [scope] 一个参数 (Android = `viewModelScope`), 不算"超多";
 * - 转发 loadGroup / topBook / refreshBookSourceName / upEditBook 到 shared;
 * - Android 专属方法保留在 BookInfoViewModel。
 *
 * # BaseReadViewModel.curBook 与 shared._bookData 的关系
 *
 * BaseReadViewModel 的方法 (loadBookInfo / loadChapterList / addToBookshelf / delBook)
 * 会通过抽象 `curBook` setter 写 book 状态。BookInfoViewModel 重写 curBook getter/setter,
 * 内部读写 [shared._bookData], 使 BaseReadViewModel 的方法访问的就是 shared 的状态源:
 * - getter: 读 `shared.bookData.value` (同步, 线程安全)
 * - setter: 写 `shared.upBook(value)` (同步, 线程安全, StateFlow 内部 atomic)
 * 然后 app 端 `_bookData` (LiveData) 通过 `viewModelScope.launch { collect { postValue } }`
 * 异步镜像, 供 BookInfoActivity.observe 使用, 与原 LiveData.postValue 行为等价。
 *
 * 与原 `MutableLiveData.postValue` 的细微差异:
 * - postValue 在主线程下一帧派发 (异步);
 * - StateFlow.value 立即更新, 经 app 端 `collect { postValue }` 桥接后,
 *   LiveData observer 在下一帧收到, 与原 postValue 等价。
 * - BaseReadViewModel 的方法读 curBook 时拿到的是最新值 (同步), 比原 MutableLiveData
 *   读取 `value` (异步缓存) 更可靠, 但原代码 curBook setter 后不立即读 curBook, 行为兼容。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class BookInfoViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    // region 状态流: 外部只读 StateFlow, 适配 Compose 重组 / Android asLiveData 桥接

    /**
     * 当前书籍状态 (替代原 app 端 `MutableLiveData<Book>`)。
     *
     * 由 [upBook] 写入, 由宿主 collect 桥接到 LiveData 或直接用于 Compose。
     */
    private val _bookData = MutableStateFlow<Book?>(null)
    val bookData: StateFlow<Book?> = _bookData.asStateFlow()

    /**
     * 等待对话框显隐状态 (替代原 app 端 `MutableLiveData<Boolean>`)。
     *
     * - `null` 表示从未触发 (默认值), 桥接到 LiveData 时跳过 null 避免初始假触发。
     * - `true` / `false` 由 [upWaitDialog] 写入, 对应 importWebFile / downloadWebFile /
     *   importBookFromArchive / uploadBook 等长任务的开始 / 结束。
     */
    private val _waitDialogData = MutableStateFlow<Boolean?>(null)
    val waitDialogData: StateFlow<Boolean?> = _waitDialogData.asStateFlow()

    /**
     * 动作事件 (替代原 app 端 `MutableLiveData<String>`)。
     *
     * 典型值: `"selectBooksDir"` (importWebFile / downloadWebFile 抛
     * `NoBooksDirException` 时下发, 由 BookInfoActivity observe 后启动
     * `localBookTreeSelect.launch` 选默认书目录)。
     *
     * 用 SharedFlow 对齐 postValue: 连续两次同一个 action (再次导入还是缺书目录)
     * 用 StateFlow 会被去重, 第二次就再也弹不出目录选择。
     */
    private val _actionLive = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionLive: SharedFlow<String> = _actionLive.asSharedFlow()
    // endregion

    // region 状态写入接口 (供 app 端 BookInfoViewModel 调用)

    /**
     * 写 book 状态。
     *
     * 供 app 端 BookInfoViewModel.curBook setter 调用: BaseReadViewModel 的方法
     * (loadBookInfo / loadChapterList / addToBookshelf / delBook) 会通过 `curBook = book`
     * 写状态, 经此方法同步到 [_bookData]。
     *
     * StateFlow.value 同步赋值, 线程安全 (内部 atomic)。
     */
    fun upBook(book: Book?) {
        _bookData.value = book
    }

    /**
     * 写等待对话框状态。
     *
     * 供 app 端 importWebFile / downloadWebFile / importBookFromArchive / uploadBook 调用
     * (这些方法保留 app 端, 因为依赖 Uri / FileBook / AppWebDav 等 Android 专属 API)。
     *
     * @param show true=显示等待对话框, false=隐藏
     */
    fun upWaitDialog(show: Boolean) {
        _waitDialogData.value = show
    }

    /**
     * 写动作事件 (供 app 端 importWebFile / downloadWebFile 出错时调用)。
     *
     * 典型值: `"selectBooksDir"` (`NoBooksDirException` 时下发, 由 BookInfoActivity
     * observe 后启动 `localBookTreeSelect.launch` 选默认书目录)。
     */
    fun postAction(action: String) {
        _actionLive.tryEmit(action)
    }
    // endregion

    // region 业务方法 (无 Android 依赖, 下沉 commonMain)

    /**
     * 加载分组名 (对照原 BookInfoViewModel.loadGroup)。
     *
     * 原 `execute { appDb.bookGroupDao.getGroupNames(groupId).joinToString(",") }
     *        .onSuccess { success.invoke(it) }`
     * 改为 `scope.launch(Dispatchers.IO) { try { ... success.invoke(names) }
     *        catch (e) { AppLog.put(...) } }`,
     * 行为等价 (Coroutine.async 内部也是 try/catch 包装 onSuccess / onError)。
     *
     * 协程切到 [Dispatchers.IO] 是因为 DAO 方法是 suspend (Room 内部已切线程,
     * 但保留 IO 调度器与原 `execute { ... }` 默认 `Dispatchers.IO` 行为一致)。
     *
     * @param groupId 分组 id (Book.group 位掩码, 多选时为多组或运算)
     * @param success 成功回调, 入参为分组名拼接字符串 (无分组时为空串)
     */
    fun loadGroup(groupId: Long, success: (String?) -> Unit) {
        scope.launch(IoDispatcher) {
            try {
                val names = appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
                success.invoke(names)
            } catch (e: Throwable) {
                AppLog.put("加载分组名失败\n${e.message}", e)
            }
        }
    }

    /**
     * 置顶书籍 (对照原 BookInfoViewModel.topBook)。
     *
     * 原 `execute { bookData.value?.let { book ->
     *        book.order = appDb.bookDao.minOrder() - 1
     *        book.durChapterTime = System.currentTimeMillis()
     *        appDb.bookDao.update(book)
     *      } }`
     * 改为 `scope.launch(Dispatchers.IO) { ... }`, 行为等价。
     *
     * 内部读 [_bookData].value (同步), 桌面端调用前需先 [upBook] 同步状态。
     */
    fun topBook() {
        val book = _bookData.value ?: return
        scope.launch(IoDispatcher) {
            book.order = appDb.bookDao.minOrder() - 1
            book.durChapterTime = systemCurrentTimeMillis()
            appDb.bookDao.update(book)
        }
    }

    /**
     * 更新书源名 (对照原 BookInfoViewModel.refreshBookSourceName)。
     *
     * 原 private 方法, 内部用 `curBookSource?.let { source -> ... }`。
     * 下沉后 shared 无 curBookSource (在 BaseReadViewModel), 改为接受 source 参数,
     * 由 app 端 refreshBook 调用时传入 `curBookSource`。
     *
     * @param book 待更新的书 (修改其 originName 字段)
     * @param source 当前书源 (从 app 端 `curBookSource` 传入, null 时直接返回)
     */
    fun refreshBookSourceName(book: Book, source: BookSource?) {
        source?.let {
            if (book.originName != it.bookSourceName) {
                book.originName = it.bookSourceName
            }
        }
    }

    /**
     * 编辑页保存后从 IntentData 同步刷新 book (对照原 BookInfoViewModel.upEditBook)。
     *
     * 原 `bookData.postValue(IntentData.book as? Book)` 改为
     * `_bookData.value = IntentData.book as? Book`, 行为等价 (经 app 端
     * `collect { postValue }` 桥接到 LiveData)。
     *
     * 注: IntentData.book 类型为 `BaseBook?`, 这里强转 Book (与原 `as? Book` 一致)。
     * 若 IntentData.book 为 null, [_bookData].value 设为 null, app 端 collect 桥接时
     * 过滤 null 不触发 LiveData observer, 与原 `postValue(null)` 行为略有差异
     * (原 MutableLiveData<Book>.postValue(null) 会触发 observer 收到 null)。
     * 但 BookInfoActivity.showBook 接收 Book (非空), null 会 NPE,
     * 实际运行时 upEditBook 由 BookInfoEditActivity RESULT_OK 触发,
     * 那时 IntentData.book 必然非 null, 故过滤 null 是安全的。
     */
    fun upEditBook() {
        _bookData.value = IntentData.book as? Book
    }
    // endregion
}
