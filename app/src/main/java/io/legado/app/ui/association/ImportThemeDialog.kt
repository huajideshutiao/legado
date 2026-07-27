package io.legado.app.ui.association

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.showDialogFragment

class ImportThemeDialog() : BaseComposeDialogFragment() {


    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportThemeViewModel>()
    private var version by mutableIntStateOf(0)

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @Composable
    override fun Content() {
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        version

        LaunchedEffect(Unit) {
            viewModel.errorLiveData.observe(this@ImportThemeDialog) {
                loading = false
                error = it
            }
            viewModel.successLiveData.observe(this@ImportThemeDialog) {
                loading = false
                if (it > 0) version++ else error = getString(R.string.wrong_format)
            }
            val source = arguments?.getString("source")
            if (source.isNullOrEmpty()) dismiss() else viewModel.importSource(source)
        }

        ImportListScaffold(
            title = getString(R.string.import_theme),
            loading = loading,
            errorText = error,
            itemCount = viewModel.allSources.size,
            selectCount = viewModel.selectCount,
            isSelectAll = viewModel.isSelectAll,
            itemLabel = { viewModel.allSources[it].themeName },
            itemState = {
                val local = viewModel.checkSources[it]
                when {
                    local == null -> "新增"
                    local != viewModel.allSources[it] -> "更新"
                    else -> "已有"
                }
            },
            itemChecked = { viewModel.selectStatus[it] },
            onItemChecked = { index, checked ->
                viewModel.selectStatus[index] = checked
                version++
            },
            onOpen = { openCode(it) },
            onToggleAll = { toggleAll() },
            onCancel = { dismissAllowingStateLoss() },
            onOk = { onImport() },
        )
    }

    private fun toggleAll() {
        val selectAll = viewModel.isSelectAll
        viewModel.selectStatus.forEachIndexed { index, b ->
            if (b != !selectAll) viewModel.selectStatus[index] = !selectAll
        }
        version++
    }

    private fun onImport() {
        val waitDialog = WaitDialog.from(requireActivity())
        waitDialog.show(requireActivity().supportFragmentManager)
        viewModel.importSelect {
            waitDialog.dismissSafe()
            dismissAllowingStateLoss()
        }
    }

    private fun openCode(index: Int) {
        showDialogFragment(
            CodeDialog(
                GSON.toJson(viewModel.allSources[index]),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }
}
