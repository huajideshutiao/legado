package io.legado.app.ui.book.toc.rule

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.reorderable.RuleItemScope
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * txt目录规则选择器：单选 tocRegex + 长按拖拽排序 + 单项启停/编辑/删除，OK 回传选中规则。
 */
class TxtTocRuleDialog() : BaseComposeDialogFragment(), TxtTocRuleEditDialog.Callback {

    override val isFullHeight: Boolean = true

    constructor(tocRegex: String?) : this() {
        arguments = Bundle().apply {
            putString("tocRegex", tocRegex)
        }
    }

    private val importTocRuleKey = "tocRuleUrl"
    private val viewModel: TxtTocRuleViewModel by viewModels()

    private var tocRules by mutableStateOf<List<TxtTocRule>>(emptyList())
    private var selectedName by mutableStateOf<String?>(null)
    private var durRegex: String? = null

    private val importDoc by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
            }
        }
    }

    @Composable
    override fun Content() {
        durRegex = arguments?.getString("tocRegex")
        LaunchedEffect(Unit) {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则对话框获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { rules ->
                initSelectedName(rules)
                tocRules = rules
            }
        }
        RuleManageScaffold(
            items = tocRules,
            itemKey = { it.id },
            onMove = { from, to ->
                tocRules = tocRules.toMutableList().apply { add(to, removeAt(from)) }
            },
            titleBar = {
                var showMenu by remember { mutableStateOf(false) }
                DialogTitleBar(
                    title = getString(R.string.txt_toc_rule),
                    onBack = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = rememberPainter("ic_more_vert"),
                                contentDescription = getString(R.string.more_menu),
                                tint = AppTheme.colors.primaryText,
                            )
                        }
                        TitleMenu(showMenu) { showMenu = false }
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
                    AppTextButton(text = stringResource(R.string.cancel)) {
                        dismissAllowingStateLoss()
                    }
                    AppTextButton(text = stringResource(R.string.ok)) { confirmSelection() }
                }
            },
        ) { item ->
            TocRuleItem(item)
        }
    }

    @Composable
    private fun RuleItemScope.TocRuleItem(item: TxtTocRule) {
        val colors = AppTheme.colors
        Column(
            Modifier
                .fillMaxWidth()
                .longPressDraggableHandle(onDragStopped = { persistOrder() })
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppRadioButton(
                    selected = item.name == selectedName,
                    onClick = { selectedName = item.name },
                )
                Text(
                    text = item.name,
                    color = colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedName = item.name },
                )
                AppSwitch(
                    checked = item.enable,
                    onCheckedChange = {
                        item.enable = it
                        viewModel.update(item)
                    },
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { showDialogFragment(TxtTocRuleEditDialog(item.id)) }) {
                    Icon(
                        painter = rememberPainter("ic_edit"),
                        contentDescription = stringResource(R.string.edit),
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = { confirmDelete(item) }) {
                    Icon(
                        painter = rememberPainter("ic_clear_all"),
                        contentDescription = stringResource(R.string.delete),
                        tint = colors.primaryText,
                    )
                }
            }
            item.example?.let {
                Text(text = it, color = colors.secondaryText, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun TitleMenu(expanded: Boolean, onDismiss: () -> Unit) {
        val colors = AppTheme.colors
        AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            @Composable
            fun item(textRes: Int, onClick: () -> Unit) {
                DropdownMenuItem(
                    onClick = { onDismiss(); onClick() },
                ) { Text(stringResource(textRes), color = colors.primaryText) }
            }
            item(R.string.create) { showDialogFragment(TxtTocRuleEditDialog()) }
            item(R.string.import_local) {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
            item(R.string.import_on_line) { showImportDialog() }
            item(R.string.import_default_rule) { viewModel.importDefault() }
            item(R.string.help) { showHelp("txtTocRuleHelp") }
        }
    }

    private fun confirmSelection() {
        tocRules.forEach { tocRule ->
            if (selectedName == tocRule.name) {
                (activity as? CallBack)?.onTocRegexDialogResult(tocRule.rule)
                dismissAllowingStateLoss()
                return
            }
        }
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    private fun confirmDelete(item: TxtTocRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + item.name)
            noButton()
            yesButton { viewModel.del(item) }
        }
    }

    private fun persistOrder() {
        tocRules.forEachIndexed { index, item -> item.serialNumber = index + 1 }
        viewModel.update(*tocRules.toTypedArray())
    }

    private fun initSelectedName(tocRules: List<TxtTocRule>) {
        if (selectedName == null && durRegex != null) {
            tocRules.forEach {
                if (durRegex == it.rule) {
                    selectedName = it.name
                    return@forEach
                }
            }
            if (selectedName == null) {
                selectedName = ""
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> =
            aCache.getAsString(importTocRuleKey)?.splitNotBlank(",")?.toMutableList()
                ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        requireContext().alert(titleResource = R.string.import_on_line) {
            val getText = editTextView(
                hint = "url",
                filterValues = cacheUrls,
                onDelete = {
                    cacheUrls.remove(it)
                    aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                },
            )
            okButton {
                val text = getText()
                text.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportTxtTocRuleDialog(it))
                }
            }
            cancelButton()
        }
    }

    interface CallBack {
        fun onTocRegexDialogResult(tocRegex: String) {}
    }

}
