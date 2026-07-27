package io.legado.app.ui.association

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 规则订阅管理界面 (薄壳)。
 *
 * Composable 已下沉到 shared/sharedUiMain 的 [RuleSubScreen], 本 Activity 仅:
 * - 持有 [RuleSubUiState] (订阅列表 mutableStateOf) 与 [RuleSubViewModel]
 * - 实现 [RuleSubUiActions] 接口, 在回调内桥接平台依赖 (appDb / showDialogFragment /
 *   `alert` 编辑弹窗 / `toastOnUi`)
 * - [Content] 内构造 state, 调用 [RuleSubScreen] 渲染
 *
 * 编辑弹窗 ([showEditDialog]) 与订阅打开 ([openSubscription]) 仍保留在本类:
 * 它们依赖 `alert` 扩展 (commonMain 不可用) 与 `showDialogFragment` (Android 专属)。
 */
class RuleSubActivity : BaseComposeActivity(), RuleSubUiActions {

    private val viewModel by viewModels<RuleSubViewModel>()

    private var ruleSubs by mutableStateOf<List<RuleSub>>(emptyList())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.ruleSubDao.flowAll().catch {
                AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { subs ->
                ruleSubs = subs
            }
        }
    }

    @Composable
    override fun Content() {
        val state = RuleSubUiState(ruleSubs = ruleSubs)
        RuleSubScreen(state = state, actions = this)
    }

    // ===== RuleSubUiActions 适配 =====

    override fun onBack() = finish()

    override fun onAdd() {
        showEditDialog(RuleSub())
    }

    override fun onEdit(ruleSub: RuleSub) {
        showEditDialog(ruleSub)
    }

    override fun onOpenSubscription(ruleSub: RuleSub) {
        openSubscription(ruleSub)
    }

    override fun onMove(from: Int, to: Int) {
        ruleSubs = ruleSubs.toMutableList().apply { add(to, removeAt(from)) }
    }

    override fun onPersistOrder() {
        viewModel.upOrder(ruleSubs)
    }

    override fun onToTop(ruleSub: RuleSub) {
        viewModel.toTop(ruleSub)
    }

    override fun onToBottom(ruleSub: RuleSub) {
        viewModel.toBottom(ruleSub)
    }

    override fun onDelete(ruleSub: RuleSub) {
        delete(ruleSub)
    }

    // ===== 平台相关方法 (依赖 alert / showDialogFragment / getString, 不下沉) =====

    private fun typeName(type: Int): String = when (type) {
        1 -> getString(R.string.rss_source)
        2 -> getString(R.string.replace_rule)
        3 -> getString(R.string.txt_toc_rule)
        4 -> getString(R.string.dict_rule)
        5 -> getString(R.string.tts)
        else -> getString(R.string.book_source)
    }

    private fun openSubscription(ruleSub: RuleSub) {
        if (!ruleSub.url.isAbsUrl()) {
            toastOnUi(R.string.non_null_name_url)
            return
        }
        when (ruleSub.type) {
            0, 1 -> showDialogFragment(ImportBookSourceDialog(ruleSub.url))
            2 -> showDialogFragment(ImportReplaceRuleDialog(ruleSub.url))
            3 -> showDialogFragment(ImportTxtTocRuleDialog(ruleSub.url))
            4 -> showDialogFragment(ImportDictRuleDialog(ruleSub.url))
            5 -> showDialogFragment(ImportHttpTtsDialog(ruleSub.url))
            else -> toastOnUi(R.string.error)
        }
    }

    private fun delete(ruleSub: RuleSub) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + ruleSub.name)
            yesButton { viewModel.delete(ruleSub) }
            noButton()
        }
    }

    private fun showEditDialog(ruleSub: RuleSub) {
        val title = if (ruleSub.name.isEmpty()) R.string.add else R.string.edit
        alert(titleResource = title) {
            val type = mutableStateOf(ruleSub.type.coerceIn(0, 5))
            val name = mutableStateOf(ruleSub.name)
            val url = mutableStateOf(ruleSub.url)
            customView {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.book_type),
                            color = AppTheme.colors.accent,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                typeName(type.value),
                                color = AppTheme.colors.primaryText,
                                modifier = Modifier
                                    .clickable { expanded = true }
                                    .padding(8.dp),
                            )
                            AppDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                (0..5).forEach { t ->
                                    DropdownMenuItem(
                                        onClick = { type.value = t; expanded = false },
                                    ) { Text(typeName(t), color = AppTheme.colors.primaryText) }
                                }
                            }
                        }
                    }
                    AppOutlinedTextField(
                        value = name.value,
                        onValueChange = { name.value = it },
                        label = stringResource(R.string.name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = url.value,
                        onValueChange = { url.value = it },
                        label = "Url",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            okButton {
                val n = name.value.trim()
                val u = url.value.trim()
                if (n.isEmpty() || !u.isAbsUrl()) {
                    toastOnUi(R.string.non_null_name_url)
                    return@okButton
                }
                ruleSub.name = n
                ruleSub.url = u
                ruleSub.type = type.value
                viewModel.save(ruleSub)
            }
            cancelButton()
        }
    }
}
