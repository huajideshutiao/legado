package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.FileCacheProviders
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.splitNotBlank
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ic_baseline_close
import legado.shared.generated.resources.import_on_line
import legado.shared.generated.resources.ok
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 在线导入 URL 输入对话框 (带历史记录, KMP 共享)。
 *
 * 对应 app 端 `showImportDialog()` (ReplaceRuleActivity 等): editTextView 的
 * filterValues 历史下拉 + onDelete 删除 + okButton 记录合法 URL。
 * 历史走 [FileCacheProviders] (app 端 ACache 的 KMP 抽象), 逗号拼接持久化。
 * persistent = true 对齐原版 `ACache.get(cacheDir = false)`, 清缓存不丢历史。
 *
 * @param recordKey 历史记录缓存 key (与 app 端一致, 如 "replaceRuleRecordKey")
 * @param onConfirm 用户确认非空 URL (历史记录已在内部处理)
 * @param defaultUrl 可选默认 URL, 不在历史中时插到首位 (对照 TxtTocRuleActivity)
 */
@Composable
fun OnlineImportUrlDialog(
    recordKey: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    defaultUrl: String? = null,
) {
    val colors = AppTheme.colors
    var url by remember { mutableStateOf("") }
    // 历史列表 (对照 app 端 cacheUrls: 读缓存逗号拆分, defaultUrl 不在列表时插首位)
    val cacheUrls = remember {
        val urls = FileCacheProviders.get().getAsString(recordKey, persistent = true)
            ?.splitNotBlank(",")?.toMutableList() ?: mutableListOf()
        if (defaultUrl != null && !urls.contains(defaultUrl)) urls.add(0, defaultUrl)
        urls.toMutableStateList()
    }

    fun persist() {
        FileCacheProviders.get().put(recordKey, cacheUrls.joinToString(","), persistent = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.appDialogSize(),
        properties = AppDialogSizes.properties(),
        title = {
            Text(
                text = stringResource(Res.string.import_on_line),
                color = colors.primaryText,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "url",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 历史记录列表 (点击回填输入框, 删除按钮移除并持久化)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    cacheUrls.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { url = item }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item,
                                color = colors.secondaryText,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    cacheUrls.remove(item)
                                    persist()
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_baseline_close),
                                    contentDescription = null,
                                    tint = colors.secondaryText,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = url.trim()
                // 与 app 端一致: 合法 URL 且不在历史中才记录 (插首位)
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    persist()
                }
                onDismiss()
                if (text.isNotEmpty()) onConfirm(text)
            }) {
                Text(text = stringResource(Res.string.ok), color = DesignTokens.arcoBlue6)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cancel), color = colors.secondaryText)
            }
        },
        shape = DesignTokens.dialogShape,
        backgroundColor = colors.fillet,
    )
}
