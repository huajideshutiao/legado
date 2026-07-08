package io.legado.app.ui.book.toc.rule

import android.app.Application
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.DialogFormEditBinding
import io.legado.app.exception.NoStackTraceException
import io.legado.app.ui.widget.form.FormAdapter
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class TxtTocRuleEditDialog() : BaseDialogFragment(R.layout.dialog_form_edit),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long?) : this() {
        id ?: return
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogFormEditBinding::bind)
    private val adapter by lazy { FormAdapter() }
    private val editEntities: ArrayList<EditEntity> = ArrayList()
    private val viewModel by viewModels<ViewModel>()
    private val callback get() = (parentFragment as? Callback) ?: activity as? Callback

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initMenu()
        binding.recyclerView.adapter = adapter
        viewModel.initData(arguments?.getLong("id")) {
            upRuleView(it)
        }
    }

    private fun initMenu() {
        setupTitleBar(
            menuRes = R.menu.rule_edit,
            onMenuClick = ::onMenuItemClick
        )
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> {
                val tocRule = getRuleFromView()
                if (checkValid(tocRule)) {
                    callback?.saveTxtTocRule(getRuleFromView())
                    dismissAllowingStateLoss()
                }
            }
            R.id.menu_copy_rule -> context?.sendToClip(GSON.toJson(getRuleFromView()))
            R.id.menu_paste_rule -> viewModel.pasteRule {
                upRuleView(it)
            }
        }
        return true
    }

    private fun checkValid(tocRule: TxtTocRule): Boolean {
        if (tocRule.name.isEmpty()) {
            toastOnUi("名称不能为空")
            return false
        }

        try {
            Pattern.compile(tocRule.rule, Pattern.MULTILINE)
        } catch (ex: PatternSyntaxException) {
            AppLog.put("正则语法错误或不支持(txt)：${ex.localizedMessage}", ex, true)
            return false
        }

        return true
    }

    private fun upRuleView(tocRule: TxtTocRule?) {
        editEntities.clear()
        editEntities.apply {
            add(EditEntity("name", tocRule?.name, R.string.name))
            add(EditEntity("rule", tocRule?.rule, R.string.regex))
            add(EditEntity("example", tocRule?.example, R.string.example))
        }
        adapter.editEntities = editEntities
    }

    private fun getRuleFromView(): TxtTocRule {
        val tocRule = viewModel.tocRule ?: TxtTocRule().apply {
            viewModel.tocRule = this
        }
        editEntities.forEach {
            when (it.key) {
                "name" -> tocRule.name = it.text.orEmpty()
                "rule" -> tocRule.rule = it.text.orEmpty()
                "example" -> tocRule.example = it.text.orEmpty()
            }
        }
        return tocRule
    }

    class ViewModel(application: Application) : BaseViewModel(application) {

        var tocRule: TxtTocRule? = null

        fun initData(id: Long?, finally: (tocRule: TxtTocRule?) -> Unit) {
            if (tocRule != null) return
            execute {
                if (id == null) return@execute
                tocRule = appDb.txtTocRuleDao.get(id)
            }.onFinally {
                finally.invoke(tocRule)
            }
        }

        fun pasteRule(success: (TxtTocRule) -> Unit) {
            execute(context = Dispatchers.Main) {
                val text = getClipText()
                if (text.isNullOrBlank()) {
                    throw NoStackTraceException("剪贴板为空")
                }
                GSON.fromJsonObject<TxtTocRule>(text).getOrNull()
                    ?: throw NoStackTraceException("格式不对")
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi(it.localizedMessage ?: "Error")
                it.printOnDebug()
            }
        }

    }

    interface Callback {

        fun saveTxtTocRule(txtTocRule: TxtTocRule)

    }

}