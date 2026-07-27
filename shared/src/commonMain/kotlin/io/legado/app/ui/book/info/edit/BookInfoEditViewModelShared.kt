package io.legado.app.ui.book.info.edit

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书籍信息编辑 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `BookInfoEditViewModel(application: Application) : BaseViewModel(application)`:
 * - 核心业务 (loadBook / saveBook) 不依赖 Android 专属 API, 仅依赖 [AppDbProviders] /
 *   [IntentData] / 协程 + 一个 `ReadBook.book` 同步副作用 (通过 [readBookUpdater] lambda 注入),
 *   可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - 原 `execute { ... }.onSuccess { success?.invoke() }.onError { AppLog.put(...) }`
 *   改为 `scope.launch(Dispatchers.IO) { try { ... success?.invoke() }
 *   catch (e: Throwable) { AppLog.put(...) } }`, 行为等价 (Coroutine.async 内部
 *   也是 try/catch 包装 onSuccess / onError)。
 *
 * # ReadBook 单例同步 (未下沉, lambda 注入)
 *
 * 原代码 `if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.book = book` 在
 * saveBook 开头同步 `ReadBook` 单例 (避免编辑后阅读页拿旧 book)。
 * `ReadBook` 单例依赖大量 Android 阅读流状态 (TextChapter / ChapterProvider 等),
 * 未下沉。下沉后改由 [readBookUpdater] lambda 注入:
 * - app 端实现: `{ if (ReadBook.book?.bookUrl == it.bookUrl) ReadBook.book = it }`,
 *   语义与原完全一致 (含 bookUrl 相等判断, 不相等时不写)。
 * - desktop 端阅读流未与编辑流联动, 传 `{}` no-op。
 *
 * # SQLiteConstraintException 处理 (不直接引用 android.database.sqlite)
 *
 * 原代码 `if (it is SQLiteConstraintException)` 区分日志文案 (相同书名作者 vs 通用失败)。
 * `android.database.sqlite.SQLiteConstraintException` 是 Android 专属类, commonMain 不可直接引用,
 * 改为通过类名匹配 (`e::class.simpleName == "SQLiteConstraintException"`),
 * 行为等价 (Room 抛出的异常类名固定为 `SQLiteConstraintException`)。
 *
 * # 设计选择 (组合委托, 与 BookInfoViewModelShared 一致)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用**组合委托**模式:
 * - app 端 BookInfoEditViewModel `extends BaseViewModel`, 内部持有 [BookInfoEditViewModelShared] 实例;
 * - 仅注入 [scope] / [readBookUpdater] 两个参数 (Android = `viewModelScope` /
 *   `{ if (ReadBook.book?.bookUrl == it.bookUrl) ReadBook.book = it }`), 不算"超多";
 * - 转发 loadBook / saveBook 到 shared; `book` 字段暴露为 `shared.book`。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param readBookUpdater 同步 ReadBook 单例的 lambda (app 端实现内含 bookUrl 相等判断,
 *   desktop 端 no-op)
 */
class BookInfoEditViewModelShared(
    private val scope: CoroutineScope,
    private val readBookUpdater: (Book) -> Unit,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前编辑的书籍 (对照原 app 端 `BookInfoEditViewModel.book`)。
     *
     * 由 [loadBook] 写入 (从 [IntentData.book] 取), 由 [BookInfoEditActivity.upView] /
     * 桌面端 LaunchedEffect 读取初始化编辑态。
     */
    var book: Book? = null
        private set

    /**
     * 加载书籍 (对照原 BookInfoEditViewModel.loadBook)。
     *
     * 原 `book = IntentData.book as? Book` 改为同语义赋值, 行为完全一致。
     * 注: IntentData.book 类型为 `BaseBook?`, 这里强转 Book (与原 `as? Book` 一致)。
     */
    fun loadBook() {
        book = IntentData.book as? Book
    }

    /**
     * 保存书籍 (对照原 BookInfoEditViewModel.saveBook)。
     *
     * 原 `execute { if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.book = book;
     *   if (bookUrl != null && bookUrl != book.bookUrl) { appDb.bookDao.delete(book);
     *   book.bookUrl = bookUrl; appDb.bookDao.insert(book) } else appDb.bookDao.update(book) }
     *   .onSuccess { success?.invoke() }.onError { if (it is SQLiteConstraintException) ...
     *   else ... }`
     * 改为 `scope.launch(Dispatchers.IO) { try { ... withContext(Dispatchers.Main) {
     *   success?.invoke() } } catch (e: Throwable) { ... } }`。
     *
     * # success 回调调度器
     *
     * 原 `execute { ... }.onSuccess { ... }` 的 onSuccess 在 `executeContext = Main` 调度器
     * 执行 (见 [io.legado.app.help.coroutine.Coroutine] 默认 executeContext = mainDispatcher)。
     * app 端 BookInfoEditActivity 的 success 回调内调 `setResult` / `finish`, 必须在 Main 线程。
     * 故 shared 内用 `withContext(Dispatchers.Main) { success?.invoke() }` 切回 Main,
     * 行为与原完全一致。desktop 端 onSaved 回调 (路由切换) 在 Main 调度器执行也无问题。
     *
     * 步骤:
     * 1. 调 [readBookUpdater] 同步 ReadBook 单例 (app 端 lambda 内含 bookUrl 相等判断,
     *    不相等时不写, 与原 `if (ReadBook.book?.bookUrl == book.bookUrl)` 等价);
     * 2. bookUrl 变更 (非 null 且与 book.bookUrl 不同): delete 旧 + 修改 bookUrl + insert 新
     *    (主键 bookUrl 变更, 不能直接 update);
     * 3. bookUrl 未变更: 直接 update;
     * 4. 成功回调 [success] (切到 [Dispatchers.Main], 与原 onSuccess 调度器一致);
     * 5. 异常按 [isSQLiteConstraintException] 区分日志文案 (相同书名作者 vs 通用失败)。
     *
     * @param book 待保存的书籍 (调用前已修改 name/author/type/coverUrl/intro 等字段)
     * @param bookUrl 新的 bookUrl (与 book.bookUrl 不同则 delete+insert, 否则 update;
     *   null 视为与 book.bookUrl 相同走 update 分支)
     * @param success 保存成功回调 (app 端 setResult + finish, 在 Main 调度器执行)
     */
    fun saveBook(book: Book, bookUrl: String?, success: (() -> Unit)?) {
        scope.launch(IoDispatcher) {
            try {
                // 1. 同步 ReadBook 单例 (app 端 lambda 内含 bookUrl 相等判断)
                readBookUpdater.invoke(book)
                // 2/3. bookUrl 变更: delete 旧 + insert 新; 否则 update
                if (bookUrl != null && bookUrl != book.bookUrl) {
                    appDb.bookDao.delete(book)
                    book.bookUrl = bookUrl
                    appDb.bookDao.insert(book)
                } else {
                    appDb.bookDao.update(book)
                }
                // 4. 成功回调 (切到 Main 调度器, 与原 execute.onSuccess 在 executeContext=Main 一致;
                //    app 端 success 内调 setResult/finish 必须在 Main 线程)
                if (success != null) {
                    withContext(Dispatchers.Main) { success.invoke() }
                }
            } catch (e: Throwable) {
                // 5. 异常日志: 按 SQLiteConstraintException 区分文案 (类名匹配, 不直接引用 android.database.sqlite)
                if (isSQLiteConstraintException(e)) {
                    AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$e", e, true)
                } else {
                    AppLog.put("书籍信息保存失败\n$e", e, true)
                }
            }
        }
    }

    /**
     * 判断异常是否为 SQLiteConstraintException (不直接引用 android.database.sqlite)。
     *
     * Room 在主键/唯一约束冲突时抛 `android.database.sqlite.SQLiteConstraintException`,
     * 该类是 Android 专属, commonMain 不可直接 `is` 引用。改为通过类名匹配:
     * - `e::class.simpleName` 反射取运行时类简名 (Android 运行时即 `SQLiteConstraintException`);
     * - `e::class.qualifiedName` 取全限定名作 fallback (跨平台稳健)。
     *
     * 非 Android 平台 (desktop 端使用 JVM SQLite 如 xerial jdbc, 抛
     * `org.sqlite.SQLiteException` 等) 类名不匹配, 走通用失败文案, 行为合理。
     */
    private fun isSQLiteConstraintException(e: Throwable): Boolean {
        val simpleName = e::class.simpleName
        val qualifiedName = e::class.qualifiedName
        return simpleName == "SQLiteConstraintException"
            || qualifiedName == "android.database.sqlite.SQLiteConstraintException"
    }
}
