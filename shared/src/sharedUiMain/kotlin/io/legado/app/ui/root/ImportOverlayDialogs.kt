package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.PreferKey
import io.legado.app.help.IntentData
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.association.DeepLinkImportTarget
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.association.ImportTargetDialog
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.compose.component.AppSelectorDialog
import io.legado.app.ui.dialog.TextInputDialog
import io.legado.app.ui.widget.dialog.CodeDialog
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.btn_default_s
import legado.shared.generated.resources.import_file_name
import legado.shared.generated.resources.page_anim
import legado.shared.generated.resources.page_anim_cover
import legado.shared.generated.resources.page_anim_none
import legado.shared.generated.resources.page_anim_scroll
import legado.shared.generated.resources.page_anim_simulation
import legado.shared.generated.resources.page_anim_slide
import org.jetbrains.compose.resources.stringResource

/**
 * 6 个 Import 对话框 + CodeDialog 的 Overlay 渲染实现。
 *
 * 对照 app 端原 BaseComposeDialogFragment 子类 (ImportBookSourceDialog 等):
 * - source 文本经 [IntentData] 侧信道传递 (可能为大段 JSON, 不直接走 payload 字符串);
 * - VM 创建/下载/解析/勾选渲染统一走 [DeepLinkImportTarget] + [ImportTargetDialog]
 *   (与 deep link 宿主、规则订阅同一条链, 不再各自内联构造 VM 与 collect success/error)。
 */

// 通用导入 Overlay: 6 个 Import 对话框共用 (key="*Import:<DeepLinkImportType.name>", payload=IntentData key)
// 由 LegadoApp DialogOverlayContent 按类型分发; 调用方 (app 端 FileAssociationFragment / 其他端深链) 用
// AppOverlay.Dialog(key = "*Import:" + type.name, payload = IntentData.put(sourceText)) 弹窗。
@Composable
internal fun ImportSourceOverlayContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator,
    type: DeepLinkImportType,
) {
    // 侧信道取 source 文本 (对照 SourceLoginContext.put/take 模式)
    val sourceText = remember(overlay.payload) { IntentData.get<String>(overlay.payload) }
    if (sourceText.isNullOrEmpty()) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }

    val scope = rememberCoroutineScope()
    val target = remember(overlay.key) { DeepLinkImportTarget.of(type, scope) }
    if (target == null) {
        // ADD_TO_BOOKSHELF / READ_CONFIG / READ_BOOK / UNKNOWN 不走导入对话框, 无 Overlay 分支
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    LaunchedEffect(target) { target.startImport(sourceText) }
    ImportTargetDialog(
        target = target,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 按文件名导入 js 编辑框 (key="import_file_name", 对照 app 端 alertImportFileName):
// 鸿蒙无命令式文本输入宿主, 经共享 Overlay 弹 [TextInputDialog]; 确认写 PreferKey.bookImportFileName
@Composable
internal fun ImportFileNameOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val prefs = remember { PreferenceProviders.get() }
    TextInputDialog(
        title = stringResource(Res.string.import_file_name),
        message = "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
        initialValue = prefs.getString(PreferKey.bookImportFileName, ""),
        hint = "js",
        onConfirm = { text ->
            prefs.putString(PreferKey.bookImportFileName, text)
            navigator.dismissOverlay(overlay.key)
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

// 翻页动画配置选择器 (key="page_anim_config", 对照原版 showPageAnimConfig 的 6 项 selector):
// 选择器回调忽略索引 (原版怪癖), 只触发 upPageAnim + 重载; 实际动画值在界面设置弹窗配置。
// desktop/iOS/鸿蒙无命令式对话框宿主, 与 import_file_name 同走共享 Overlay; Android 仍用
// activity.selector (app 端, 不重复实现)
@Composable
internal fun PageAnimConfigSelectorOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val items = listOf(
        stringResource(Res.string.btn_default_s),
        stringResource(Res.string.page_anim_cover),
        stringResource(Res.string.page_anim_slide),
        stringResource(Res.string.page_anim_simulation),
        stringResource(Res.string.page_anim_scroll),
        stringResource(Res.string.page_anim_none),
    )
    AppSelectorDialog(
        onDismissRequest = { navigator.dismissOverlay(overlay.key) },
        title = stringResource(Res.string.page_anim),
        items = items,
        onItemSelected = {
            // 对照原版 selector { _, _ -> success() }: 忽略索引, 仅触发重载
            ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT)
            navigator.dismissOverlay(overlay.key)
        },
    )
}

// 代码查看对话框 (key="codeDialog", payload=IntentData key for code text)
@Composable
internal fun CodeDialogOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val code = remember(overlay.payload) { IntentData.get<String>(overlay.payload).orEmpty() }
    CodeDialog(
        code = code,
        disableEdit = true,
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}
