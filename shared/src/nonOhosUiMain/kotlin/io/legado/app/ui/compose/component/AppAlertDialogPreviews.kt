package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import io.legado.app.ui.preview.AppPreview
import androidx.compose.ui.unit.dp
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * [AppAlertDialog.kt] 中各 Composable 的 @Preview。
 * - [AppAlertDialog]: alert 对话框 (含 Dialog 窗口)
 * - [AppAlertDialogContent]: alert 正文 (不含窗口, 适合预览样式)
 * - [AppSelectorDialog]: 列表选择型 alert
 * - [AppSelectorList]: 列表正文
 *
 * 注: 含 Dialog/Popup 的 Composable 在 IDE Preview 中可能渲染受限,
 * 故优先 Preview 不含窗口的 [AppAlertDialogContent] / [AppSelectorList]。
 */

@AppPreview
@Composable
fun AppAlertDialogContentPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppAlertDialogContent(
            onDismissRequest = {},
            title = "对话框标题",
            message = "这是对话框的正文内容, 用于提示用户确认操作。",
            okButton = AlertButton(text = "确定"),
            cancelButton = AlertButton(text = "取消"),
            widthFraction = 0.9f,
        )
    }
}

@AppPreview
@Composable
fun AppAlertDialogContentDarkPreview() = LegadoThemePreview(dark = true) {
    Box(Modifier.padding(16.dp)) {
        AppAlertDialogContent(
            onDismissRequest = {},
            title = "深色对话框",
            message = "深色主题下的对话框内容。",
            okButton = AlertButton(text = "确定"),
            cancelButton = AlertButton(text = "取消"),
        )
    }
}

@AppPreview
@Composable
fun AppAlertDialogContentWithCustomViewPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppAlertDialogContent(
            onDismissRequest = {},
            title = "带自定义视图",
            okButton = AlertButton(text = "确定"),
            cancelButton = AlertButton(text = "取消"),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("自定义内容行 1")
                Spacer(Modifier.height(8.dp))
                Text("自定义内容行 2")
            }
        }
    }
}

@AppPreview
@Composable
fun AppAlertDialogPreview() = LegadoThemePreview {
    AppAlertDialog(
        onDismissRequest = {},
        title = "对话框标题",
        message = "完整对话框 (含 Dialog 窗口)。",
        okButton = AlertButton(text = "确定"),
        cancelButton = AlertButton(text = "取消"),
    )
}

@AppPreview
@Composable
fun AppSelectorListPreview() = LegadoThemePreview {
    Box(Modifier.padding(16.dp)) {
        AppSelectorList(
            items = listOf("选项一", "选项二", "选项三", "选项四"),
            onItemClick = {},
        )
    }
}

@AppPreview
@Composable
fun AppSelectorDialogPreview() = LegadoThemePreview {
    AppSelectorDialog(
        onDismissRequest = {},
        title = "选择一项",
        items = listOf("选项一", "选项二", "选项三"),
        onItemSelected = {},
    )
}
