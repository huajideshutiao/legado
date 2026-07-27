package io.legado.app.ui.dict.rule

import android.app.Application
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.base.BaseViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FormEditFields
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip

class DictRuleEditDialog() : BaseComposeDialogFragment() {

    val viewModel by viewModels<DictRuleEditViewModel>()
    private var editEntities by mutableStateOf<List<EditEntity>>(emptyList())

    constructor(name: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initData(arguments?.getString("name")) {
            upRuleView(viewModel.dictRule)
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
                    IconButton(onClick = {
                        viewModel.save(getDictRule()) {
                            dismissAllowingStateLoss()
                        }
                    }) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = stringResource(R.string.action_save),
                            tint = colors.primaryText,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_rule), color = colors.primaryText) },
                            onClick = {
                                dismissMenu()
                                viewModel.copyRule(getDictRule())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.paste_rule), color = colors.primaryText) },
                            onClick = {
                                dismissMenu()
                                viewModel.pasteRule { upRuleView(it) }
                            },
                        )
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

    private fun upRuleView(dictRule: DictRule?) {
        editEntities = listOf(
            EditEntity("name", dictRule?.name, R.string.name),
            EditEntity("urlRule", dictRule?.urlRule, R.string.url_rule),
            EditEntity("showRule", dictRule?.showRule, R.string.show_rule),
        )
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

        // 委托给 commonMain 的 DictRuleEditViewModelShared, 业务逻辑下沉供多端复用
        // (Android=viewModelScope + getClipText + sendToClip / desktop=应用 scope + AWT Clipboard)
        private val shared: DictRuleEditViewModelShared =
            DictRuleEditViewModelShared(
                viewModelScope,
                { getClipText() },
                { text -> context.sendToClip(text) }
            )

        var dictRule: DictRule?
            get() = shared.dictRule
            set(value) {
                shared.dictRule = value
            }

        fun initData(name: String?, onFinally: () -> Unit) {
            shared.initData(name, onFinally)
        }

        fun save(newDictRule: DictRule, onFinally: () -> Unit) {
            shared.save(newDictRule, onFinally)
        }

        fun copyRule(dictRule: DictRule) {
            shared.copyRule(dictRule)
        }

        fun pasteRule(success: (DictRule) -> Unit) {
            shared.pasteRule(success)
        }

    }

}
