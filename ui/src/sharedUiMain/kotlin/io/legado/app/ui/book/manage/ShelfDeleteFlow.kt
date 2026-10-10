package io.legado.app.ui.book.manage

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.root.PlatformCapabilityProviders

/**
 * 移出书架前的待决确认 (对照原版 BookInfoActivity.deleteBook: 本地书带"删除源文件"勾选)。
 *
 * @param book 待移出的书 (本地书才显示勾选框)
 * @param initialDeleteOriginal 勾选初始值 (删除源文件偏好)
 */
class ShelfDeleteRequest(
    val book: Book,
    val initialDeleteOriginal: Boolean,
)

/**
 * 发起移出书架 (移出记录 + 按勾选删本地书源文件), 确认框与勾选值按端分工:
 * - 确认框由能力面自己弹的端 (安卓系统 alert): 直接交 `PlatformCapabilities.deleteBookshelfBook`;
 * - 关掉"移除书籍时确认"偏好: 按记忆的勾选值直接执行;
 * - 其余: 返回待决请求, 由调用方用 [ShelfDeleteConfirmDialog] 渲染确认框, 用户选完把勾选值交
 *   [confirmShelfDelete]。
 *
 * @return 非空 = 调用方还需弹确认框
 */
fun startShelfDelete(book: Book, onComplete: (Boolean?) -> Unit): ShelfDeleteRequest? {
    val platform = PlatformCapabilityProviders.get()
    if (platform.confirmsDeleteBookInCapability()) {
        platform.deleteBookshelfBook(book, platform.getDeleteBookOriginal(), onComplete)
        return null
    }
    if (!PreferenceProviders.get().getBoolean(PreferKey.bookInfoDeleteAlert, true)) {
        confirmShelfDelete(book, platform.getDeleteBookOriginal(), onComplete)
        return null
    }
    return ShelfDeleteRequest(book, platform.getDeleteBookOriginal())
}

/**
 * 勾选值已定: 只有弹过勾选框的本地书回写"删除源文件"偏好, 移出记录与删源文件交唯一入口
 * `PlatformCapabilities.deleteBookshelfBook` (onComplete: null=移出成功, false=失败)。
 */
fun confirmShelfDelete(book: Book, deleteOriginal: Boolean, onComplete: (Boolean?) -> Unit) {
    val platform = PlatformCapabilityProviders.get()
    if (book.isLocal) platform.setDeleteBookOriginal(deleteOriginal)
    platform.deleteBookshelfBook(book, deleteOriginal, onComplete)
}

/** 移出书架确认框 (本地书带"删除源文件"勾选); [request] 为空时不渲染。 */
@Composable
fun ShelfDeleteConfirmDialog(
    request: ShelfDeleteRequest?,
    onConfirm: (Book, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    request?.let { pending ->
        DeleteConfirmDialog(
            showCheckbox = pending.book.isLocal,
            initialDeleteOriginal = pending.initialDeleteOriginal,
            onConfirm = { onConfirm(pending.book, it) },
            onDismiss = onDismiss,
        )
    }
}
