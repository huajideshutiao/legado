package io.legado.app.ui.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppUnderlineTextField
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 通用文本输入对话框 (sharedUiMain, 供 Android/Desktop/iOS 复用)。
 *
 * 对照 app 端 alert DSL 的 editTextView + okButton + cancelButton 模式
 * (如 alertLocalPassword / showUserAgentDialog / importBookshelfAlert), 抽出共享 Compose 版本。
 * 输入框多行 (对齐原版 DialogEditTextBinding 的 AutoCompleteTextView, 原版各输入对话框均多行,
 * 可粘贴多行 JSON/URL 列表)。
 *
 * @param title 标题
 * @param message 副标题/说明 (可选, 显示在输入框上方)
 * @param initialValue 输入框初始值
 * @param hint 输入框 label
 * @param neutralButton 左置按钮 (可选, 对齐原版 alert DSL neutralButton 槽位, 如导入书单"选择文件";
 *        dismissOnClick 默认 false, 由调用方控制关闭时机)
 * @param onConfirm 确认回调, 携带用户输入文本 (空串也回调, 由调用方决定是否过滤)
 * @param onDismiss 取消/dismiss 回调
 */
@Composable
fun TextInputDialog(
    title: String,
    message: String? = null,
    initialValue: String = "",
    hint: String? = null,
    neutralButton: AlertButton? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
        okButton = AlertButton(text = stringResource(Res.string.ok)) {
            onConfirm(text)
        },
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)),
        neutralButton = neutralButton,
    ) {
        // 外围间距由 AppTextField 组件统一 (左右下各 4dp), 调用点不再叠加
        // 多行 (singleLine 默认 false), 对齐原版 DialogEditTextBinding 的 AutoCompleteTextView
        AppUnderlineTextField(
            value = text,
            onValueChange = { text = it },
            label = hint,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
