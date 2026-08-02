package io.legado.app.ui.widget.dialog

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 变量设置对话框
 * 已经由 BaseDialogFragment 重构为更轻量级的 alert 实现 (Compose alert DSL)
 */
object VariableDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        variable: String?,
        comment: String,
        onSave: (variable: String?) -> Unit
    ) {
        val text = mutableStateOf(variable ?: "")
        val dialog = activity.alert(title = title) {
            customView {
                val colors = AppTheme.colors
                val focusRequester = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                // 内容对齐原版 dialog_variable.xml: 水平 16dp (arco_spacing_lg),
                // 注释区 4dp (arco_spacing_xs)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    AppOutlinedTextField(
                        value = text.value,
                        onValueChange = { text.value = it },
                        label = "variable",
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                    Text(
                        text = stringResource(R.string.variable_comment),
                        color = colors.accent,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    SelectionContainer {
                        Text(
                            text = comment,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }
            okButton {
                onSave(text.value)
            }
            cancelButton()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

}
