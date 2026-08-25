package io.legado.app.ui.book.read.config

// I18N KEYS (均已注册于 shared/src/commonMain/composeResources/values/strings.xml):
//   action_save, login, show_login_header, del_login_header, copy_source,
//   paste_source, log, help
// PAINTER KEYS:
//   ic_save (已存在 shared drawable)

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FormEditFields
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.text.EditEntity
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.action_save
import legado.shared.generated.resources.copy_source
import legado.shared.generated.resources.del_login_header
import legado.shared.generated.resources.help
import legado.shared.generated.resources.ic_save
import legado.shared.generated.resources.log
import legado.shared.generated.resources.login
import legado.shared.generated.resources.paste_source
import legado.shared.generated.resources.show_login_header
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * HttpTTS 编辑对话框内容 (KMP 共享, app + desktop 复用)。
 *
 * 对应 app 端 `io.legado.app.ui.book.read.config.HttpTtsEditDialog` (BaseComposeDialogFragment)
 * 的 `Content()`, 去掉对 Android Fragment / ViewModel / toastOnUi / alert / sendToClip /
 * showDialogFragment / showHelp 的依赖, 改为纯 @Composable + 回调形式:
 * - 调用方传入 [editEntities] (表单字段列表, 由调用方从 HttpTTS 构建并负责收集回 HttpTTS);
 * - 各菜单 / 按钮动作通过 lambda 回调由调用方实现 (app 端 Fragment 委托 ViewModel + 平台工具,
 *   desktop 端可自行实现等价逻辑)。
 *
 * # 原业务逻辑保留 (1:1 复刻 app 端 Content)
 *
 * - 标题栏: 空标题 + 返回 (dismiss) + 保存按钮 (ic_save) + OverflowMenu;
 * - OverflowMenu 7 项: 登录 / 查看登录头 / 删除登录头 / 拷贝源 / 粘贴源 / 日志 / 帮助;
 * - 表单区: Column(weight 1f, fill=false) + verticalScroll + FormEditFields。
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
 * @param onBack 返回按钮 (app: dismissAllowingStateLoss)
 * @param onSave 保存按钮 (app: viewModel.save(dataFromView()) { toastOnUi("保存成功") })
 * @param onLogin 登录菜单 (app: login() — hasLogin 校验 + save + showLoginDialog)
 * @param onShowLoginHeader 查看登录头菜单 (app: alert { setTitle; setMessage(loginHeader) })
 * @param onDeleteLoginHeader 删除登录头菜单 (app: dataFromView().removeLoginHeader())
 * @param onCopySource 拷贝源菜单 (app: context?.sendToClip(GSON.toJson(dataFromView())))
 * @param onPasteSource 粘贴源菜单 (app: viewModel.importFromClip { initView(it) })
 * @param onShowLog 日志菜单 (app: showDialogFragment<AppLogDialog>())
 * @param onShowHelp 帮助菜单 (app: showHelp("httpTTSHelp"))
 */
@Composable
fun HttpTtsEditDialogContent(
    editEntities: List<EditEntity>,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onShowLoginHeader: () -> Unit,
    onDeleteLoginHeader: () -> Unit,
    onCopySource: () -> Unit,
    onPasteSource: () -> Unit,
    onShowLog: () -> Unit,
    onShowHelp: () -> Unit,
) {
    val colors = AppTheme.colors
    // 预解析菜单 / 按钮文案, 避免 onClick 中误用 @Composable (对齐 TxtTocRuleEditDialog 风格)
    val saveDescText = stringResource(Res.string.action_save)
    val loginText = stringResource(Res.string.login)
    val showLoginHeaderText = stringResource(Res.string.show_login_header)
    val delLoginHeaderText = stringResource(Res.string.del_login_header)
    val copySourceText = stringResource(Res.string.copy_source)
    val pasteSourceText = stringResource(Res.string.paste_source)
    val logText = stringResource(Res.string.log)
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
                    item(loginText) { onLogin() }
                    item(showLoginHeaderText) { onShowLoginHeader() }
                    item(delLoginHeaderText) { onDeleteLoginHeader() }
                    item(copySourceText) { onCopySource() }
                    item(pasteSourceText) { onPasteSource() }
                    item(logText) { onShowLog() }
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
    onBack: () -> Unit,
    onSave: () -> Unit,
    onLogin: () -> Unit,
    onShowLoginHeader: () -> Unit,
    onDeleteLoginHeader: () -> Unit,
    onCopySource: () -> Unit,
    onPasteSource: () -> Unit,
    onShowLog: () -> Unit,
    onShowHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    AppDialog(
        onDismissRequest = onDismiss,
        properties = AppDialogSizes.properties(),
    ) {
        Surface(
            shape = DesignTokens.dialogShape,
            color = colors.fillet,
            modifier = Modifier.appDialogSize(fullHeight = true).padding(
                start = DesignTokens.spacingDefault,
                top = 16.dp,
                end = DesignTokens.spacingDefault,
                bottom = DesignTokens.spacingDefault,
            ),
        ) {
            HttpTtsEditDialogContent(
                editEntities = editEntities,
                onBack = onBack,
                onSave = onSave,
                onLogin = onLogin,
                onShowLoginHeader = onShowLoginHeader,
                onDeleteLoginHeader = onDeleteLoginHeader,
                onCopySource = onCopySource,
                onPasteSource = onPasteSource,
                onShowLog = onShowLog,
                onShowHelp = onShowHelp,
            )
        }
    }
}
