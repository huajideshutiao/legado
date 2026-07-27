package io.legado.app.ui.dict.rule

// I18N KEYS (need to register in ResourceProvider.jvm.kt):
//   "dict_rule_edit_name" to "名称",
//   "dict_rule_edit_url_rule" to "URL 规则",
//   "dict_rule_edit_show_rule" to "显示规则",
//   "dict_rule_edit_copy_success" to "已复制"

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.DictRule
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme

/**
 * Arco Design 主色 arcoblue-6 (#165DFF)。
 *
 * 用作对话框标题栏保存图标 + 复制规则成功提示的强调色。
 * 不复用 AppTheme.accent, 避免不同主题下颜色漂移导致与原 app 端 arco 规范不一致。
 */
private val ArcoBlue6 = Color(0xFF165DFF)

/**
 * 字典规则编辑对话框 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.dict.rule.DictRuleEditDialog` (BaseComposeDialogFragment),
 * 但去掉对 Android Fragment / Bundle / viewModelScope / getClipText / sendToClip 的依赖,
 * 改为纯 @Composable + 回调形式:
 * - 调用方传入 [rule] (null=新增), [onConfirm] / [onDismiss] 回调;
 * - [clipTextProvider] 注入剪贴板读取能力 (app=getClipText / desktop=AWT Clipboard);
 * - [clipTextSink] 可选注入剪贴板写入能力, null 时不渲染"复制规则"菜单项 (与原 app 端行为对齐)。
 *
 * # 业务对齐 (对照 app 端原版)
 *
 * - 字段: 名称 (name) / URL 规则 (urlRule) / 显示规则 (showRule) 三个 EditEntity, 与原版完全一致;
 * - 标题栏右侧: 保存 (ic_save) + OverflowMenu (复制规则 / 粘贴规则);
 * - 保存: 委托 [DictRuleEditViewModelShared.save] (delete 旧 + insert 新), 完成后回调 [onConfirm]
 *   + [onDismiss] (与 app 端 `viewModel.save(getDictRule()) { dismiss }` 等价,
 *   原 onFinally 在成功/失败均触发, 与 app 端一致);
 * - 复制规则: 委托 [DictRuleEditViewModelShared.copyRule], 内部走 [clipTextSink] 写剪贴板
 *   (GSON.toJson(rule)), 不再额外 toast (与 app 端原版"sendToClip 无 toast"行为一致);
 * - 粘贴规则: 委托 [DictRuleEditViewModelShared.pasteRule], 内部走 [clipTextProvider] 取剪贴板,
 *   GSON.fromJsonObject<DictRule>(text).getOrThrow() 解析, 失败 toast "格式不对" (与 app 端一致);
 * - getDictRule: `shared.dictRule?.copy() ?: DictRule()` (与 app 端完全一致, copy 避免污染 shared 状态)。
 *
 * @param rule 待编辑规则 (null=新增)
 * @param onConfirm 用户点击保存按钮, 参数为组装后的 DictRule (save 已写 DB, 此回调用于刷新 UI)
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部)
 * @param clipTextProvider 剪贴板文本提供者 (替代 app 端 `getClipText()`):
 *   - app 端实现 `{ getClipText() }`
 *   - desktop 端实现用 `Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String`
 * @param clipTextSink 剪贴板文本写入器 (替代 `context.sendToClip(text)`), null 时不显示复制规则菜单项
 */
@Composable
fun DictRuleEditDialog(
    rule: DictRule?,
    onConfirm: (DictRule) -> Unit,
    onDismiss: () -> Unit,
    clipTextProvider: () -> String?,
    clipTextSink: ((String) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    // 所有字符串一次性在 @Composable 主体内 rememberString, 避免 onClick 中误用 @Composable
    val titleText = rememberString("dict_rule")
    val nameLabelText = rememberString("dict_rule_edit_name")
    val urlRuleLabelText = rememberString("dict_rule_edit_url_rule")
    val showRuleLabelText = rememberString("dict_rule_edit_show_rule")
    val saveDescText = rememberString("action_save")
    val copyRuleText = rememberString("copy_rule")
    val pasteRuleText = rememberString("paste_rule")
    val copySuccessText = rememberString("dict_rule_edit_copy_success")

    // 构造 Shared VM (委托 save/copyRule/pasteRule 业务逻辑, 与 app 端 ViewModel 内部持有 shared 模式对齐)
    val scope = rememberCoroutineScope()
    val shared = remember(rule) {
        // clipTextSink 为 null 时传空 lambda (Shared VM 构造要求非空), UI 端不会调用 copyRule 路径
        DictRuleEditViewModelShared(
            scope = scope,
            clipTextProvider = clipTextProvider,
            clipTextSink = clipTextSink ?: {},
        )
    }
    // 与 app 端 onCreate -> initData(name, onFinally) 等价: rule 直接赋值 (不走 DAO 按 name 重查)
    LaunchedEffect(rule) {
        shared.dictRule = rule
    }

    var name by remember(rule) { mutableStateOf(rule?.name.orEmpty()) }
    var urlRule by remember(rule) { mutableStateOf(rule?.urlRule.orEmpty()) }
    var showRule by remember(rule) { mutableStateOf(rule?.showRule.orEmpty()) }

    /**
     * 组装当前输入框值为 DictRule, 与 app 端 getDictRule() 完全等价:
     * - 编辑已有规则时 shared.dictRule?.copy() (保留原规则其它字段, 不污染 shared 状态);
     * - 新增时 new DictRule()。
     */
    fun getDictRule(): DictRule {
        val dictRule = shared.dictRule?.copy() ?: DictRule()
        dictRule.name = name
        dictRule.urlRule = urlRule
        dictRule.showRule = showRule
        return dictRule
    }

    /**
     * 保存, 与 app 端 save() 等价:
     * - 调 shared.save(newRule) { ... } (内部 delete 旧 + insert 新, 与 app 端一致);
     * - onFinally 回调中触发 [onConfirm] (刷新 UI) + [onDismiss] (关闭对话框)。
     *
     * 注意: shared.save 用 onFinally (非 onSuccess), 失败也会回调, 与 app 端原版一致。
     */
    fun save() {
        val newRule = getDictRule()
        shared.save(newRule) {
            onConfirm(newRule)
            onDismiss()
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.background,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = titleText,
                onBack = onDismiss,
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            painter = rememberPainter("ic_save"),
                            contentDescription = saveDescText,
                            tint = ArcoBlue6,
                        )
                    }
                    OverflowMenu { dismissMenu ->
                        // 复制规则: 仅当调用方提供 clipTextSink 时渲染 (与 app 端 sendToClip 等价)
                        if (clipTextSink != null) {
                            DropdownMenuItem(
                                text = { Text(copyRuleText, color = colors.primaryText) },
                                onClick = {
                                    dismissMenu()
                                    // shared.copyRule 内部: GSON.toJson(rule) + clipTextSink(text)
                                    shared.copyRule(getDictRule())
                                    Toasters.get().toast(copySuccessText)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(pasteRuleText, color = colors.primaryText) },
                            onClick = {
                                dismissMenu()
                                shared.pasteRule { pasted ->
                                    // 与 app 端 upRuleView 一致: 把粘贴的规则字段写回输入框
                                    name = pasted.name
                                    urlRule = pasted.urlRule
                                    showRule = pasted.showRule
                                }
                            },
                        )
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                AppOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = nameLabelText,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.heightIn(min = 8.dp))
                AppOutlinedTextField(
                    value = urlRule,
                    onValueChange = { urlRule = it },
                    label = urlRuleLabelText,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.heightIn(min = 8.dp))
                AppOutlinedTextField(
                    value = showRule,
                    onValueChange = { showRule = it },
                    label = showRuleLabelText,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
