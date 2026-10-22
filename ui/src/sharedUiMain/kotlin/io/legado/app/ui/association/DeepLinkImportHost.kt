package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.legado.app.constant.AppLog
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.model.webBook.WebBook.matchBookSourceByUrl
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.dialog.awaitWaitDialogDismissed
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.deep_link_type_not_supported
import legado.ui.generated.resources.import_complete
import org.jetbrains.compose.resources.stringResource

/**
 * 无"勾选列表"的四型 deep link: 全平台统一走共享实现 ([runSchemeImport]), 无平台优先分支。
 * [DeepLinkImportTarget.of] 对这四型返回 null (见其 KDoc), 不再进"不支持"提示分支。
 */
private val schemeOnlyTypes = setOf(
    DeepLinkImportType.ADD_TO_BOOKSHELF,
    DeepLinkImportType.READ_CONFIG,
    DeepLinkImportType.READ_BOOK,
    DeepLinkImportType.UNKNOWN,
)

/**
 * 直通四型 (addToBookshelf/read/readConfig/unknown) 的共享实现:
 * - ADD_TO_BOOKSHELF: 直接跳详情页;
 * - READ_BOOK: 书架内直接阅读, 不在书架抓详情进详情页;
 * - READ_CONFIG: 一步导入完成;
 * - UNKNOWN: **先走书籍识别** (零网络按各源 bookUrlPattern 认领这条地址) —— 被认领就直接带
 *   着这个源进抓书链 (不再重算认领), 不被认领才下载嗅探内容类型 —— zip 当排版配置一步完成,
 *   JSON 识别出具体类型后转生新请求 (对照 app 端 determineType 的分流, 转生后 pending 不再是 [req],
 *   本函数不再负责消费)。
 *
 * @param beforeNavigate 两型导航前的等待钩子 (收起加载框并等它离开界面), 由宿主传入。
 */
private suspend fun runSchemeImport(
    req: DeepLinkImportRequest,
    beforeNavigate: suspend () -> Unit,
) {
    when (req.type) {
        DeepLinkImportType.ADD_TO_BOOKSHELF ->
            runCatching { AddToBookshelfShared.add(req.src, beforeNavigate) }
                .onFailure {
                    AppLog.put("添加书籍 ${req.src} 出错", it)
                    Toasters.get().toast(it.message ?: "")
                }

        DeepLinkImportType.READ_BOOK ->
            runCatching { ReadBookShared.read(req.src, beforeNavigate) }
                .onFailure {
                    AppLog.put("阅读书籍 ${req.src} 出错", it)
                    Toasters.get().toast(it.message ?: "")
                }

        DeepLinkImportType.READ_CONFIG -> runCatching { SchemeImportOps.importReadConfig(req.src) }
            .onSuccess { Toasters.get().toast("导入排版成功: $it") }
            .onFailure { Toasters.get().toast(it.message ?: "导入排版失败") }

        DeepLinkImportType.UNKNOWN -> {
            // 先零网络按书源认领: 认领到的源直接带到抓书链, 不让下游再算一遍认领;
            // 不认领才下载内容嗅探类型 (排版配置 / 各类 JSON 一键导入)。
            val matchedSource = runCatching { matchBookSourceByUrl(req.src) }
                .onFailure { AppLog.put("按书源认领 ${req.src} 出错", it) }
                .getOrNull()
            if (matchedSource != null) {
                runCatching { AddToBookshelfShared.add(req.src, beforeNavigate, matchedSource) }
                    .onFailure {
                        AppLog.put("添加书籍 ${req.src} 出错", it)
                        Toasters.get().toast(it.message ?: "")
                    }
            } else runCatching { SchemeImportOps.determineType(req.src) }
                .onSuccess { result ->
                    when (result) {
                        is SchemeImportOps.DetermineResult.ReadConfig ->
                            Toasters.get().toast("导入排版成功: ${result.configName}")

                        is SchemeImportOps.DetermineResult.Json ->
                            LegadoDeepLinkHandler.handleResolved(
                                DeepLinkImportRequest(result.type, result.json)
                            )
                    }
                }
                // 与 ImportTargetDialog 的 Res.string.wrong_format 同一资源, 避免同语义双文案
                .onFailure { Toasters.get().toast(it.message ?: syncGetString("wrong_format")) }
        }

        else -> Unit
    }
}

/**
 * legado:// deep link 导入宿主 (Android/iOS/鸿蒙/desktop 共用, 挂载点见各端 UI 顶层;
 * 语义源头是 app 端 AssociationActivity + FileAssociationFragment.handleOnLineImport)。
 *
 * 链路: [LegadoDeepLinkHandler.pending] 非 null → 按类型分流:
 * - 直通四型 → [runSchemeImport] (加载中显示共享 [WaitDialog], 结果 toast);
 * - 勾选六型 → [DeepLinkImportTarget.of] 建 VM → [ImportTargetDialog] 下载解析勾选入库
 *   (与各 Screen 的手动网络导入同链) → 取消/完成均 [LegadoDeepLinkHandler.consume]。
 *
 * 冷启动投递先于 UI 组合也不丢: pending 是 StateFlow, 组合起来后立即收到当前值。
 *
 * 导入完成 toast 放在本层而非 [ImportTargetDialog]: 完成回调紧跟 onDismiss→consume,
 * pending 归 null 后内层已退出组合, 计数文案取不到 (rememberString 是 @Composable)。
 */
@Composable
fun DeepLinkImportHost() {
    var importedCount by remember { mutableStateOf<Int?>(null) }
    val request by LegadoDeepLinkHandler.pending.collectAsState()
    request?.let { req ->
        DeepLinkImportContent(req, onImported = { importedCount = it })
    }
    importedCount?.let { count ->
        val importCompleteText = stringResource(Res.string.import_complete, count)
        LaunchedEffect(count) {
            Toasters.get().toast(importCompleteText)
            importedCount = null
        }
    }
}

/**
 * 单条 deep link 请求的导入流程 (VM 生命周期与请求同寿, 请求变化时整块重建)。
 */
@Composable
private fun DeepLinkImportContent(
    req: DeepLinkImportRequest,
    onImported: (selectCount: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    if (req.type in schemeOnlyTypes) {
        // 直通四型: 全平台统一共享实现, 加载中显示共享 WaitDialog (sharedUiMain 全平台同款;
        // 原 Android handleDeepLinkImport 覆盖与其平台外壳已删除)
        var waitVisible by remember(req) { mutableStateOf(true) }
        WaitDialog(
            visible = waitVisible,
            onDismissRequest = { /* 操作不中断, 对齐原 Android 转圈对话框不可点外取消 */ },
        )
        LaunchedEffect(req) {
            // 收框必须早于导入流程发起的页面跳转 (addToBookshelf / read): 置隐后框要等之后某次
            // 重组才离开组合, 而承载它的透明壳一旦被转场盖住组合就停摆 —— 框会一直挂着, 整段
            // 转场都带着它。故置隐后等框确实离开界面, 再让 add/read 往下 push
            // (与原版 "关框 → 打开页面" 的顺序一致)
            runSchemeImport(req) {
                awaitWaitDialogDismissed { waitVisible = false }
            }
            // 不导航的两型 (readConfig / unknown) 在上一步里没动过框, 这里统一收掉
            awaitWaitDialogDismissed { waitVisible = false }
            // JSON 嗅探分支会把 pending 换成新请求, 此时不能再 consume (否则刚升级的请求被清掉)
            if (LegadoDeepLinkHandler.pending.value == req) {
                LegadoDeepLinkHandler.consume()
            }
        }
        return
    }

    // 勾选导入六型: 统一走 DeepLinkImportTarget + ImportTargetDialog
    // (与规则订阅 / 通用导入 Overlay 同一条链; loading/错误文案由 ImportTargetDialog 内部处理)
    val target = remember(req) { DeepLinkImportTarget.of(req.type, scope) }
    if (target == null) {
        val notSupportedText =
            stringResource(Res.string.deep_link_type_not_supported, req.type.name)
        LaunchedEffect(req) {
            Toasters.get().toast(notSupportedText)
            AppLog.put("legado:// 导入类型暂未支持: type=" + req.type + " src=" + req.src)
            LegadoDeepLinkHandler.consume()
        }
        return
    }
    LaunchedEffect(target) { target.startImport(req.src) }
    ImportTargetDialog(
        target = target,
        onDismiss = { LegadoDeepLinkHandler.consume() },
        onImported = onImported,
    )
}
