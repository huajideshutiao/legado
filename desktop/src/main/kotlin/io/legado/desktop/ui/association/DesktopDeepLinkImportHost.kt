package io.legado.desktop.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.constant.AppLog
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.association.ImportBookSourceViewModelShared
import io.legado.app.ui.association.ImportDictRuleViewModelShared
import io.legado.app.ui.association.ImportHttpTtsViewModelShared
import io.legado.app.ui.association.ImportReplaceRuleViewModelShared
import io.legado.app.ui.association.ImportThemeViewModelShared
import io.legado.app.ui.association.ImportTxtTocRuleViewModelShared
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.compose.platform.rememberString

/**
 * 桌面端 legado:// deep link 导入对话框宿主 (对照 app 端 AssociationActivity +
 * FileAssociationFragment.handleOnLineImport 的对话框分发)。
 *
 * 由 Main.kt 在 Window/AppTheme 内与 [io.legado.desktop.ui.SourceUiEventBridgeHost] 平级调用:
 * - 订阅 [LegadoDeepLinkHandler.pending] (启动参数 handleDeepLinkArgs / macOS
 *   OpenURIHandler 投递), 非 null 时按类型弹 [DesktopImportDialog];
 * - Dialog 内部 LaunchedEffect 调 startImport(src) 触发下载→解析→comparisonSource
 *   比对, 用户勾选"新增/更新/已有"后 importSelect 入库 (与手动网络导入同一条链);
 * - 确定/取消均 [LegadoDeepLinkHandler.consume] 清空隐藏。
 *
 * 暂不支持的类型 (AppLog 记录后直接消费, 见任务清单):
 * - ADD_TO_BOOKSHELF: app 端 AddToBookshelfHelper 依赖 Activity 弹窗链, 未下沉;
 * - READ_CONFIG: app 端 ReadBookConfig.import(zip bytes), ReadBookConfig 未下沉 desktop;
 * - UNKNOWN: app 端 determineType 下载嗅探 (zip→排版, json→importJson), 未下沉。
 */
@Composable
fun DesktopDeepLinkImportHost() {
    val request by LegadoDeepLinkHandler.pending.collectAsState()
    val req = request ?: return
    val scope = rememberCoroutineScope()
    // 每条请求新建 VM (避免 success/error state 残留, 与 BookSourceScreen 网络导入一致)
    val vm = remember(req) {
        when (req.type) {
            DeepLinkImportType.BOOK_SOURCE ->
                DesktopImportVm.bookSource(ImportBookSourceViewModelShared(scope))
            DeepLinkImportType.REPLACE_RULE ->
                DesktopImportVm.replaceRule(ImportReplaceRuleViewModelShared(scope))
            DeepLinkImportType.TXT_TOC_RULE ->
                DesktopImportVm.txtTocRule(ImportTxtTocRuleViewModelShared(scope))
            DeepLinkImportType.HTTP_TTS ->
                DesktopImportVm.httpTts(ImportHttpTtsViewModelShared(scope))
            DeepLinkImportType.DICT_RULE ->
                DesktopImportVm.dictRule(ImportDictRuleViewModelShared(scope))
            DeepLinkImportType.THEME ->
                DesktopImportVm.theme(ImportThemeViewModelShared(scope))
            else -> null
        }
    }
    if (vm == null) {
        LaunchedEffect(req) {
            AppLog.put("legado:// 导入类型桌面端暂未支持: type=${req.type} src=${req.src}")
            LegadoDeepLinkHandler.consume()
        }
        return
    }
    val title = rememberString(
        when (req.type) {
            DeepLinkImportType.REPLACE_RULE -> "import_replace_rule"
            DeepLinkImportType.TXT_TOC_RULE -> "import_txt_toc_rule"
            DeepLinkImportType.HTTP_TTS -> "import_tts"
            DeepLinkImportType.DICT_RULE -> "import_dict_rule"
            DeepLinkImportType.THEME -> "import_theme"
            else -> "import_book_source"
        }
    )
    DesktopImportDialog(
        title = title,
        vm = vm,
        initialText = req.src,
        onDismiss = { LegadoDeepLinkHandler.consume() },
    )
}
