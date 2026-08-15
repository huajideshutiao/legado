package io.legado.app.ui.book.toc.rule

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "txt_toc_rule_edit_name" to "名称",
//   "txt_toc_rule_edit_rule" to "规则",
//   "txt_toc_rule_edit_example" to "示例",
//   "txt_toc_rule_edit_name_required" to "名称不能为空",
//   "txt_toc_rule_edit_regex_error" to "正则语法错误或不支持(txt)",
//   "txt_toc_rule_edit_copy_success" to "已复制"

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.copy_rule
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.paste_rule
import legado.shared.generated.resources.txt_toc_rule
import legado.shared.generated.resources.txt_toc_rule_edit_copy_success
import legado.shared.generated.resources.txt_toc_rule_edit_example
import legado.shared.generated.resources.txt_toc_rule_edit_name
import legado.shared.generated.resources.txt_toc_rule_edit_name_required
import legado.shared.generated.resources.txt_toc_rule_edit_regex_error
import legado.shared.generated.resources.txt_toc_rule_edit_rule
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * TXT 目录规则编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.toc.rule.TxtTocRuleEditDialog` (BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / Bundle / viewModelScope / getClipText / sendToClip / toastOnUi 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [rule] (null=新增), [onConfirm] / [onDismiss] 回调;
 * - [clipTextProvider] 注入剪贴板读取能力 (app=getClipText / desktop=AWT Clipboard);
 * - [clipTextSink] 可选注入剪贴板写入能力, null 时不渲染"复制规则"菜单项 (与原 app 端行为对齐)。
 *
 * # 业务对齐 (对照 app 端原版)
 *
 * - 字段: 名称 (name) / 规则 (rule, 正则) / 示例 (example) 三个 EditEntity, 与原版完全一致;
 * - 保存校验: 名称不能为空 + 正则语法校验 (Pattern.MULTILINE 等价于 [RegexOption.MULTILINE]);
 * - 标题栏右侧: 保存 (ic_save) + OverflowMenu (复制规则 / 粘贴规则);
 * - 粘贴规则: 委托 [TxtTocRuleEditViewModelShared.pasteRule], 内部走 [clipTextProvider] 取剪贴板,
 *   GSON.fromJsonObject<TxtTocRule>(text) 解析, 失败 toast "格式不对" (与 app 端一致);
 * - 复制规则: GSON.toJson(rule) + [clipTextSink] 写剪贴板 + toast "已复制" (app 端原版仅 sendToClip
 *   无 toast 提示, 这里加 toast 是因为桌面端无系统级"已复制"反馈, 与 SourceLoginDialog 风格对齐);
 * - 错误日志: 正则校验失败用 [AppLog.put] 记录 (与 app 端 `AppLog.put("正则语法错误或不支持(txt)：" + ...,
 *   ex, true)` 完全等价, 第三个参数 toast=true 也会触发宿主 toast)。
 *
 * @param rule 待编辑规则 (null=新增); 新增时构造一个新的 [TxtTocRule] (id 用 Clock.System.now())
 * @param onConfirm 用户点击保存按钮且校验通过, 参数为组装后的 TxtTocRule
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部)
 * @param clipTextProvider 剪贴板文本提供者 (替代 app 端 `getClipText()`):
 *   - app 端实现 `{ getClipText() }`
 *   - desktop 端实现用 `Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String`
 * @param clipTextSink 剪贴板文本写入器 (替代 `context.sendToClip(text)`), null 时不显示复制规则菜单项
 */
@Composable
fun TxtTocRuleEditDialog(
    rule: TxtTocRule?,
    onConfirm: (TxtTocRule) -> Unit,
    onDismiss: () -> Unit,
    clipTextProvider: () -> String?,
    clipTextSink: ((String) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // 所有字符串一次性在 @Composable 主体内 rememberString, 避免 onClick 中误用 @Composable
    val titleText = stringResource(Res.string.txt_toc_rule)
    val nameLabelText = stringResource(Res.string.txt_toc_rule_edit_name)
    val ruleLabelText = stringResource(Res.string.txt_toc_rule_edit_rule)
    val exampleLabelText = stringResource(Res.string.txt_toc_rule_edit_example)
    val saveDescText = stringResource(Res.string.action_save)
    val copyRuleText = stringResource(Res.string.copy_rule)
    val pasteRuleText = stringResource(Res.string.paste_rule)
    val copySuccessText = stringResource(Res.string.txt_toc_rule_edit_copy_success)
    val nameRequiredText = stringResource(Res.string.txt_toc_rule_edit_name_required)
    val regexErrorText = stringResource(Res.string.txt_toc_rule_edit_regex_error)

    // 构造 Shared VM (委托 pasteRule 业务逻辑, 与 app 端 ViewModel 内部持有 shared 模式对齐)
    val scope = rememberCoroutineScope()
    val shared = remember(rule) {
        TxtTocRuleEditViewModelShared(scope, clipTextProvider)
    }
    // 与 app 端 onCreate -> initData(id, finally) 等价: rule 直接赋值 (不走 DAO 按 id 重查)
    LaunchedEffect(rule) {
        shared.tocRule = rule
    }

    var name by remember(rule) { mutableStateOf(rule?.name.orEmpty()) }
    var ruleContent by remember(rule) { mutableStateOf(rule?.rule.orEmpty()) }
    var example by remember(rule) { mutableStateOf(rule?.example.orEmpty()) }

    /**
     * 组装当前输入框值为 TxtTocRule, 与 app 端 getRuleFromView 完全等价:
     * - 编辑已有规则时复用 shared.tocRule (保留 id 等字段);
     * - 新增时若 shared.tocRule 为空则 new 一个并写回 shared.tocRule (与 app 端 `viewModel.tocRule = this` 等价)。
     */
    fun getRuleFromView(): TxtTocRule {
        val tocRule = shared.tocRule ?: TxtTocRule().also { shared.tocRule = it }
        tocRule.name = name
        tocRule.rule = ruleContent
        tocRule.example = example
        return tocRule
    }

    /**
     * 校验并保存, 与 app 端 save() + checkValid() 完全等价:
     * - 名称非空校验;
     * - 正则语法校验 (Pattern.MULTILINE 等价于 [RegexOption.MULTILINE]);
     * - 校验通过回调 [onConfirm] + [onDismiss]。
     *
     * 正则错误用 AppLog.put(msg, ex, toast=true) 记录 (toast=true 触发宿主 toast, 与 app 端一致)。
     */
    fun saveAndDismiss() {
        val tocRule = getRuleFromView()
        if (tocRule.name.isEmpty()) {
            Toasters.get().toast(nameRequiredText)
            return
        }
        try {
            // Pattern.compile(rule, Pattern.MULTILINE) 等价于 Regex(rule, RegexOption.MULTILINE)
            Regex(tocRule.rule, RegexOption.MULTILINE)
        } catch (ex: Exception) {
            AppLog.put(
                "$regexErrorText：${ex.message}",
                ex,
                true,
            )
            return
        }
        onConfirm(tocRule)
        onDismiss()
    }

    Surface(
        shape = DesignTokens.dialogShape,
        color = colors.fillet,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = titleText,
                onBack = onDismiss,
                actions = {
                    IconButton(onClick = { saveAndDismiss() }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_save),
                            contentDescription = saveDescText,
                            tint = DesignTokens.arcoBlue6,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        // 复制规则: 仅当调用方提供 clipTextSink 时渲染 (与 app 端 sendToClip 等价)
                        if (clipTextSink != null) {
                            DropdownMenuItem(
                                onClick = {
                                    dismissMenu()
                                    val json = GSON.toJson(getRuleFromView())
                                    clipTextSink(json)
                                    Toasters.get().toast(copySuccessText)
                                },
                            ) {
                                Text(copyRuleText, color = colors.primaryText)
                            }
                        }
                        DropdownMenuItem(
                            onClick = {
                                dismissMenu()
                                shared.pasteRule { pasted ->
                                    // 与 app 端 upRuleView 一致: 把粘贴的规则字段写回输入框
                                    name = pasted.name
                                    ruleContent = pasted.rule
                                    example = pasted.example.orEmpty()
                                }
                            },
                        ) {
                            Text(pasteRuleText, color = colors.primaryText)
                        }
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                AppUnderlineTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = nameLabelText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.heightIn(min = 8.dp))
                AppUnderlineTextField(
                    value = ruleContent,
                    onValueChange = { ruleContent = it },
                    label = ruleLabelText,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.heightIn(min = 8.dp))
                AppUnderlineTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = exampleLabelText,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
