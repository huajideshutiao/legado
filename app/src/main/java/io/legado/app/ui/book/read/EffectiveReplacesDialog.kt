package io.legado.app.ui.book.read

import android.content.DialogInterface
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.fragment.app.activityViewModels
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.showConverterSelector

/**
 * 展示当前章节起效的替换规则；点击单条编辑，底部"管理全部"跳 [ReplaceRuleActivity]。
 * 承担原"净化"入口职责。
 *
 * 实现已下沉到 shared/sharedUiMain 的 [EffectiveReplacesScreen]（app + desktop 共用），
 * 本类作为 thin wrapper 保留 `BaseComposeDialogFragment` 宿主与 AndroidX 特有 API：
 * - `registerForActivityResult` + `Intent(ReplaceRuleActivity/ReplaceEditActivity)` 不能下沉
 *   （依赖 AndroidX Activity Result + Fragment），通过 `onAddRule` / `onManageAll` /
 *   `onItemClick` 回调桥接到 wrapper 内的 editActivity / manageActivity.launch
 * - `activityViewModels<ReadBookViewModel>()` 不能下沉（依赖 AndroidX ViewModel），
 *   onDismiss 时根据 isEdit 调用 `viewModel.replaceRuleChanged()`
 * - `chineseConvert` 项（繁简转换）作为 items 的一部分由 wrapper 构造时附加，
 *   `onItemClick` 中判断 `item === chineseConvert` 后调用 `showChineseConvertAlert`
 *
 * 调用方契约不变：`showDialogFragment<EffectiveReplacesDialog>()`。
 */
class EffectiveReplacesDialog : BaseComposeDialogFragment() {

    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val chineseConvert by lazy { ReplaceRule(0, "繁简转换") }

    override val isFullHeight: Boolean = true

    private var isEdit = false

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    private val manageActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    @Composable
    override fun Content() {
        val effectiveReplaceRules = ReadBook.curTextChapter?.effectiveReplaceRules ?: emptyList()
        val items = if (AppConfig.chineseConverterType > 0) {
            effectiveReplaceRules + chineseConvert
        } else {
            effectiveReplaceRules
        }
        EffectiveReplacesScreen(
            items = items,
            onAddRule = { addRule() },
            onItemClick = { onItemClick(it) },
            onManageAll = {
                manageActivity.launch(
                    Intent(requireContext(), ReplaceRuleActivity::class.java)
                )
            },
            onDismiss = { dismiss() },
        )
    }

    private fun addRule() {
        val scope = listOfNotNull(
            ReadBook.book?.name,
            ReadBook.bookSource?.bookSourceUrl
        ).joinToString(";")
        editActivity.launch(
            ReplaceEditActivity.startIntent(requireContext(), scope = scope)
        )
    }

    private fun onItemClick(item: ReplaceRule) {
        if (item === chineseConvert) {
            showChineseConvertAlert()
            return
        }
        editActivity.launch(ReplaceEditActivity.startIntent(requireContext(), item.id))
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isEdit) {
            viewModel.replaceRuleChanged()
        }
    }

    private fun showChineseConvertAlert() {
        ChineseUtils.showConverterSelector(requireContext()) {
            isEdit = true
        }
    }
}
