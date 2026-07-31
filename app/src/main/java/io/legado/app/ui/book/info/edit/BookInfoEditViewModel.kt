package io.legado.app.ui.book.info.edit

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Book
import io.legado.app.help.IntentData
import io.legado.app.model.ReadBook

/**
 * 书籍信息编辑 ViewModel (app 端薄壳, 业务下沉 [BookInfoEditViewModelShared])。
 *
 * # 背景
 *
 * 对照 [BookInfoViewModelShared] 同款组合委托模式:
 * - 核心业务 (loadBook / saveBook) 已下沉 shared commonMain, 本类仅持有 [shared] 实例并转发;
 * - 注入两个参数:
 *   1. [scope] = `viewModelScope` (BaseViewModel 继承自 AndroidViewModel, 已有 viewModelScope);
 *   2. [readBookUpdater] = `{ if (ReadBook.book?.bookUrl == it.bookUrl) ReadBook.book = it }`,
 *      替代原 saveBook 内 `if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.book = book`
 *      同步 ReadBook 单例的副作用 (ReadBook 依赖阅读流, 未下沉)。
 * - [book] 字段改为 getter 委托 `shared.book` (原 `var book` 外部仅读 + 修改属性, 不重赋值,
 *   getter 委托行为兼容)。
 *
 * # 异常处理
 *
 * 原 `onError { if (it is SQLiteConstraintException) ... else ... }` 已下沉到
 * [BookInfoEditViewModelShared.saveBook], 通过类名匹配区分 (不直接引用
 * android.database.sqlite), 本类无需重复处理。
 *
 * # 调用方
 *
 * [BookInfoEditActivity] 通过 `viewModel.book` / `viewModel.loadBook()` /
 * `viewModel.saveBook(book, bookUrl) { ... }` 访问, 签名零改动, 行为完全一致。
 */
class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 业务核心 shared 实例 (组合委托)。
     *
     * 注入 [viewModelScope] (随 ViewModel 生命周期自动取消) +
     * [readBookUpdater] (同步 ReadBook 单例, 内含 bookUrl 相等判断与原 saveBook 一致)。
     */
    private val shared = BookInfoEditViewModelShared(
        scope = viewModelScope,
        readBookUpdater = { if (ReadBook.book?.bookUrl == it.bookUrl) ReadBook.book = it },
    )

    /**
     * 当前编辑的书籍 (委托 [shared.book])。
     *
     * 外部 (BookInfoEditActivity) 通过 `viewModel.book` 读取并修改其属性
     * (如 `viewModel.book?.customCoverUrl = coverUrl`), 不重赋值整个引用,
     * getter 委托行为与原 `var book` 兼容。
     */
    val book: Book? get() = shared.book

    /**
     * 加载书籍 (转发到 [shared.loadBook])。
     */
    fun loadBook() {
        (IntentData.book as? Book)?.let(shared::loadBook)
    }

    /**
     * 保存书籍 (转发到 [shared.saveBook])。
     *
     * 签名与原方法一致, 调用方 (BookInfoEditActivity.saveData) 零改动。
     *
     * @param book 待保存的书籍
     * @param bookUrl 新的 bookUrl (null 或与 book.bookUrl 相同走 update, 不同走 delete+insert)
     * @param success 保存成功回调
     */
    fun saveBook(book: Book, bookUrl: String?, success: (() -> Unit)?) =
        shared.saveBook(book, bookUrl, success)
}
