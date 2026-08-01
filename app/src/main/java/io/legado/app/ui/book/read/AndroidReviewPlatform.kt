package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.utils.toastOnUi

/**
 * [ReviewPlatform] 的 Android 实现。
 * 走 `context.toastOnUi` + 硬编码中文文案，与原 app 端 ReviewViewModel 内联文案一一对应。
 */
class AndroidReviewPlatform(private val context: Context) : ReviewPlatform {

    override fun toastOnUi(msg: String) {
        context.toastOnUi(msg)
    }

    override fun noCurrentBook(): String = "无当前书籍"

    override fun noSource(): String = "无书源"

    override fun loadFailed(cause: String?): String = "评论加载失败: $cause"

    override fun operationFailed(cause: String?): String = "操作失败: $cause"

    override fun noActionRule(): String = "书源未配置此操作规则"
}
