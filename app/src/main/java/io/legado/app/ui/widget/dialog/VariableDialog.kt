package io.legado.app.ui.widget.dialog

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.ComposeDialog
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.theme.AppTheme

/**
 * 变量设置对话框（命令式，非 DialogFragment），走 ComposeDialog 宿主。
 */
object VariableDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        variable: String?,
        comment: String,
        onSave: (variable: String?) -> Unit
    ) {
        val dialog = ComposeDialog(activity)
        dialog.setComposeContent {
            val colors = AppTheme.colors
            var text by remember { mutableStateOf(variable ?: "") }
            val focusRequester = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = title,
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                AppOutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "variable",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .focusRequester(focusRequester)
                )
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(text = stringResource(R.string.variable_comment), color = colors.accent)
                    SelectionContainer {
                        Text(text = comment, color = colors.secondaryText)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Spacer(Modifier.weight(1f))
                    AppTextButton(text = stringResource(R.string.cancel)) { dialog.dismiss() }
                    AppTextButton(text = stringResource(R.string.ok)) {
                        onSave(text)
                        dialog.dismiss()
                    }
                }
            }
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

}
