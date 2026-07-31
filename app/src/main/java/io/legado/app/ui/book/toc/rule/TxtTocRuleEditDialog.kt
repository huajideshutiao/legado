package io.legado.app.ui.book.toc.rule

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.base.BaseViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FormEditFields
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import org.jetbrains.compose.resources.stringResource

class TxtTocRuleEditDialog() : BaseComposeDialogFragment() {

    constructor(id: Long?) : this() {
        id ?: return
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private var editEntities by mutableStateOf<List<EditEntity>>(emptyList())
    private val viewModel by viewModels<ViewModel>()
    private val callback get() = (parentFragment as? Callback) ?: activity as? Callback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initData(arguments?.getLong("id")) {
            upRuleView(it)
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = "",
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = stringResource(R.string.action_save),
                            tint = colors.primaryText,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        DropdownMenuItem(
                            onClick = {
                                dismissMenu()
                                context?.sendToClip(GSON.toJson(getRuleFromView()))
                            },
                        ) { Text(stringResource(R.string.copy_rule), color = colors.primaryText) }
                        DropdownMenuItem(
                            onClick = {
                                dismissMenu()
                                viewModel.pasteRule { upRuleView(it) }
                            },
                        ) { Text(stringResource(R.string.paste_rule), color = colors.primaryText) }
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                FormEditFields(editEntities)
            }
        }
    }

    private fun save() {
        val tocRule = getRuleFromView()
        if (checkValid(tocRule)) {
            callback?.saveTxtTocRule(tocRule)
            dismissAllowingStateLoss()
        }
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
        editEntities = listOf(
            EditEntity("name", tocRule?.name, R.string.name),
            EditEntity("rule", tocRule?.rule, R.string.regex),
            EditEntity("example", tocRule?.example, R.string.example),
        )
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

        // 委托给 commonMain 的 TxtTocRuleEditViewModelShared, 业务逻辑下沉供多端复用
        // (Android=viewModelScope + getClipText / desktop=应用 scope + AWT Clipboard)
        private val shared: TxtTocRuleEditViewModelShared =
            TxtTocRuleEditViewModelShared(viewModelScope) { getClipText() }

        var tocRule: TxtTocRule?
            get() = shared.tocRule
            set(value) {
                shared.tocRule = value
            }

        fun initData(id: Long?, finally: (tocRule: TxtTocRule?) -> Unit) {
            shared.initData(id, finally)
        }

        fun pasteRule(success: (TxtTocRule) -> Unit) {
            shared.pasteRule(success)
        }

    }

    interface Callback {

        fun saveTxtTocRule(txtTocRule: TxtTocRule)

    }

}
