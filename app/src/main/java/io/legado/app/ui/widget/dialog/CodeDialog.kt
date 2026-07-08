package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.disableEdit
import io.legado.app.utils.dpToPx

class CodeDialog() : BaseDialogFragment(0) {

    constructor(code: String, disableEdit: Boolean = true, requestId: String? = null) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
        }
    }

    private lateinit var codeView: CodeView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dp12 = 12.dpToPx()
        return LinearLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            // 复用 dialog_title_bar 模板: attachToActivity=false / displayHomeAsUp=false / fitStatusBar=false,
            // 避免 TitleBar 污染宿主 Activity ActionBar 导致返回箭头关闭宿主界面
            addView(inflater.inflate(R.layout.dialog_title_bar, this, false))
            addView(CodeView(requireContext()).apply {
                codeView = this
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { weight = 1f }
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp12, dp12, dp12, dp12)
            })
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (arguments?.getBoolean("disableEdit") == true) {
            setupTitleBar(title = "code view")
            codeView.disableEdit()
        } else {
            initMenu()
        }
        codeView.addLegadoPattern()
        codeView.addJsonPattern()
        codeView.addJsPattern()
        arguments?.getString("code")?.let {
            codeView.text = IntentData.get(it)
        }
    }

    private fun initMenu() {
        setupTitleBar(menuRes = R.menu.code_edit) {
            when (it?.itemId) {
                R.id.menu_save -> {
                    codeView.text?.toString()?.let { code ->
                        val requestId = arguments?.getString("requestId")
                        (parentFragment as? Callback)?.onCodeSave(code, requestId)
                            ?: (activity as? Callback)?.onCodeSave(code, requestId)
                    }
                    dismiss()
                }
            }
            return@setupTitleBar true
        }
    }

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)
    }
}
