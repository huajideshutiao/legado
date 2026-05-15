package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogPageKeyBinding
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.putPrefString
import splitties.views.onClick


class PageKeyDialog : BaseDialogFragment(R.layout.dialog_page_key) {

    private lateinit var binding: DialogPageKeyBinding

    override fun onStart() {
        super.onStart()
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) return@setOnKeyListener false
            if (binding.etPrev.hasFocus()) {
                val editableText = binding.etPrev.editableText
                if (editableText.isEmpty() || editableText.endsWith(",")) {
                    editableText.append(keyCode.toString())
                } else {
                    editableText.append(",").append(keyCode.toString())
                }
                return@setOnKeyListener true
            } else if (binding.etNext.hasFocus()) {
                val editableText = binding.etNext.editableText
                if (editableText.isEmpty() || editableText.endsWith(",")) {
                    editableText.append(keyCode.toString())
                } else {
                    editableText.append(",").append(keyCode.toString())
                }
                return@setOnKeyListener true
            }
            false
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding = DialogPageKeyBinding.bind(view)
        binding.run {
            etPrev.setText(requireContext().getPrefString(PreferKey.prevKeys))
            etNext.setText(requireContext().getPrefString(PreferKey.nextKeys))
            tvReset.onClick {
                etPrev.setText("")
                etNext.setText("")
            }
            tvOk.setOnClickListener {
                requireContext().putPrefString(PreferKey.prevKeys, etPrev.text?.toString())
                requireContext().putPrefString(PreferKey.nextKeys, etNext.text?.toString())
                dismiss()
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (dialog as? Dialog)?.currentFocus?.hideSoftInput()
    }
}