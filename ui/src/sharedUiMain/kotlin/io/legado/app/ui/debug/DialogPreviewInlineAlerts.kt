package io.legado.app.ui.debug

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.KeywordHighlight
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.bookmark.BookmarkShareCardDialog
import io.legado.app.ui.book.keyword.KeywordHighlightEditDialog
import io.legado.app.ui.book.manage.DeleteConfirmDialog
import io.legado.app.ui.book.read.config.ChineseConverterSelectorDialog
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.dialog.NumberPickerDialog
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.bookmark
import legado.ui.generated.resources.chinese_converter
import legado.ui.generated.resources.keyword_highlight_add

/**
 * alert 形制预览清单: 标题 + 正文 + 按钮行。
 *
 * 每一条都是该形制里一个真实弹窗的最小实例 (只给必要参数, 回调为空操作), 改按钮行/标题/正文
 * 边距时逐个对照。
 *
 * 形制样例 (为对照形态差异而造的实例) 用用途说明当名字; 真实产品弹窗引用它自己的标题文案。
 */
internal fun buildAlertEntries(): List<DialogPreviewEntry.Inline> = listOf(
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_basic", nameText("alert (标题+正文+双按钮)"), "AppAlertDialog") { onDismiss ->
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = "标题",
            message = "正文内容",
            okButton = AlertButton("确定") { onDismiss() },
            cancelButton = AlertButton("取消") { onDismiss() },
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_three_buttons", nameText("alert (三个按钮)"), "AppAlertDialog") { onDismiss ->
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = "标题",
            message = "正文内容",
            okButton = AlertButton("确定") { onDismiss() },
            cancelButton = AlertButton("取消") { onDismiss() },
            neutralButton = AlertButton("中性", dismissOnClick = false) {},
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_custom_content", nameText("alert (自定义正文槽)"), "AppAlertDialog") { onDismiss ->
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = "标题",
            okButton = AlertButton("确定") { onDismiss() },
            cancelButton = AlertButton("取消") { onDismiss() },
        ) {
            Text(
                text = "自定义正文槽: 复选框、输入框等由调用方自排",
                color = AppTheme.colors.primaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(DesignTokens.spacingDefault),
            )
        }
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_disabled_ok", nameText("alert (确定键置灰)"), "AppAlertDialog") { onDismiss ->
        AppAlertDialog(
            onDismissRequest = onDismiss,
            title = "标题",
            message = "确定键不可点 (输入未填完的形制)",
            okButton = AlertButton("确定", enabled = false) {},
            cancelButton = AlertButton("取消") { onDismiss() },
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_delete_with_checkbox", nameText("删除确认 (带复选框)"), "DeleteConfirmDialog") { onDismiss ->
        DeleteConfirmDialog(
            showCheckbox = true,
            initialDeleteOriginal = false,
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_delete_plain", nameText("删除确认 (无复选框)"), "DeleteConfirmDialog") { onDismiss ->
        DeleteConfirmDialog(
            showCheckbox = false,
            initialDeleteOriginal = false,
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_text_input", nameText("文本输入"), "TextInputDialog") { onDismiss ->
        TextInputDialog(
            title = "文本输入",
            message = "说明文字",
            initialValue = "",
            hint = "请输入",
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_text_input_neutral", nameText("文本输入 (带中性键)"), "TextInputDialog") { onDismiss ->
        TextInputDialog(
            title = "文本输入",
            initialValue = "已有内容",
            neutralButton = AlertButton("中性", dismissOnClick = false) {},
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_number_picker", nameText("数值选择"), "NumberPickerDialog") { onDismiss ->
        NumberPickerDialog(
            title = "数值选择",
            value = 30,
            range = 0..100,
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_number_picker_neutral", nameText("数值选择 (带中性键)"), "NumberPickerDialog") { onDismiss ->
        NumberPickerDialog(
            title = "数值选择",
            value = 5,
            range = 0..30,
            neutralButtonText = "重置",
            onNeutral = {},
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_selector", nameText("列表选择"), "AppSelectorDialog") { onDismiss ->
        AppSelectorDialog(
            onDismissRequest = onDismiss,
            title = "列表选择",
            items = listOf("选项一", "选项二", "选项三"),
            onItemSelected = { onDismiss() },
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_bookmark", titleOf(Res.string.bookmark), "BookmarkDialog") { onDismiss ->
        BookmarkDialog(
            bookmark = Bookmark(),
            showDelete = true,
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
            onDelete = { onDismiss() },
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_keyword_highlight", titleOf(Res.string.keyword_highlight_add), "KeywordHighlightEditDialog") { onDismiss ->
        KeywordHighlightEditDialog(
            rule = KeywordHighlight(),
            showDelete = true,
            onConfirm = { onDismiss() },
            onDismiss = onDismiss,
            onDelete = { onDismiss() },
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_text_dialog", nameText("文本查看"), "TextDialog") { onDismiss ->
        TextDialog(
            title = "文本查看",
            content = "长文本查看弹窗的正文。\n第二行。",
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_text_dialog_markdown", nameText("文本查看 (Markdown)"), "TextDialog") { onDismiss ->
        TextDialog(
            title = "Markdown",
            content = "# 标题\n\n- 列表项\n- 列表项\n\n**加粗** 与 `代码`",
            markdown = true,
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_wait", nameText("等待框"), "WaitDialog") { onDismiss ->
        WaitDialog(visible = true, message = "加载中", onDismissRequest = onDismiss)
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_code", nameText("代码编辑/查看"), "CodeDialog") { onDismiss ->
        CodeDialog(
            code = "function preview() {\n  return \"legado\";\n}",
            disableEdit = false,
            onDismiss = onDismiss,
            onSave = { onDismiss() },
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_code_readonly", nameText("代码查看 (只读)"), "CodeDialog") { onDismiss ->
        CodeDialog(
            code = """{"preview": true}""",
            disableEdit = true,
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_chinese_converter", titleOf(Res.string.chinese_converter), "ChineseConverterSelectorDialog") { onDismiss ->
        ChineseConverterSelectorDialog(
            currentType = 0,
            onChanged = { onDismiss() },
            onDismiss = onDismiss,
        )
    },
    DialogPreviewEntry.Inline(DialogPreviewGroup.ALERT, "alert_bookmark_share", nameText("书签分享卡片"), "BookmarkShareCardDialog") { onDismiss ->
        BookmarkShareCardDialog(bookmark = Bookmark(bookText = "预览正文"), onDismiss = onDismiss)
    },
)
