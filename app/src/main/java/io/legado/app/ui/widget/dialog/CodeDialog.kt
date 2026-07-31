package io.legado.app.ui.widget.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.code.CodeTextField
import io.legado.app.ui.compose.component.code.rememberFullCodeSyntax
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import org.jetbrains.compose.resources.stringResource

class CodeDialog() : BaseComposeDialogFragment() {


    constructor(code: String, disableEdit: Boolean = true, requestId: String? = null) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
        }
    }

    private var code by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code = IntentData.get<String>(arguments?.getString("code")).orEmpty()
    }

    @Composable
    override fun Content() {
        val disableEdit = arguments?.getBoolean("disableEdit") == true
        val syntax = rememberFullCodeSyntax()
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = if (disableEdit) "code view" else "",
                onBack = { dismissAllowingStateLoss() }
            ) {
                if (!disableEdit) {
                    IconButton(onClick = ::onSave) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = stringResource(R.string.action_save),
                            tint = AppTheme.colors.primaryText
                        )
                    }
                }
            }
            CodeTextField(
                value = code,
                onValueChange = { code = it },
                syntax = syntax,
                readOnly = disableEdit,
                // 对齐原版 CodeView 内嵌呈现: 无下划线, 四周 12dp
                showIndicator = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(12.dp)
            )
        }
    }

    private fun onSave() {
        val requestId = arguments?.getString("requestId")
        (parentFragment as? Callback)?.onCodeSave(code, requestId)
            ?: (activity as? Callback)?.onCodeSave(code, requestId)
        dismiss()
    }

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)
    }
}
