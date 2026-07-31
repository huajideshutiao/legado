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
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.deep_link_type_not_supported
import legado.shared.generated.resources.import_complete
import legado.shared.generated.resources.wrong_format
import org.jetbrains.compose.resources.stringResource

/**
 * legado:// deep link 导入宿主 (iOS/鸿蒙共用, 对照 desktop `DesktopDeepLinkImportHost`,
 * 语义源头是 app 端 AssociationActivity + FileAssociationFragment.handleOnLineImport)。
 *
 * 挂载点与 [io.legado.app.ui.book.source.SourceUiEventBridgeHost] 平级:
 * - iOS: [io.legado.app.MainViewController] 尾部 (投递侧 `handleLegadoDeepLink` ← onOpenURL)
 * - 鸿蒙: [io.legado.app.MainOhos] 尾部 (投递侧 EntryAbility onCreate/onNewWant → napi)
 *
 * 链路: [LegadoDeepLinkHandler.pending] 非 null → [DeepLinkImportTarget.of] 建 VM →
 * startImport(src) 下载解析比对 → successState>0 弹 [ImportItemsDialog] 勾选入库
 * (与各 Screen 的手动网络导入完全同链) → 取消/完成均 [LegadoDeepLinkHandler.consume]。
 *
 * 冷启动投递先于 UI 组合也不丢: pending 是 StateFlow, 组合起来后立即收到当前值。
 *
 * 导入完成 toast 放在本层而非 [DeepLinkImportContent]: 完成回调紧跟 onDismiss→consume,
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

/** 单条 deep link 请求的导入流程 (VM 生命周期与请求同寿, 请求变化时整块重建)。 */
@Composable
private fun DeepLinkImportContent(
    req: DeepLinkImportRequest,
    onImported: (selectCount: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 每条请求新建 VM (避免 success/error state 残留, 与各 Screen 网络导入一致)
    val target = remember(req) { DeepLinkImportTarget.of(req.type, scope) }

    if (target == null) {
        // ADD_TO_BOOKSHELF / READ_CONFIG / UNKNOWN: 依赖未下沉 (详见 DeepLinkImportTarget KDoc)
        val notSupportedText =
            stringResource(Res.string.deep_link_type_not_supported, req.type.name)
        LaunchedEffect(req) {
            Toasters.get().toast(notSupportedText)
            AppLog.put("legado:// 导入类型暂未支持: type=${req.type} src=${req.src}")
            LegadoDeepLinkHandler.consume()
        }
        return
    }

    var showDialog by remember(req) { mutableStateOf(false) }
    val wrongFormatText = stringResource(Res.string.wrong_format)

    // 触发下载 → 解析 → 与本地库比对; 解析成功弹勾选对话框, 无结果/出错则 toast 后消费
    LaunchedEffect(target) {
        launch {
            target.successState.collectLatest { count ->
                if (count > 0) {
                    showDialog = true
                } else {
                    Toasters.get().toast(wrongFormatText)
                    LegadoDeepLinkHandler.consume()
                }
            }
        }
        launch {
            target.errorState.collectLatest { err ->
                Toasters.get().toast(err.substringAfter("ImportError:"))
                LegadoDeepLinkHandler.consume()
            }
        }
        target.startImport(req.src)
    }

    if (!showDialog) return
    val bookSourceVm = target.bookSourceVm
    if (bookSourceVm != null) {
        // 书源另走带"选中新增源/选中更新源"菜单的封装 (与 LegadoApp 内书源网络导入同一入口)
        ImportBookSourceItemsDialog(
            vm = bookSourceVm,
            onDismiss = { LegadoDeepLinkHandler.consume() },
            onImported = onImported,
        )
    } else {
        ImportItemsDialog(
            title = rememberString(target.titleKey),
            vm = target.items,
            onDismiss = { LegadoDeepLinkHandler.consume() },
            onImported = onImported,
        )
    }
}
