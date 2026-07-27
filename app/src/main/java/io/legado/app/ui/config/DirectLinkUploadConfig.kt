package io.legado.app.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 直链上传规则配置（迁 dialog_form_edit + FormAdapter → Compose）。
 * 表单四字段（上传Url/下载Url规则/注释/压缩）+ 溢出菜单（复制/粘贴/导入默认）+ 底部 测试/取消/确定，
 * 校验与保存逻辑逐字保留（DirectLinkUpload.putConfig）。
 */
class DirectLinkUploadConfig : BaseComposeDialogFragment() {

    private var uploadUrl by mutableStateOf("")
    private var downloadUrlRule by mutableStateOf("")
    private var summary by mutableStateOf("")
    private var compress by mutableStateOf(false)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            uploadUrl = savedInstanceState.getString("uploadUrl") ?: ""
            downloadUrlRule = savedInstanceState.getString("downloadUrlRule") ?: ""
            summary = savedInstanceState.getString("summary") ?: ""
            compress = savedInstanceState.getBoolean("compress")
        } else {
            upView(DirectLinkUpload.getRule())
        }
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("uploadUrl", uploadUrl)
        outState.putString("downloadUrlRule", downloadUrlRule)
        outState.putString("summary", summary)
        outState.putBoolean("compress", compress)
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            // 原 View 版 setupTitleBar 未设标题（仅菜单），保持空标题等价
            DialogTitleBar(
                title = "",
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    OverflowMenu { dismissMenu ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.copy_rule), color = colors.primaryText)
                            },
                            onClick = {
                                dismissMenu()
                                getRule()?.let { rule ->
                                    requireContext().sendToClip(GSON.toJson(rule))
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.paste_rule), color = colors.primaryText)
                            },
                            onClick = {
                                dismissMenu()
                                runCatching {
                                    getClipText()!!.let {
                                        val rule = GSON.fromJsonObject<DirectLinkUploadRule>(it)
                                            .getOrThrow()
                                        upView(rule)
                                    }
                                }.onFailure {
                                    toastOnUi("剪贴板为空或格式不对")
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.import_default_rule),
                                    color = colors.primaryText
                                )
                            },
                            onClick = { dismissMenu(); importDefault() },
                        )
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
                    label = stringResource(R.string.upload_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                AppOutlinedTextField(
                    value = downloadUrlRule,
                    onValueChange = { downloadUrlRule = it },
                    label = stringResource(R.string.download_url_rule),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                AppOutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = stringResource(R.string.summary),
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
                    Text(stringResource(R.string.is_compress), color = colors.primaryText)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(text = stringResource(R.string.test)) { test() }
                Spacer(Modifier.weight(1f))
                AppTextButton(text = stringResource(R.string.cancel)) { dismiss() }
                AppTextButton(text = stringResource(R.string.ok)) {
                    getRule()?.let { rule ->
                        DirectLinkUpload.putConfig(rule)
                        dismiss()
                    }
                }
            }
        }
    }

    private fun upView(rule: DirectLinkUploadRule) {
        uploadUrl = rule.uploadUrl
        downloadUrlRule = rule.downloadUrlRule
        summary = rule.summary
        compress = rule.compress
    }

    private fun getRule(): DirectLinkUploadRule? {
        if (uploadUrl.isBlank()) {
            toastOnUi("上传Url不能为空")
            return null
        }
        if (downloadUrlRule.isBlank()) {
            toastOnUi("下载Url规则不能为空")
            return null
        }
        if (summary.isBlank()) {
            toastOnUi("注释不能为空")
            return null
        }
        return DirectLinkUploadRule(uploadUrl, downloadUrlRule, summary, compress)
    }

    private fun importDefault() {
        requireContext().selector(DirectLinkUpload.getDefaultRules()) { _, rule, _ ->
            upView(rule)
        }
    }

    private fun test() {
        val rule = getRule() ?: return
        execute {
            DirectLinkUpload.upLoad("test.json", "{}", "application/json", rule)
        }.onError {
            alertTestResult(it.localizedMessage ?: "ERROR")
        }.onSuccess { result ->
            alertTestResult(result)
        }
    }

    private fun alertTestResult(result: String) {
        alert {
            setTitle("result")
            setMessage(result)
            okButton()
            negativeButton(R.string.copy_text) {
                appCtx.sendToClip(result)
            }
        }
    }

}
