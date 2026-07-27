package io.legado.app.ui.config

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.platform.rememberPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.getClipText
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

/**
 * 主题列表（迁 View 版 item_theme_config 手拼列表 → Compose）。
 * 点击应用/长按编辑/分享/删除、新建、剪贴板导入行为逐项等价；
 * ThemeCustomizeDialog 保存后经 FragmentResult 通知刷新。
 */
class ThemeListDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    // 数据版本号：增删改后自增触发重组重取列表
    private var dataVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(
            ThemeCustomizeDialog.RESULT_CONFIG_CHANGED, this
        ) { _, _ -> dataVersion++ }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        // 读 dataVersion 让增删改能触发重组重取
        dataVersion
        val builtins = ThemeConfig.getBuiltinConfigs(requireContext())
        val items = builtins + ThemeConfig.configList
        val builtinCount = builtins.size
        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = stringResource(R.string.theme_list),
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    IconButton(onClick = { alertNewTheme() }) {
                        Icon(
                            painter = rememberPainter("ic_add"),
                            contentDescription = stringResource(R.string.add),
                            tint = colors.primaryText,
                        )
                    }
                    IconButton(onClick = { importFromClip() }) {
                        Icon(
                            painter = rememberPainter("ic_copy"),
                            contentDescription = "剪贴板导入",
                            tint = colors.primaryText,
                        )
                    }
                },
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                itemsIndexed(items) { position, item ->
                    ThemeItem(item, configIndex = position - builtinCount)
                }
            }
        }
    }

    @Composable
    private fun ThemeItem(item: ThemeConfig.Config, configIndex: Int) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp) // arco_view_height_large
                .combinedClickable(
                    onClick = {
                        val ctx = requireContext()
                        dismiss()
                        if (item.isBuiltin) {
                            ThemeConfig.applyBuiltin(ctx, item.isNightTheme)
                        } else {
                            ThemeConfig.applyConfig(ctx, item)
                        }
                    },
                    onLongClick = {
                        if (!item.isBuiltin && configIndex in ThemeConfig.configList.indices) {
                            ThemeCustomizeDialog.editConfig(configIndex)
                                .show(parentFragmentManager, "themeCustomize")
                        }
                    },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.themeName,
                color = colors.primaryText,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!item.isBuiltin) {
                IconButton(onClick = { share(configIndex) }) {
                    Icon(
                        painter = rememberPainter("ic_share"),
                        contentDescription = stringResource(R.string.share),
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = { delete(configIndex) }) {
                    Icon(
                        painter = rememberPainter("ic_clear_all"),
                        contentDescription = stringResource(R.string.delete),
                        tint = colors.primaryText,
                    )
                }
            }
        }
    }

    private fun importFromClip() {
        getClipText()?.let {
            if (ThemeConfig.addConfig(it)) {
                dataVersion++
            } else {
                toastOnUi("格式不对,添加失败")
            }
        }
    }

    private fun alertNewTheme() {
        ThemeCustomizeDialog.newConfig(isNight = false)
            .show(parentFragmentManager, "themeCustomize")
    }

    fun delete(index: Int) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                ThemeConfig.delConfig(index)
                dataVersion++
            }
            noButton()
        }
    }

    fun share(index: Int) {
        val json = GSON.toJson(ThemeConfig.configList[index])
        requireContext().share(json, "主题分享")
    }
}
