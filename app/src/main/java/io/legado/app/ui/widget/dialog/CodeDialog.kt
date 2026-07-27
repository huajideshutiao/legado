package io.legado.app.ui.widget.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.lib.theme.space
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.disableEdit

class CodeDialog() : BaseComposeDialogFragment() {


    constructor(code: String, disableEdit: Boolean = true, requestId: String? = null) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
        }
    }

    private var codeView: CodeView? = null

    @Composable
    override fun Content() {
        val disableEdit = arguments?.getBoolean("disableEdit") == true
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
            AndroidView(
                factory = { ctx ->
                    CodeView(ctx).apply {
                        codeView = this
                        val dp12 = ctx.space.md
                        setPadding(dp12, dp12, dp12, dp12)
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        if (disableEdit) disableEdit()
                        addLegadoPattern()
                        addJsonPattern()
                        addJsPattern()
                        setText(IntentData.get(arguments?.getString("code")))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            )
        }
    }

    private fun onSave() {
        codeView?.text?.toString()?.let { code ->
            val requestId = arguments?.getString("requestId")
            (parentFragment as? Callback)?.onCodeSave(code, requestId)
                ?: (activity as? Callback)?.onCodeSave(code, requestId)
        }
        dismiss()
    }

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)
    }
}
