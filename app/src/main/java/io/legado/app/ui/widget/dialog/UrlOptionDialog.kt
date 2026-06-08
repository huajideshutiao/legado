package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.databinding.DialogUrlOptionEditBinding
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.viewbindingdelegate.viewBinding

class UrlOptionDialog : BaseDialogFragment(R.layout.dialog_url_option_edit) {

    private val binding by viewBinding(DialogUrlOptionEditBinding::bind)
    var callback: ((String) -> Unit)? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.editMethod.setFilterValues("POST", "GET")
        binding.editCharset.setFilterValues(AppConst.charsets)
        binding.tvOk.setOnClickListener {
            callback?.invoke(GSON.toJson(getUrlOption()))
            dismiss()
        }
    }

    private fun getUrlOption(): AnalyzeUrl.UrlOption {
        val urlOption = AnalyzeUrl.UrlOption()
        urlOption.useWebView = binding.cbUseWebView.isChecked
        urlOption.method = binding.editMethod.text.toString()
        urlOption.charset = binding.editCharset.text.toString()
        urlOption.headers = binding.editHeaders.text.toString().ifBlank { null }
            ?.let { GSON.fromJsonObject<Map<String, Any?>>(it).getOrNull() }
        urlOption.body = binding.editBody.text.toString()
        urlOption.retry = binding.editRetry.text.toString().ifBlank { null }?.toIntOrNull()
        urlOption.type = binding.editType.text.toString()
        urlOption.webJs = binding.editWebJs.text.toString()
        urlOption.js = binding.editJs.text.toString()
        return urlOption
    }

    companion object {
        fun show(fragmentManager: FragmentManager, callback: (String) -> Unit) {
            UrlOptionDialog().apply { this.callback = callback }
                .show(fragmentManager, "urlOptionDialog")
        }
    }
}
