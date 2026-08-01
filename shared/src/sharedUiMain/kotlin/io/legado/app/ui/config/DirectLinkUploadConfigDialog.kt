package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.help.DirectLinkUploadDefaultsProviders
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.DirectLinkUploadStoreProviders
import io.legado.app.help.getRuleShared
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.copy_rule
import legado.shared.generated.resources.download_url_rule
import legado.shared.generated.resources.import_default_rule
import legado.shared.generated.resources.is_compress
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.paste_rule
import legado.shared.generated.resources.summary
import legado.shared.generated.resources.test
import legado.shared.generated.resources.upload_url
import org.jetbrains.compose.resources.stringResource

/**
 * 直链上传规则配置对话框 (KMP 共享, 对照 app 端 DirectLinkUploadConfig Fragment)。
 *
 * 表单四字段 + 溢出菜单(复制/粘贴/导入默认) + 底部 测试/取消/确定。
 * 平台专属能力(剪贴板/selector/test/alert)通过回调注入。
 *
 * @param onDismiss 关闭回调
 * @param onToast 显示 toast
 * @param onGetClip 获取剪贴板文本 (null=剪贴板为空)
 * @param onSetClip 写入剪贴板
 * @param onSelector 选择器弹窗 (items + 返回选中项)
 * @param onTest 测试上传 (rule + 成功回调/失败回调)
 * @param onAlertResult 显示测试结果 (result: 测试返回内容)
 */
@Composable
fun DirectLinkUploadConfigDialog(
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
    onGetClip: () -> String?,
    onSetClip: (String) -> Unit,
    onSelector: (List<String>, (Int) -> Unit) -> Unit,
    onTest: (DirectLinkUploadRule, (String) -> Unit, (String) -> Unit) -> Unit,
) {
    val colors = AppTheme.colors
    // 初始加载规则 (对照 app 端 onCreate upView(DirectLinkUpload.getRule()))
    val initRule = remember { getRuleShared() }
    var uploadUrl by remember { mutableStateOf(initRule.uploadUrl) }
    var downloadUrlRule by remember { mutableStateOf(initRule.downloadUrlRule) }
    var summary by remember { mutableStateOf(initRule.summary) }
    var compress by remember { mutableStateOf(initRule.compress) }

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = "",
            onBack = onDismiss,
            actions = {
                OverflowMenu { dismissMenu ->
                    DropdownMenuItem(
                        onClick = {
                            dismissMenu()
                            getRule(
                                uploadUrl,
                                downloadUrlRule,
                                summary,
                                compress,
                                onToast
                            )?.let { rule ->
                                onSetClip(GSON.toJson(rule))
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.copy_rule), color = colors.primaryText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismissMenu()
                            val clipText = onGetClip()
                            if (clipText != null) {
                                runCatching {
                                    GSON.fromJsonObject<DirectLinkUploadRule>(clipText).getOrThrow()
                                }.onSuccess { rule ->
                                    uploadUrl = rule.uploadUrl
                                    downloadUrlRule = rule.downloadUrlRule
                                    summary = rule.summary
                                    compress = rule.compress
                                }.onFailure {
                                    onToast("剪贴板为空或格式不对")
                                }
                            } else {
                                onToast("剪贴板为空或格式不对")
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.paste_rule), color = colors.primaryText)
                    }
                    DropdownMenuItem(
                        onClick = {
                            dismissMenu()
                            val defaults =
                                DirectLinkUploadDefaultsProviders.get()?.getDefaultRules()
                                    ?: emptyList()
                            onSelector(defaults.map { it.summary }) { index ->
                                val rule = defaults[index]
                                uploadUrl = rule.uploadUrl
                                downloadUrlRule = rule.downloadUrlRule
                                summary = rule.summary
                                compress = rule.compress
                            }
                        },
                    ) {
                        Text(
                            stringResource(Res.string.import_default_rule),
                            color = colors.primaryText
                        )
                    }
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            AppOutlinedTextField(
                value = uploadUrl,
                onValueChange = { uploadUrl = it },
                label = stringResource(Res.string.upload_url),
                modifier = Modifier.fillMaxWidth(),
            )
            AppOutlinedTextField(
                value = downloadUrlRule,
                onValueChange = { downloadUrlRule = it },
                label = stringResource(Res.string.download_url_rule),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            AppOutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = stringResource(Res.string.summary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .toggleable(
                        value = compress,
                        role = Role.Checkbox,
                        onValueChange = { compress = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppCheckbox(checked = compress, onCheckedChange = null)
                Text(stringResource(Res.string.is_compress), color = colors.primaryText)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextButton(text = stringResource(Res.string.test)) {
                val rule = getRule(uploadUrl, downloadUrlRule, summary, compress, onToast)
                if (rule != null) {
                    onTest(rule, { result -> onToast(result) }, { err -> onToast(err) })
                }
            }
            Spacer(Modifier.weight(1f))
            AppTextButton(text = stringResource(Res.string.cancel)) { onDismiss() }
            AppTextButton(text = stringResource(Res.string.ok)) {
                getRule(uploadUrl, downloadUrlRule, summary, compress, onToast)?.let { rule ->
                    DirectLinkUploadStoreProviders.get()?.putConfig(rule)
                    onDismiss()
                }
            }
        }
    }
}

/** 校验表单, 返回 DirectLinkUploadRule 或 null (toast 提示) */
private fun getRule(
    uploadUrl: String,
    downloadUrlRule: String,
    summary: String,
    compress: Boolean,
    onToast: (String) -> Unit,
): DirectLinkUploadRule? {
    if (uploadUrl.isBlank()) {
        onToast("上传Url不能为空")
        return null
    }
    if (downloadUrlRule.isBlank()) {
        onToast("下载Url规则不能为空")
        return null
    }
    if (summary.isBlank()) {
        onToast("注释不能为空")
        return null
    }
    return DirectLinkUploadRule(uploadUrl, downloadUrlRule, summary, compress)
}
