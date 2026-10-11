package io.legado.app.ui.book.import.local

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.importLocalFile
import io.legado.app.utils.toastOnUi

/**
 * 导入本地书籍的 Android ViewModel。
 *
 * 目录状态机 (目录栈/面包屑/过滤/排序/扫描/空态) 在 data/commonMain [ImportBookShared]
 * (四端一份, 目录列举经 [io.legado.app.ui.book.import.ImportBookDirHost] 注入);
 * 本类只保留上架编排 (导入失败 toast 需要 Context)。
 */
class ImportBookViewModel(application: Application) : BaseViewModel(application) {

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

}
