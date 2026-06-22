package io.legado.app.ui.main.home

import android.os.Bundle
import android.view.View
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogHomeTabEditBinding
import io.legado.app.help.HomeTabHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.utils.gone
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.views.onClick

/**
 * 添加/编辑/删除一个主页分组。title 即唯一标识，重名校验在保存时进行。
 */
class HomeTabEditDialog : BaseDialogFragment(R.layout.dialog_home_tab_edit) {

    private val binding by viewBinding(DialogHomeTabEditBinding::bind)

    /** 编辑模式下为原标题；添加模式下为 null */
    private val oldTitle: String? get() = arguments?.getString(ARG_OLD_TITLE)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            val editing = oldTitle
            if (editing == null) {
                toolBar.setTitle(R.string.home_tab_add)
                btnDelete.gone()
            } else {
                toolBar.setTitle(R.string.home_tab_edit)
                etTitle.setText(editing)
                btnDelete.visible()
                btnDelete.onClick { confirmDelete(editing) }
            }
            btnCancel.onClick { dismiss() }
            btnOk.onClick { save() }
        }
    }

    private fun save() {
        val newTitle = binding.etTitle.text?.toString()?.trim().orEmpty()
        if (newTitle.isBlank()) {
            toastOnUi(R.string.home_title_empty)
            return
        }
        val old = oldTitle
        val ok = if (old == null) {
            HomeTabHelp.addTab(newTitle)
        } else {
            HomeTabHelp.renameTab(old, newTitle)
        }
        if (!ok) {
            toastOnUi(R.string.home_tab_name_duplicate)
            return
        }
        if (old == null) {
            postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.ADD, newTitle = newTitle))
        } else {
            postEvent(
                EventBus.HOME_TAB,
                HomeTabEvent(HomeTabEvent.RENAME, oldTitle = old, newTitle = newTitle)
            )
        }
        dismiss()
    }

    private fun confirmDelete(title: String) {
        val sectionCount = HomeTabHelp.getSections(title).size
        val message = if (sectionCount > 0) {
            getString(R.string.home_tab_delete_confirm_with_sections, title, sectionCount)
        } else {
            getString(R.string.home_tab_delete_confirm, title)
        }
        alert(getString(R.string.delete), message) {
            yesButton {
                HomeTabHelp.removeTab(title)
                postEvent(EventBus.HOME_TAB, HomeTabEvent(HomeTabEvent.REMOVE, oldTitle = title))
                dismiss()
            }
            noButton()
        }
    }

    companion object {
        private const val ARG_OLD_TITLE = "oldTitle"

        fun newInstance(oldTitle: String?) = HomeTabEditDialog().apply {
            arguments = Bundle().apply {
                if (oldTitle != null) putString(ARG_OLD_TITLE, oldTitle)
            }
        }
    }
}
