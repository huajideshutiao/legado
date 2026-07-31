package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.migrateTo
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.fileBook.FileBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.launch
import splitties.init.appCtx
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * 书架管理 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 核心批量管理方法 (upCanUpdate / updateBook / deleteBook / changeSource / clearCache /
 * loadCacheFiles) 已下沉到 shared commonMain [BookshelfManageViewModelShared], 用
 * `MutableStateFlow<T>` 替代 `MutableLiveData<T>` (LiveData 不可 KMP)。
 *
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承 [BookshelfManageViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [BookshelfManageViewModelShared];
 * - 平台专属逻辑通过 [BookshelfManagePlatform] 聚合接口注入 [shared]:
 *   [AndroidBookshelfManagePlatform] 包装 `Book.migrateTo` / `BookHelp.clearCache` /
 *   `BookHelp.getChapterFiles` / `FileBook.deleteBook` / `R.string.clear_cache_success`。
 *
 * # 留 app 端的方法 (Android-specific)
 *
 * - [saveAllUseBookSourceToFile]: 用 `context.filesDir` + `FileUtils.createFileWithReplace`
 *   + `GSON.writeToOutputStream`, 文件 I/O + GSON 序列化均未下沉, 留 app 端。
 * - [exportBookshelf]: 用 `context.filesDir` + `FileUtils` + `FileOutputStream` +
 *   `kotlinx.serialization.json.Json`, 文件 I/O 未下沉, 留 app 端。
 *
 * # 状态桥接 (StateFlow → LiveData)
 *
 * 三个 MutableLiveData 保留供 Activity observe, 内部用 [viewModelScope] 协程订阅
 * [shared] 的 StateFlow 转发到 LiveData (参考 [io.legado.app.ui.book.changesource.ChangeBookSourceViewModel.searchStateData] 桥接模式):
 * - [batchChangeSourceState] ← [BookshelfManageViewModelShared.batchChangeSourceState];
 * - [batchChangeSourceProcessLiveData] ← [BookshelfManageViewModelShared.batchChangeSourceProcess];
 * - [upAdapterLiveData] ← [BookshelfManageViewModelShared.upAdapter]。
 *
 * StateFlow 是 hot flow, collect 时立即收到当前值, 不会错过状态切换; `postValue` 异步
 * 切到主线程, 与原 LiveData.postValue 行为一致。
 *
 * # 调用方兼容
 *
 * [BookshelfManageActivity] 调用方式保持不变:
 * - `viewModel.groupId = ...` / `viewModel.groupName = ...` (var 属性转发);
 * - `viewModel.batchChangeSourceState.observe(...)` / `batchChangeSourceProcessLiveData.observe(...)`
 *   / `upAdapterLiveData.observe(...)` (LiveData, 桥接订阅 StateFlow);
 * - `viewModel.batchChangeSourceCoroutine?.cancel()` (getter 转发);
 * - `viewModel.cacheChapters[...].add/size/let` (getter 转发可变映射);
 * - `viewModel.upCanUpdate(...) / updateBook(...) / deleteBook(...) / changeSource(...) /
 *   clearCache(...) / loadCacheFiles(...)` (方法转发);
 * - `viewModel.saveAllUseBookSourceToFile(...) / exportBookshelf(...)` (本类直接实现)。
 */
class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 与 [AndroidBookshelfManagePlatform]。
     *
     * 平台专属依赖通过聚合接口注入, 避免在 commonMain 硬编码 Book.migrateTo /
     * BookHelp.clearCache / BookHelp.getChapterFiles / FileBook.deleteBook /
     * R.string.clear_cache_success (全部 Android 专属)。
     */
    private val shared: BookshelfManageViewModelShared = BookshelfManageViewModelShared(
        scope = viewModelScope,
        platform = AndroidBookshelfManagePlatform(),
    )

    /** 书架分组 ID, 转发到 [shared.groupId]。 */
    var groupId: Long
        get() = shared.groupId
        set(value) {
            shared.groupId = value
        }

    /** 书架分组名, 转发到 [shared.groupName]。 */
    var groupName: String?
        get() = shared.groupName
        set(value) {
            shared.groupName = value
        }

    /**
     * 批量换源进行中状态, 暴露给 Activity observe。
     *
     * 内部订阅 [shared.batchChangeSourceState] (StateFlow<Boolean>) 转发到 MutableLiveData:
     * - StateFlow 启动 collect 时立即收到当前值, 不会错过搜索状态切换;
     * - `postValue` 异步切到主线程, 与原 LiveData.postValue 行为一致。
     */
    val batchChangeSourceState = MutableLiveData<Boolean>()

    /**
     * 批量换源进度文案, 暴露给 Activity observe。
     *
     * 内部订阅 [shared.batchChangeSourceProcess] (StateFlow<String>) 转发到 MutableLiveData。
     */
    val batchChangeSourceProcessLiveData = MutableLiveData<String>()

    /**
     * 当前批量换源协程, 转发到 [shared.batchChangeSourceCoroutine]。
     *
     * Activity waitDialog.onCancelListener 调 `viewModel.batchChangeSourceCoroutine?.cancel()`
     * 取消换源, getter 转发即可。
     */
    val batchChangeSourceCoroutine: Coroutine<Unit>? get() = shared.batchChangeSourceCoroutine

    /**
     * 章节缓存列表更新通知, 暴露给 Activity observe。
     *
     * 内部订阅 [shared.upAdapter] (StateFlow<String>) 转发到 MutableLiveData, Activity
     * observe 后 `refreshTick++` 触发 Compose 重组。
     */
    val upAdapterLiveData = MutableLiveData<String>()

    /**
     * 当前缓存加载协程, 转发到 [shared.loadChapterCoroutine]。
     *
     * 注: Activity 未直接访问, 仅保留 getter 转发以维持原签名兼容。
     */
    val loadChapterCoroutine: Coroutine<Unit>? get() = shared.loadChapterCoroutine

    /**
     * 每本书已缓存的章节 URL 集合, 转发到 [shared.cacheChapters]。
     *
     * Activity 多处直接读写 (`viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)`
     * / `?.size` / `?.let { ... }`), getter 转发暴露可变映射, 行为不变。
     */
    val cacheChapters: HashMap<String, HashSet<String>> get() = shared.cacheChapters

    init {
        // 订阅 shared 的 StateFlow, 把变化推到 LiveData (一次性订阅, viewModelScope cancel 时自动结束)
        viewModelScope.launch {
            shared.batchChangeSourceState.collect { searching ->
                batchChangeSourceState.postValue(searching)
            }
        }
        viewModelScope.launch {
            shared.batchChangeSourceProcess.collect { process ->
                batchChangeSourceProcessLiveData.postValue(process)
            }
        }
        viewModelScope.launch {
            shared.upAdapter.collect { bookUrl ->
                upAdapterLiveData.postValue(bookUrl)
            }
        }
    }

    /** 批量更新 canUpdate, 转发到 [shared.upCanUpdate]。 */
    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        shared.upCanUpdate(books, canUpdate)
    }

    /** 更新书籍, 转发到 [shared.updateBook]。 */
    fun updateBook(vararg book: Book) {
        shared.updateBook(*book)
    }

    /** 删除书籍, 转发到 [shared.deleteBook]。 */
    fun deleteBook(books: List<Book>, deleteOriginal: Boolean = false) {
        shared.deleteBook(books, deleteOriginal)
    }

    /**
     * 导出当前书架使用的全部书源到文件 (留 app 端, Android-specific)。
     *
     * # 实现细节保持
     *
     * - 路径 `context.filesDir/shareBookSource.json`;
     * - `FileUtils.delete(path)` 删旧 + `FileUtils.createFileWithReplace(path)` 建新
     *   (createFileWithReplace 内部 replace 文件避免并发写入冲突);
     * - `appDb.bookDao.getAllUseBookSource()` 取所有 books 引用过的书源;
     * - `GSON.writeToOutputStream(it, sources)` 写文件 (GSON 序列化未下沉, 留 app 端);
     * - 成功回调 [success], 失败 `context.toastOnUi(it.stackTraceStr)`。
     *
     * 业务在 IO 跑, 回调切到 mainDispatcher (与原 BaseViewModel.execute 一致)。
     *
     * @param success 成功回调, 参数为生成的 shareBookSource.json 文件
     */
    fun saveAllUseBookSourceToFile(success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    /** 批量换源, 转发到 [shared.changeSource]。 */
    fun changeSource(books: List<Book>, source: BookSource) {
        shared.changeSource(books, source)
    }

    /** 清除书籍缓存, 转发到 [shared.clearCache]。 */
    fun clearCache(books: List<Book>) {
        shared.clearCache(books)
    }

    /**
     * 导出书架 (留 app 端, Android-specific)。
     *
     * # 实现细节保持
     *
     * - 路径 `context.filesDir/bookshelf.json`;
     * - `FileUtils.delete(path)` + `FileUtils.createFileWithReplace(path)` 同 [saveAllUseBookSourceToFile];
     * - 用 `kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }`
     *   对齐原 GSON 行为 (prettyPrint + 2 空格缩进 + 不序列化 null 字段);
     * - `buildJsonArray { ... }` 手工构建 JSON 数组 (字段顺序与原 GSON 序列化保持一致);
     * - `FileOutputStream(file).use { ... }` 写文件;
     * - books 为空抛 [NoStackTraceException];
     * - 成功回调 [success], 失败 `context.toastOnUi("导出书籍出错\n${it.localizedMessage}")`。
     *
     * @param books 待导出的书籍列表 (null 抛 NoStackTraceException)
     * @param success 成功回调, 参数为生成的 bookshelf.json 文件
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun exportBookshelf(books: List<Book>?, success: (file: File) -> Unit) {
        execute {
            books?.let {
                val path = "${context.filesDir}/bookshelf.json"
                FileUtils.delete(path)
                val file = FileUtils.createFileWithReplace(path)
                // 对齐原 GSON 行为: prettyPrint + 2 空格缩进 + 不序列化 null 字段
                val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
                val jsonArray = buildJsonArray {
                    books.forEach {
                        add(buildJsonObject {
                            put("bookUrl", it.bookUrl)
                            put("tocUrl", it.tocUrl)
                            put("origin", it.origin)
                            put("originName", it.originName)
                            put("name", it.name)
                            put("author", it.author)
                            it.kind?.let { v -> put("kind", v) }
                            it.coverUrl?.let { v -> put("coverUrl", v) }
                            it.customCoverUrl?.let { v -> put("customCoverUrl", v) }
                            it.intro?.let { v -> put("intro", v) }
                            it.customIntro?.let { v -> put("customIntro", v) }
                            put("type", it.type)
                            it.wordCount?.let { v -> put("wordCount", v) }
                        })
                    }
                }
                FileOutputStream(file).use { out ->
                    out.write(
                        json.encodeToString(JsonArray.serializer(), jsonArray)
                            .toByteArray(Charsets.UTF_8)
                    )
                }
                file
            } ?: throw NoStackTraceException("书籍不能为空")
        }.onSuccess {
            success(it)
        }.onError {
            context.toastOnUi("导出书籍出错\n${it.localizedMessage}")
        }
    }

    /** 加载缓存文件列表, 转发到 [shared.loadCacheFiles]。 */
    fun loadCacheFiles(books: List<Book>) {
        shared.loadCacheFiles(books)
    }

}

/**
 * Android 端 [BookshelfManagePlatform] 实现 (顶级类, 供 shared Route + app ViewModel 共用)。
 *
 * 委托 [Book.migrateTo] / [BookHelp.clearCache] / [BookHelp.getChapterFiles] /
 * [FileBook.deleteBook] / `R.string.clear_cache_success`。
 * 原 inner class 通过 `context` (BaseViewModel 提供) 访问 Android Context;
 * 顶级类无外类 context, 改用 [appCtx] (Application Context), 与原行为等价。
 */
class AndroidBookshelfManagePlatform : BookshelfManagePlatform {

    /** 委托 [Book.migrateTo], 迁移旧书进度/分组/自定义字段到新书。 */
    override fun migrateBook(oldBook: Book, newBook: Book, toc: List<BookChapter>): Book {
        return oldBook.migrateTo(newBook, toc)
    }

    /** 委托 [BookHelp.clearCache], 删除该书所有章节缓存文件。 */
    override fun clearCache(book: Book) {
        BookHelp.clearCache(book)
    }

    /** 委托 [BookHelp.getChapterFiles], 列出已缓存的章节文件名集合。 */
    override fun getChapterFiles(book: Book): HashSet<String> {
        return BookHelp.getChapterFiles(book)
    }

    /** 委托 [FileBook.deleteBook], 删除本地书源文件 (含压缩包子书场景)。 */
    override fun deleteLocalBook(book: Book, deleteOriginal: Boolean) {
        FileBook.deleteBook(book, deleteOriginal)
    }

    /** 解析 R.string.clear_cache_success 为字符串, 供 shared Toasters.toast 显示。 */
    override val clearCacheSuccessMessage: String
        get() = appCtx.getString(R.string.clear_cache_success)
}

/** 安卓宿主启动早期注册 BookshelfManage 平台 provider。 */
fun registerAndroidBookshelfManagePlatform() {
    BookshelfManagePlatformProviders.register(AndroidBookshelfManagePlatform())
}
