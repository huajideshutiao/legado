package io.legado.app.ui.book.filter

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import io.legado.app.ui.compose.component.AppDropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.LocalAppNavigator
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 展示当前 scope 下命中（已启用且范围覆盖）的屏蔽规则。
 * 点击编辑 → 弹出 [SourceFilterEditDialog] 编辑；底部"管理全部" → 跳 [SourceFilterRuleActivity]。
 */
class SourceFilterRuleListDialog() : BaseComposeDialogFragment(),
    SourceFilterEditDialog.Callback {

    companion object {
        private const val ARG_SCOPE = "scope"
    }

    constructor(scope: String?) : this() {
        arguments = Bundle().apply { putString(ARG_SCOPE, scope) }
    }

    override val isFullHeight: Boolean = true

    private var rules by mutableStateOf<List<SourceFilterRule>>(emptyList())

    private val scope: String?
        get() = arguments?.getString(ARG_SCOPE)

    override fun onResume() {
        super.onResume()
        loadRules()
    }

    @Composable
    override fun Content() {
        // 列表内导航统一走 AppNavigator
        val navigator = LocalAppNavigator.current
        RuleManageScaffold(
            items = rules,
            itemKey = { it.id },
            onMove = { _, _ -> },
            emptyText = stringResource(R.string.source_filter_rule_no_match),
            titleBar = {
                DialogTitleBar(
                    title = getString(R.string.source_filter_rule),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = {
                            showDialogFragment(
                                SourceFilterEditDialog(existing = null, defaultScope = scope)
                            )
                        }) {
                            Icon(
                                painter = rememberPainter("ic_add"),
                                contentDescription = getString(R.string.add),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                    },
                )
            },
            actionBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(text = stringResource(R.string.close)) {
                        dismissAllowingStateLoss()
                    }
                    AppTextButton(text = stringResource(R.string.source_filter_rule_manage)) {
                        navigator.push(AppRoute.SourceFilterRule)
                        dismissAllowingStateLoss()
                    }
                }
            },
        ) { item ->
            RuleItem(item)
        }
    }

    @Composable
    private fun RuleItem(item: SourceFilterRule) {
        val colors = AppTheme.colors
        var showMenu by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.name.ifEmpty { item.pattern },
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AppSwitch(
                checked = item.enabled,
                onCheckedChange = { checked ->
                    item.enabled = checked
                    lifecycleScope.launch(IO) { SearchBookFilter.save(item, isNew = false) }
                },
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { showDialogFragment(SourceFilterEditDialog(item)) }) {
                Icon(
                    painter = rememberPainter("ic_edit"),
                    contentDescription = stringResource(R.string.edit),
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    painter = rememberPainter("ic_more_vert"),
                    contentDescription = stringResource(R.string.more_menu),
                    tint = colors.primaryText,
                )
            }
            AppDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    onClick = { showMenu = false; confirmDelete(item) },
                ) { Text(stringResource(R.string.delete), color = colors.primaryText) }
            }
        }
    }

    private fun confirmDelete(item: SourceFilterRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + item.name.ifEmpty { item.pattern })
            noButton()
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) { SearchBookFilter.delete(item) }
                    loadRules()
                }
            }
        }
    }

    private fun loadRules() {
        lifecycleScope.launch {
            rules = withContext(IO) { SearchBookFilter.rulesInScope(scope) }
        }
    }

    override fun onSourceFilterRuleSave(rule: SourceFilterRule, isNew: Boolean) {
        lifecycleScope.launch {
            withContext(IO) { SearchBookFilter.save(rule, isNew) }
            loadRules()
        }
    }
}
