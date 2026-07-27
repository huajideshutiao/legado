package io.legado.app.ui.widget.dialog

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource

/**
 * 书籍变量对话框（原 BaseBook.showBookVariableDialog，去 entity 安卓渗漏后上移）。
 */
fun BaseBook.showBookVariableDialog(activity: AppCompatActivity, source: BaseSource?) {
    val sourceComment = when (source) {
        is BookSource -> source.variableComment
        else -> return
    }
    val defaultComment = """书籍变量可在js中通过book.getVariable("custom")获取"""
    val comment = if (sourceComment.isNullOrBlank()) {
        defaultComment
    } else {
        "${sourceComment}\n$defaultComment"
    }
    val variable = getCustomVariable()
    VariableDialog.show(
        activity,
        activity.getString(R.string.set_book_variable),
        variable,
        comment
    ) { v -> putCustomVariable(v) }
}

/**
 * 源变量对话框（原 BaseSource.showSourceVariableDialog，去 entity 安卓渗漏后上移）。
 * JS 的 source.showSourceVariableDialog() 经事件桥 SourceUiEventBridge 转调此扩展。
 */
fun BaseSource.showSourceVariableDialog(activity: AppCompatActivity?) {
    activity ?: return
    if (this !is BookSource) return
    val suffix = "源变量可在js中通过source.getVariable()获取"
    val comment =
        variableComment.takeIf { !it.isNullOrBlank() }?.let { "$it\n$suffix" } ?: suffix
    val variable = getVariable()
    VariableDialog.show(
        activity,
        activity.getString(R.string.set_source_variable),
        variable,
        comment
    ) { v -> setVariable(v) }
}
