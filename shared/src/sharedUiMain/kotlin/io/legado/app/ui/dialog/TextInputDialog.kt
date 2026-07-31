package io.legado.app.ui.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 通用单行文本输入对话框 (sharedUiMain, 供 Android/Desktop/iOS 复用)。
 *
 * 对照 app 端 alert DSL 的 editTextView + okButton + cancelButton 模式
 * (如 alertLocalPassword / showUserAgentDialog), 抽出共享 Compose 版本。
 *
 * @param title 标题
 * @param message 副标题/说明 (可选, 显示在输入框上方)
 * @param initialValue 输入框初始值
 * @param hint 输入框 label
 * @param onConfirm 确认回调, 携带用户输入文本 (空串也回调, 由调用方决定是否过滤)
 * @param onDismiss 取消/dismiss 回调
 */
@Composable
fun TextInputDialog(
    title: String,
    message: String? = null,
    initialValue: String = "",
    hint: String? = null,
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
    ) {
        // 输入框: padding 在 fillMaxWidth 前, 让 OutlinedTextField 含边框与 title/message 的 24dp 对齐
        AppOutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = hint,
            singleLine = true,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
        )
    }
}
