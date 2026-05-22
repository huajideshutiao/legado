package io.legado.app.ui.widget.dialog

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.databinding.DialogVariableBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.okButton
import io.legado.app.utils.applyTint
import io.legado.app.utils.requestInputMethod

/**
 * 变量设置对话框
 * 已经从 BaseDialogFragment 重构为更轻量级的 alert 实现
 */
object VariableDialog {

    fun show(
        activity: AppCompatActivity,
        title: String,
        variable: String?,
        comment: String,
        onSave: (variable: String?) -> Unit
    ) {
        activity.alert(title = title) {
            val binding = DialogVariableBinding.inflate(activity.layoutInflater)
            binding.tvVariable.setText(variable)
            binding.tvComment.text = comment
            customView { binding.root }
            okButton {
                onSave(binding.tvVariable.text?.toString())
            }
            cancelButton()
        }.applyTint().requestInputMethod()
    }

}
