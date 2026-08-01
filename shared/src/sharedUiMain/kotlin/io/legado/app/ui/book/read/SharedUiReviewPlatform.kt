package io.legado.app.ui.book.read

import io.legado.app.help.toast.Toasters

/**
 * [ReviewPlatform] 的 sharedUi 实现（桌面/共享 UI 路径）。
 *
 * 走 [Toasters] + 硬编码中文文案，与 app 端 ReviewViewModel / 桌面原 ReviewListScreenModel 内联文案一致。
 * Android 端走 [AndroidReviewPlatform]（app 端 BottomSheet Dialog 路径），本实现仅服务于 shared [io.legado.app.ui.route.ReviewListRoute]。
 */
object SharedUiReviewPlatform : ReviewPlatform {

    override fun toastOnUi(msg: String) {
        Toasters.get().toast(msg)
    }

    override fun noCurrentBook(): String = "无当前书籍"

    override fun noSource(): String = "无书源"

    override fun loadFailed(cause: String?): String = "评论加载失败: $cause"

    override fun operationFailed(cause: String?): String = "操作失败: $cause"

    override fun noActionRule(): String = "书源未配置此操作规则"
}
