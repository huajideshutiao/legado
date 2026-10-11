package io.legado.app.ui.book.read.config

// I18N KEYS (均已注册于 ui/src/commonMain/composeResources/values/strings.xml):
//   action_save, login, test, copy_source, paste_source, help
// PAINTER KEYS:
//   ic_save (已存在 shared drawable)

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppDialogSurface
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FormEditFields
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.text.EditEntity
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.action_save
import legado.ui.generated.resources.copy_source
import legado.ui.generated.resources.help
import legado.ui.generated.resources.ic_save
import legado.ui.generated.resources.login
import legado.ui.generated.resources.paste_source
import legado.ui.generated.resources.test
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * HttpTTS 编辑对话框内容 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.HttpTtsEditDialog` (原命令式对话框基类(已删除))
 * 的 `Content()`, 去掉对 Android Fragment / ViewModel / toastOnUi / alert / sendToClip /
 * showDialogFragment / showHelp 的依赖, 改为纯 @Composable + 回调形式:
 * - 调用方传入 [editEntities] (表单字段列表, 由调用方从 HttpTTS 构建并负责收集回 HttpTTS);
 * - 各菜单 / 按钮动作通过 lambda 回调由调用方实现 (app 端 Fragment 委托 ViewModel + 平台工具,
 *   desktop 端可自行实现等价逻辑)。
 *
 * # 菜单形态 (2026-10-12 用户拍板, 对齐书源编辑界面)
 *
 * - 标题栏: 空标题 + 返回 (dismiss) + 保存按钮 (ic_save) + OverflowMenu;
 * - 溢出菜单: 登录 (仅当 [hasLogin] 为 true, 对齐书源编辑界面登录项显隐) /
 *   测试 (试听当前表单内容) / 拷贝源 / 粘贴源 / 帮助;
 * - 原版的 查看登录头/删除登录头/日志 三项不保留 (登录头查看/删除只在登录表单对话框提供,
 *   与原版一致); 表单区: Column(weight 1f, fill=false) + verticalScroll + FormEditFields。
 *
 * # 与原版的差异 (KMP 限制)
 *
 * - 文案: 原 `stringResource(R.string.*)` 改为 `stringResource(Res.string.*)`
 *   (shared compose-multiplatform resources, 调用方无感知);
 * - 图标: 原 `rememberPainter("ic_save")` 改为 `painterResource(Res.drawable.ic_save)`
 *   (shared compose-multiplatform resources);
 * - 菜单项 helper: 原 `item(textRes: Int, onClick)` 改为 `item(text: String, onClick)`
 *   (shared 端在 @Composable 主体内预解析文案, 避免在 onClick 中误用 @Composable)。
 *
 * @param editEntities 表单字段列表 (调用方负责从 HttpTTS 构建 + 从字段收集回 HttpTTS)
 * @param hasLogin 当前表单是否有登录入口 (每次菜单展开时求值, 对齐书源编辑 onMenuOpened 语义)
 * @param onBack 返回按钮 (app: dismissAllowingStateLoss)
 * @param onSave 保存按钮 (app: viewModel.save(dataFromView()) { toastOnUi("保存成功") })
 * @param onLogin 登录菜单 (调用方先保存再弹登录)
 * @param onTestSpeak 测试菜单 (试听当前表单内容, 不落库)
 * @param onCopySource 拷贝源菜单 (app: context?.sendToClip(GSON.toJson(dataFromView())))
 * @param onPasteSource 粘贴源菜单 (app: viewModel.importFromClip { initView(it) })
 * @param onShowHelp 帮助菜单 (app: showHelp("httpTTSHelp"))
 */
@Composable
fun HttpTtsEditDialogContent(
    editEntities: List<EditEntity>,
    hasLogin: () -> Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onTestSpeak: () -> Unit,
    onCopySource: () -> Unit,
    onPasteSource: () -> Unit,
    onShowHelp: () -> Unit,
) {
    val colors = AppTheme.colors
    // 预解析菜单 / 按钮文案, 避免 onClick 中误用 @Composable (对齐 TxtTocRuleEditDialog 风格)
    val saveDescText = stringResource(Res.string.action_save)
    val loginText = stringResource(Res.string.login)
    val testText = stringResource(Res.string.test)
    val copySourceText = stringResource(Res.string.copy_source)
    val pasteSourceText = stringResource(Res.string.paste_source)
    val helpText = stringResource(Res.string.help)

    Column(Modifier.fillMaxWidth()) {
        DialogTitleBar(
            title = "",
            onBack = onBack,
            actions = {
                IconButton(onClick = onSave) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = saveDescText,
                        tint = colors.primaryText,
                    )
                }
                OverflowMenu { dismissMenu ->
                    // 与原版一致的 local @Composable item helper (text 改为预解析 String)
                    @Composable
                    fun item(text: String, onClick: () -> Unit) {
                        DropdownMenuItem(
                            onClick = { dismissMenu(); onClick() },
                        ) { Text(text, color = colors.primaryText) }
                    }
                    // 登录项显隐对齐书源编辑界面: 无登录入口不显示
                    // (DropdownMenu content 在展开时新组合, 每次展开重新求值 hasLogin)
                    if (hasLogin()) {
                        item(loginText) { onLogin() }
                    }
                    item(testText) { onTestSpeak() }
                    item(copySourceText) { onCopySource() }
                    item(pasteSourceText) { onPasteSource() }
                    item(helpText) { onShowHelp() }
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            FormEditFields(editEntities)
        }
    }
}

/**
 * HttpTTS 编辑对话框 (带 Dialog 窗口, 供桌面 / iOS / 鸿蒙端直接使用)。
 *
 * app 端使用 [HttpTtsEditDialogContent] 嵌入自身 DialogFragment, 不调用本函数 (避免双层窗口)。
 *
 * @see HttpTtsEditDialogContent
 */
@Composable
fun HttpTtsEditDialog(
    editEntities: List<EditEntity>,
    hasLogin: () -> Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onTestSpeak: () -> Unit,
    onCopySource: () -> Unit,
    onPasteSource: () -> Unit,
    onShowHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        AppDialogSurface(
            modifier = Modifier.appDialogSize(),
        ) {
            HttpTtsEditDialogContent(
                editEntities = editEntities,
                hasLogin = hasLogin,
                onBack = onBack,
                onSave = onSave,
                onLogin = onLogin,
                onTestSpeak = onTestSpeak,
                onCopySource = onCopySource,
                onPasteSource = onPasteSource,
                onShowHelp = onShowHelp,
            )
        }
    }
}
