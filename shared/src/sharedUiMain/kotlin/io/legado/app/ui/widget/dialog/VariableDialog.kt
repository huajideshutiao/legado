package io.legado.app.ui.widget.dialog

// 对照原版 app 端 VariableDialog.kt + dialog_variable.xml 的 KMP 共享版。
// 原版是单变量编辑器: 一次编辑一个变量的原始文本 (书源变量 = getVariable() 的原始 JSON 文本;
// 书籍变量 = book.variable 的 "custom" 键), 不解析不校验, 确定时原样存字符串 (空串也允许)。
// 替代早前的双 Tab Map 键值编辑器 (key 只读 value 可编辑可增删, 经
// decodeStringMap/encodeStringMap 往返会破坏原始 JSON, 且书籍变量 Tab 数据会被丢)。
//
// 原版入口语义 (origin/quickjs BaseSource.showSourceVariableDialog / BaseBook.showBookVariableDialog):
//   - 书源变量: 标题 "设置源变量", 初始值 = getVariable() 原文, 注释 = variableComment + "源变量可在js中通过source.getVariable()获取",
//     确定 setVariable(v) 原样存字符串 (存储 key sourceVariable_{sourceKey}, 与 SourceCacheProviders 一致)
//   - 书籍变量: 标题 "设置书籍变量", 初始值 = getCustomVariable() (只读 variable 的 "custom" 键),
//     注释 = variableComment + "书籍变量可在js中通过book.getVariable(\"custom\")获取",
//     确定 putCustomVariable(v) 写回 "custom" 键 (其他键保留), 再持久化整个 variable JSON

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.set_book_variable
import legado.shared.generated.resources.set_source_variable
import legado.shared.generated.resources.variable_comment
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.ui.preview.LegadoThemePreview

/**
 * 书源变量对话框 (对照原版 VariableDialog.show + dialog_variable.xml):
 * 单个多行输入框编辑 [initialJson] (source.getVariable() 的原始 JSON 文本, 原样填入),
 * 确定时原样保存字符串 (不解析不校验, 空串也允许)。
 *
 * @param initialJson 初始值 = source.getVariable() 原文 (null 按空串填入)
 * @param comment 注释正文 = variableComment + "源变量可在js中通过source.getVariable()获取" (由调用方拼好)
 * @param onSave 确定回调, 参数为输入框原文 (原样存字符串)
 * @param onDismiss 取消回调
 */
@Composable
fun SourceVariableDialog(
    initialJson: String?,
    comment: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialJson.orEmpty()) }
    VariableEditDialogContent(
        title = stringResource(Res.string.set_source_variable),
        text = text,
        onTextChange = { text = it },
        comment = comment,
        onSave = { onSave(text) },
        onDismiss = onDismiss,
    )
}

/**
 * 书籍变量对话框 (对照原版 VariableDialog.show + dialog_variable.xml):
 * 只编辑 book.variable 的 "custom" 键 (getCustomVariable/putCustomVariable, 其他键保留),
 * 确定时原样保存字符串 (不解析不校验, 空串也允许)。
 *
 * @param initialCustom 初始值 = book.getCustomVariable() (custom 键原文, null 按空串填入)
 * @param comment 注释正文 = variableComment + "书籍变量可在js中通过book.getVariable(\"custom\")获取" (由调用方拼好)
 * @param onSave 确定回调, 参数为输入框原文 (调用方负责 putCustomVariable + 持久化)
 * @param onDismiss 取消回调
 */
@Composable
fun BookVariableDialog(
    initialCustom: String?,
    comment: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialCustom.orEmpty()) }
    VariableEditDialogContent(
        title = stringResource(Res.string.set_book_variable),
        text = text,
        onTextChange = { text = it },
        comment = comment,
        onSave = { onSave(text) },
        onDismiss = onDismiss,
    )
}

/**
 * 两个变量对话框共用正文 (对齐 dialog_variable.xml 布局):
 * TextInputEditText (hint "variable") + NestedScrollView(AccentTextView "变量注释" + tv_comment)。
 */
@Composable
private fun VariableEditDialogContent(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    comment: String?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        okButton = AlertButton(text = stringResource(Res.string.ok)) { onSave() },
        cancelButton = AlertButton(text = stringResource(Res.string.cancel)) { onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            // 变量原文编辑框 (对照 tv_variable, hint "variable"; 多行, 原文原样填入)
            AppOutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
                singleLine = false,
                placeholder = "variable",
            )
            // 注释区 (对照 dialog_variable.xml: AccentTextView @string/variable_comment + tv_comment)
            Text(
                text = stringResource(Res.string.variable_comment),
                color = DesignTokens.arcoBlue6,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                text = comment.orEmpty(),
                color = AppTheme.colors.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

// ===== @Preview 合并自 androidMain 的 widget/dialog/CommonDialogsPreviews.kt (VariableDialog (源变量/书籍变量, 对照原版单变量编辑器)) =====

// ---- VariableDialog (源变量/书籍变量, 对照原版单变量编辑器) ----

private val previewSourceVariable = """{
  "cookie": "session=abc123; uid=88888",
  "token": "eyJhbGciOiJIUzI1NiJ9.preview",
  "baseUrl": "https://preview.invalid"
}"""

private val previewBookVariable = """{
  "lastReadTime": "1700000000000",
  "chapterOffset": "12"
}"""

@Preview
@Composable
fun SourceVariableDialogPreview() = LegadoThemePreview {
    SourceVariableDialog(
        initialJson = previewSourceVariable,
        comment = "cookie\n源变量可在js中通过source.getVariable()获取",
        onSave = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun BookVariableDialogPreview() = LegadoThemePreview {
    BookVariableDialog(
        initialCustom = previewBookVariable,
        comment = """书籍变量可在js中通过book.getVariable("custom")获取""",
        onSave = {},
        onDismiss = {},
    )
}

@Preview
@Composable
fun VariableDialogDarkPreview() = LegadoThemePreview(dark = true) {
    SourceVariableDialog(
        initialJson = previewSourceVariable,
        comment = "源变量可在js中通过source.getVariable()获取",
        onSave = {},
        onDismiss = {},
    )
}
