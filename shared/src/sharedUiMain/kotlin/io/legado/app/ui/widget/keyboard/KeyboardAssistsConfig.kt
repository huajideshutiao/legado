package io.legado.app.ui.widget.keyboard

// 下沉说明：辅助按键配置 UI（原 app 端 KeyboardAssistsConfig BaseComposeDialogFragment 下沉）。
// 数据实体 KeyboardAssist/DAO 在 commonMain (AppDbProviders.keyboardAssistsDao), 本文件只画 UI:
// RuleManageScaffold 列表 (拖拽排序落库) + 新增/编辑弹窗 (key/value 输入)。
// 经 AppOverlay key="keyboardAssistsConfig" 渲染。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.platform.rememberNavigationBarPaddingValues
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppOverlay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.add
import legado.shared.generated.resources.assists_key_config
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.delete
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.stringResource

/**
 * 辅助按键配置 Overlay (key="keyboardAssistsConfig", 全高)。
 *
 * 对照 app 端 KeyboardAssistsConfig: DAO flow 列表 + 拖拽排序 (落定按 serialNo 重排落库) +
 * 新增/编辑弹窗 (key/value 双输入, 编辑=删旧插新保持 serialNo, 新增=maxSerialNo+1)。
 */
@Composable
internal fun KeyboardAssistsConfigOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val scope = rememberCoroutineScope()
    val items = remember(overlay.key) { mutableStateListOf<KeyboardAssist>() }
    // 编辑目标: null+show=新增; 非 null=编辑 (对照 app 端 editKey(null/item))
    var editing by remember(overlay.key) { mutableStateOf<KeyboardAssist?>(null) }
    var showEdit by remember(overlay.key) { mutableStateOf(false) }
    val onDismiss: () -> Unit = { navigator.dismissOverlay(overlay.key) }

    LaunchedEffect(overlay.key) {
        AppDbProviders.get().keyboardAssistsDao.flowAll.catch {
            AppLog.put("辅助按键配置获取数据失败\n${it.message}", it)
        }.flowOn(IoDispatcher).collect {
            items.clear()
            items.addAll(it)
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            modifier = Modifier.appDialogSize(fullHeight = true),
            shape = DesignTokens.shapeDefault,
            color = AppTheme.colors.fillet,
        ) {
            RuleManageScaffold(
                items = items,
                itemKey = { "${it.type}#${it.key}" },
                onMove = { from, to -> items.add(to, items.removeAt(from)) },
                bottomPadding = rememberNavigationBarPaddingValues(),
                titleBar = {
                    DialogTitleBar(
                        title = stringResource(Res.string.assists_key_config),
                        onBack = onDismiss,
                        actions = {
                            IconButton(onClick = {
                                editing = null
                                showEdit = true
                            }) {
                                Icon(
                                    painter = rememberPainter("ic_add"),
                                    contentDescription = stringResource(Res.string.add),
                                    tint = AppTheme.colors.primaryText,
                                )
                            }
                        },
                    )
                },
            ) { item ->
                val colors = AppTheme.colors
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            editing = item
                            showEdit = true
                        }
                        .longPressDraggableHandle(onDragStopped = { persistOrder(items, scope) })
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.key,
                        color = colors.primaryText,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = rememberPainter("ic_clear_all"),
                        contentDescription = stringResource(Res.string.delete),
                        tint = colors.primaryText,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable {
                                scope.launch(IoDispatcher) {
                                    AppDbProviders.get().keyboardAssistsDao.delete(item)
                                }
                            },
                    )
                }
            }
        }
    }

    if (showEdit) {
        val keyboardAssist = editing
        val key = remember(overlay.key, showEdit) { mutableStateOf(keyboardAssist?.key ?: "") }
        val value = remember(overlay.key, showEdit) { mutableStateOf(keyboardAssist?.value ?: "") }
        AppAlertDialog(
            onDismissRequest = { showEdit = false },
            title = "辅助按键",
            okButton = AlertButton(stringResource(Res.string.ok)) {
                scope.launch(IoDispatcher) {
                    val newKeyboardAssist = KeyboardAssist(
                        key = key.value,
                        value = value.value
                    )
                    if (keyboardAssist == null) {
                        newKeyboardAssist.serialNo =
                            AppDbProviders.get().keyboardAssistsDao.maxSerialNo() + 1
                        AppDbProviders.get().keyboardAssistsDao.insert(newKeyboardAssist)
                    } else {
                        newKeyboardAssist.serialNo = keyboardAssist.serialNo
                        AppDbProviders.get().keyboardAssistsDao.delete(keyboardAssist)
                        AppDbProviders.get().keyboardAssistsDao.insert(newKeyboardAssist)
                    }
                }
                showEdit = false
            },
            cancelButton = AlertButton(stringResource(Res.string.cancel)) { showEdit = false },
        ) {
            Column(Modifier.padding(horizontal = DesignTokens.spacingDefault)) {
                AppUnderlineTextField(
                    value = key.value,
                    onValueChange = { key.value = it },
                    label = "key",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppUnderlineTextField(
                    value = value.value,
                    onValueChange = { value.value = it },
                    label = "value",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 拖拽落定后按当前顺序重排 serialNo 并落库（对齐 app 端 persistOrder）。 */
private fun persistOrder(items: List<KeyboardAssist>, scope: kotlinx.coroutines.CoroutineScope) {
    for ((index, item) in items.withIndex()) {
        item.serialNo = index + 1
    }
    val snapshot = items.toTypedArray()
    scope.launch(IoDispatcher) {
        AppDbProviders.get().keyboardAssistsDao.update(*snapshot)
    }
}
