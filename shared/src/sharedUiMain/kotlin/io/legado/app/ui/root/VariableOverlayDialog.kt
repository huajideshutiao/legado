package io.legado.app.ui.root

// 源/书变量对话框 Overlay 统一宿主 (对照 SourceLoginOverlayDialog 的纯 Overlay 形态)。
//
// 非 Composable 上下文 (desktop/ios/ohos PlatformCapabilities + shared SourceUiEventBridgeHost)
// 经 AppNavigatorProviders.showOverlay 推 AppOverlay.Dialog("sourceVariable"/"bookVariable"),
// 由 LegadoApp DialogOverlayContent 按 key 分流到本文件两个 Overlay 入口, 内部渲染
// VariableDialog.kt 的 SourceVariableDialog/BookVariableDialog 本体 (交互不动)。
//
// 替代已删除的 VariableDialogHost 请求队列机制 (VariableDialogRequests + 三端 Compose 根挂载):
// payload 携带实体 (对照 ReviewListDialogHost 的 payload 编码/解码), 关闭走 overlay.dismiss。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.ui.widget.dialog.BookVariableDialog
import io.legado.app.ui.widget.dialog.SourceVariableDialog
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 书源变量 Overlay payload: 弹窗所需的完整输入 (书源实体)。
 * 对照原版 BaseSource.showSourceVariableDialog: 初始值 = getVariable() 原文,
 * 注释 = variableComment + 提示语, 确定后 setVariable 原样写回缓存 (不解析不校验)。
 */
@Serializable
data class SourceVariableOverlayPayload(val source: BookSource)

/** 编码 [SourceVariableOverlayPayload] 为 Overlay payload JSON (供平台能力/事件桥推 Overlay)。 */
fun encodeSourceVariableOverlayPayload(source: BookSource): String =
    KS_JSON.encodeToString(SourceVariableOverlayPayload(source))

/** 解码失败返回 null (调用方关闭弹窗, 对照 ReviewListDialogHost 的 payload 兜底)。 */
fun decodeSourceVariableOverlayPayload(json: String?): SourceVariableOverlayPayload? =
    runCatching { KS_JSON.decodeFromString<SourceVariableOverlayPayload>(json ?: return null) }
        .getOrNull()

/**
 * 书籍变量 Overlay payload: 弹窗所需的完整输入 (书籍 + 书源)。
 * 对照原版 BaseBook.showBookVariableDialog: 初始值 = getCustomVariable(),
 * 注释 = variableComment + 提示语, 确定后 putCustomVariable 只改 "custom" 键 (其他键保留)
 * 并重查书籍整行写库持久化。
 */
@Serializable
data class BookVariableOverlayPayload(
    val book: Book,
    val source: BookSource,
)

/** 编码 [BookVariableOverlayPayload] 为 Overlay payload JSON (供平台能力推 Overlay)。 */
fun encodeBookVariableOverlayPayload(book: Book, source: BookSource): String =
    KS_JSON.encodeToString(BookVariableOverlayPayload(book, source))

/** 解码失败返回 null (调用方关闭弹窗, 对照 ReviewListDialogHost 的 payload 兜底)。 */
fun decodeBookVariableOverlayPayload(json: String?): BookVariableOverlayPayload? =
    runCatching { KS_JSON.decodeFromString<BookVariableOverlayPayload>(json ?: return null) }
        .getOrNull()

/**
 * 书源变量 Overlay 分发入口 (LegadoApp DialogOverlayContent 按 key="sourceVariable" 分流)。
 * payload 缺失/解析失败直接关闭, 不落入空对话框。
 */
@Composable
internal fun SourceVariableOverlayDialogContent(
    overlay: AppOverlay.Dialog,
    navigator: AppNavigator
) {
    val source = remember(overlay.payload) {
        decodeSourceVariableOverlayPayload(overlay.payload)?.source
    }
    if (source == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    SourceVariableDialogContent(overlay, source)
}

/**
 * 书源变量对话框内容 (对照原版 BaseSource.showSourceVariableDialog):
 * 单个多行输入框编辑 source.getVariable() 的原始 JSON 文本 (原样填入, 不解析不校验, 空串也允许),
 * 确定后 setVariable 原样存缓存 (存储 key sourceVariable_{sourceKey}), 随后关闭 Overlay。
 */
@Composable
internal fun SourceVariableDialogContent(overlay: AppOverlay.Dialog, source: BookSource) {
    val navigator = LocalAppNavigator.current
    val suffix = "源变量可在js中通过source.getVariable()获取"
    val comment = source.variableComment.takeIf { !it.isNullOrBlank() }
        ?.let { "$it\n$suffix" } ?: suffix
    SourceVariableDialog(
        initialJson = source.getVariable(),
        comment = comment,
        onSave = { v ->
            source.setVariable(v)
            navigator.dismissOverlay(overlay.key)
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}

/**
 * 书籍变量 Overlay 分发入口 (LegadoApp DialogOverlayContent 按 key="bookVariable" 分流)。
 * payload 缺失/解析失败直接关闭, 不落入空对话框。
 */
@Composable
internal fun BookVariableOverlayDialogContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    val payload = remember(overlay.payload) { decodeBookVariableOverlayPayload(overlay.payload) }
    if (payload == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    BookVariableDialogContent(overlay, payload.book, payload.source)
}

/**
 * 书籍变量对话框内容 (对照原版 BaseBook.showBookVariableDialog):
 * 只编辑 book.variable 的 "custom" 键 (getCustomVariable/putCustomVariable 保留其他键, 空串也允许),
 * 确定后 putCustomVariable 写回 + 重查书籍整行持久化 (避免旧快照覆盖并发修改), 随后关闭 Overlay。
 *
 * 对照原版拿不到 BookSource 时直接 return (不弹窗): 推 Overlay 前平台能力已做同样守卫,
 * 此处为兜底。
 */
@Composable
internal fun BookVariableDialogContent(
    overlay: AppOverlay.Dialog,
    book: Book,
    source: BookSource?,
) {
    val navigator = LocalAppNavigator.current
    if (source == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }
    val scope = rememberCoroutineScope()
    val defaultComment = """书籍变量可在js中通过book.getVariable("custom")获取"""
    val comment = source.variableComment.takeIf { !it.isNullOrBlank() }
        ?.let { "$it\n$defaultComment" } ?: defaultComment
    BookVariableDialog(
        initialCustom = book.getCustomVariable(),
        comment = comment,
        onSave = { v ->
            scope.launch(IoDispatcher) {
                // 写库前重查, 避免用旧快照整行覆盖用户并发修改
                AppDbProviders.get().bookDao.getBook(book.bookUrl)?.let { latest ->
                    latest.putCustomVariable(v)
                    AppDbProviders.get().bookDao.update(latest)
                }
            }
            navigator.dismissOverlay(overlay.key)
        },
        onDismiss = { navigator.dismissOverlay(overlay.key) },
    )
}
