package io.legado.app.ui.dict.rule

import android.app.Application
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.databinding.DialogFormEditBinding
import io.legado.app.ui.widget.form.FormAdapter
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class DictRuleEditDialog() : BaseDialogFragment(R.layout.dialog_form_edit),
    Toolbar.OnMenuItemClickListener {

    val viewModel by viewModels<DictRuleEditViewModel>()
    val binding by viewBinding(DialogFormEditBinding::bind)
    private val adapter by lazy { FormAdapter() }
    private val editEntities: ArrayList<EditEntity> = ArrayList()

    constructor(name: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            menuRes = R.menu.rule_edit,
            onMenuClick = ::onMenuItemClick
        )
        binding.recyclerView.adapter = adapter
        viewModel.initData(arguments?.getString("name")) {
            upRuleView(viewModel.dictRule)
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> viewModel.save(getDictRule()) {
                dismissAllowingStateLoss()
            }
            R.id.menu_copy_rule -> viewModel.copyRule(getDictRule())
            R.id.menu_paste_rule -> viewModel.pasteRule {
                upRuleView(it)
            }
        }
        return true
    }

    private fun upRuleView(dictRule: DictRule?) {
        editEntities.clear()
        editEntities.apply {
            add(EditEntity("name", dictRule?.name, R.string.name))
            add(EditEntity("urlRule", dictRule?.urlRule, R.string.url_rule))
            add(EditEntity("showRule", dictRule?.showRule, R.string.show_rule))
        }
        adapter.editEntities = editEntities
    }

    private fun getDictRule(): DictRule {
        val dictRule = viewModel.dictRule?.copy() ?: DictRule()
        editEntities.forEach {
            when (it.key) {
                "name" -> dictRule.name = it.text.orEmpty()
                "urlRule" -> dictRule.urlRule = it.text.orEmpty()
                "showRule" -> dictRule.showRule = it.text.orEmpty()
            }
        }
        return dictRule
    }

    class DictRuleEditViewModel(application: Application) : BaseViewModel(application) {

        var dictRule: DictRule? = null

        fun initData(name: String?, onFinally: () -> Unit) {
            execute {
                if (dictRule == null && name != null) {
                    dictRule = appDb.dictRuleDao.getByName(name)
                }
            }.onFinally {
                onFinally.invoke()
            }
        }

        fun save(newDictRule: DictRule, onFinally: () -> Unit) {
            execute {
                dictRule?.let {
                    appDb.dictRuleDao.delete(it)
                }
                appDb.dictRuleDao.insert(newDictRule)
                dictRule = newDictRule
            }.onFinally {
                onFinally.invoke()
            }
        }

        fun copyRule(dictRule: DictRule) {
            context.sendToClip(GSON.toJson(dictRule))
        }

        fun pasteRule(success: (DictRule) -> Unit) {
            val text = getClipText()
            if (text.isNullOrBlank()) {
                context.toastOnUi("剪贴板没有内容")
                return
            }
            execute {
                GSON.fromJsonObject<DictRule>(text).getOrThrow()
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi("格式不对")
            }
        }

    }

}