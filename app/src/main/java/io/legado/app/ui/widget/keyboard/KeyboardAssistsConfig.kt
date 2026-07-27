package io.legado.app.ui.widget.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.RuleManageScaffold
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 辅助按键配置
 */
class KeyboardAssistsConfig : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private val items = mutableStateListOf<KeyboardAssist>()

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            appDb.keyboardAssistsDao.flowAll.catch {
                AppLog.put("辅助按键配置获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                items.clear()
                items.addAll(it)
            }
        }
        RuleManageScaffold(
            items = items,
            itemKey = { "${it.type}#${it.key}" },
            onMove = { from, to -> items.add(to, items.removeAt(from)) },
            titleBar = {
                DialogTitleBar(
                    title = stringResource(R.string.assists_key_config),
                    actions = {
                        IconButton(onClick = { editKey(null) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.add),
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
                    .clickable { editKey(item) }
                    .longPressDraggableHandle(onDragStopped = { persistOrder() })
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
                    painter = painterResource(R.drawable.ic_clear_all),
                    contentDescription = stringResource(R.string.delete),
                    tint = colors.primaryText,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            lifecycleScope.launch(IO) {
                                appDb.keyboardAssistsDao.delete(item)
                            }
                        },
                )
            }
        }
    }

    /** 拖拽落定后按当前顺序重排 serialNo 并落库（对齐 ItemTouchCallback.onClearView） */
    private fun persistOrder() {
        for ((index, item) in items.withIndex()) {
            item.serialNo = index + 1
        }
        val snapshot = items.toTypedArray()
        lifecycleScope.launch(IO) {
            appDb.keyboardAssistsDao.update(*snapshot)
        }
    }

    private fun editKey(keyboardAssist: KeyboardAssist?) {
        alert {
            setTitle("辅助按键")
            val key = mutableStateOf(keyboardAssist?.key ?: "")
            val value = mutableStateOf(keyboardAssist?.value ?: "")
            customView {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    AppOutlinedTextField(
                        value = key.value,
                        onValueChange = { key.value = it },
                        label = "key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppOutlinedTextField(
                        value = value.value,
                        onValueChange = { value.value = it },
                        label = "value",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            cancelButton()
            okButton {
                lifecycleScope.launch(IO) {
                    val newKeyboardAssist = KeyboardAssist(
                        key = key.value,
                        value = value.value
                    )
                    if (keyboardAssist == null) {
                        newKeyboardAssist.serialNo = appDb.keyboardAssistsDao.maxSerialNo() + 1
                        appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                    } else {
                        newKeyboardAssist.serialNo = keyboardAssist.serialNo
                        appDb.keyboardAssistsDao.delete(keyboardAssist)
                        appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                    }
                }
            }
        }
    }
}
