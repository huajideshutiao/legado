package io.legado.app.ui.association

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.i18n.androidAppString
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.help.book.addType
import io.legado.app.model.webBook.WebBook.getBookInfoByUrlAwait
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AddToBookshelfHelper {

    fun add(
        navigator: AppNavigator,
        activity: FragmentActivity,
        bookUrl: String,
        finishOnDismiss: Boolean = false,
    ) {
        if (bookUrl.isBlank()) {
            activity.toastOnUi("url不能为空")
            if (finishOnDismiss) activity.finish()
            return
        }
        val waitDialog = WaitDialog.from(activity).setText(androidAppString("add_to_bookshelf"))
        var cancelled = false
        waitDialog.onCancelListener = {
            cancelled = true
            activity.toastOnUi(androidAppString("cancel"))
            if (finishOnDismiss) activity.finish()
        }
        waitDialog.show()
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { getBookInfoByUrlAwait(bookUrl) }
            }.onSuccess { book ->
                if (cancelled) return@onSuccess
                waitDialog.dismissSafe()
                // 通过路由传递书籍信息
                navigator.push(AppRoute.BookInfo(book.apply { addType(BookType.notShelf) }
                    .toRouteRef()))
                if (finishOnDismiss) activity.finish()
            }.onFailure { e ->
                if (cancelled) return@onFailure
                AppLog.put("添加书籍 $bookUrl 出错", e)
                waitDialog.dismissSafe()
                activity.toastOnUi(e.localizedMessage ?: "")
                if (finishOnDismiss) activity.finish()
            }
        }
    }
}
