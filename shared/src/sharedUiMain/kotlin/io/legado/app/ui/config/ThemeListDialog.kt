package io.legado.app.ui.config

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.no
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.share
import legado.shared.generated.resources.sure_del
import legado.shared.generated.resources.theme_list
import org.jetbrains.compose.resources.stringResource

/**
 * 主题列表对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对照 app 端 `io.legado.app.ui.config.ThemeListDialog` 的 Content,
 * 去掉对 BaseComposeDialogFragment / FragmentResult / getClipText / alert DSL 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 标题栏: 返回 + "主题列表" + 新建/剪贴板导入按钮
 * - 列表: LazyColumn, 行=主题名, 点击应用, 长按编辑, 非内置项有分享/删除按钮
 *
 * 数据通过 [ThemeConfigProviders] 获取 (内置 + 自定义列表);
 * 增删改后自增 [dataVersion] 触发重组重取。
 *
 * @param onDismiss 关闭回调
 * @param onEditConfig 编辑自定义主题 (configIndex: 自定义列表中的索引)
 * @param onNewConfig 新建主题 (isNight: 是否夜间)
 * @param onImportFromClip 返回剪贴板文本 (null 表示剪贴板为空)
 * @param onShare 分享主题 (json: 主题 JSON 字符串)
 */
@Composable
fun ThemeListDialog(
    onDismiss: () -> Unit,
    onEditConfig: (configIndex: Int) -> Unit,
    onNewConfig: (isNight: Boolean) -> Unit,
    onImportFromClip: () -> String?,
    onShare: (json: String) -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        ThemeListDialogContent(
            onDismiss = onDismiss,
            onEditConfig = onEditConfig,
            onNewConfig = onNewConfig,
            onImportFromClip = onImportFromClip,
            onShare = onShare,
        )
    }
}

@Composable
private fun ThemeListDialogContent(
    onDismiss: () -> Unit,
    onEditConfig: (configIndex: Int) -> Unit,
    onNewConfig: (isNight: Boolean) -> Unit,
    onImportFromClip: () -> String?,
    onShare: (json: String) -> Unit,
) {
    val colors = AppTheme.colors
    // 数据版本号：增删改后自增触发重组重取列表
    var dataVersion by remember { mutableIntStateOf(0) }
    // 读 dataVersion 让增删改能触发重组重取
    dataVersion

    // 对照 app 端 setFragmentResultListener(RESULT_CONFIG_CHANGED):
    // ThemeCustomizeDialog 保存后 (桌面 overlay 场景) 通知刷新列表
    LaunchedEffect(Unit) {
        FlowBus.with(EventBus.THEME_CONFIG_CHANGED).collect { dataVersion++ }
    }

    val provider = ThemeConfigProviders.get()
    val builtins = remember(dataVersion) { provider.getBuiltinConfigs() }
    val customs = remember(dataVersion) { provider.getConfigList() }
    val items = builtins + customs
    val builtinCount = builtins.size

    Column(Modifier.appDialogSize(fullHeight = true)) {
        DialogTitleBar(
            title = stringResource(Res.string.theme_list),
            onBack = onDismiss,
            actions = {
                IconButton(onClick = { onNewConfig(false) }) {
                    Icon(
                        painter = rememberPainter("ic_add"),
                        contentDescription = stringResource(Res.string.add),
                        tint = colors.primaryText,
                    )
                }
                IconButton(onClick = {
                    val clipText = onImportFromClip()
                    if (clipText != null) {
                        val config = parseThemeConfig(clipText)
                        if (config != null) {
                            provider.addConfig(config)
                            dataVersion++
                        }
                    }
                }) {
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
                ThemeListItem(
                    item = item,
                    configIndex = position - builtinCount,
                    builtinCount = builtinCount,
                    onClick = {
                        onDismiss()
                        if (item.isBuiltin) {
                            provider.applyBuiltin(item.isNightTheme)
                        } else {
                            provider.applyConfig(item)
                        }
                    },
                    onLongClick = {
                        if (!item.isBuiltin && position - builtinCount >= 0) {
                            onEditConfig(position - builtinCount)
                        }
                    },
                    onShare = {
                        val json = GSON.toJson(customs[position - builtinCount])
                        onShare(json)
                    },
                    onDelete = {
                        provider.delConfig(position - builtinCount)
                        dataVersion++
                    },
                )
            }
        }
    }
}

/**
 * 单个主题条目 (对照 app 端 ThemeListDialog.ThemeItem)。
 */
@Composable
private fun ThemeListItem(
    item: ThemeConfigData,
    configIndex: Int,
    builtinCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var showDeleteConfirm by remember { mutableIntStateOf(0) }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
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
            IconButton(onClick = onShare) {
                Icon(
                    painter = rememberPainter("ic_share"),
                    contentDescription = stringResource(Res.string.share),
                    tint = colors.primaryText,
                )
            }
            IconButton(onClick = { showDeleteConfirm = 1 }) {
                Icon(
                    painter = rememberPainter("ic_clear_all"),
                    contentDescription = stringResource(Res.string.delete),
                    tint = colors.primaryText,
                )
            }
        }
    }

    // 删除确认对话框 (对照 app 端 alert(R.string.delete, R.string.sure_del))
    if (showDeleteConfirm == 1) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = 0 },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del),
            okButton = AlertButton(stringResource(Res.string.ok)) {
                showDeleteConfirm = 0
                onDelete()
            },
            cancelButton = AlertButton(stringResource(Res.string.no)),
        )
    }
}

/**
 * 从 JSON 字符串解析主题配置 (对照 app 端 ThemeConfig.addConfig 的反序列化)。
 * 解析失败返回 null, 由调用方 toast 提示。
 */
private fun parseThemeConfig(json: String): ThemeConfigData? {
    return runCatching { GSON.decodeFromString(ThemeConfigData.serializer(), json) }.getOrNull()
}
