package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.canonicalizeUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

// ohos 平台 openURL / 剪贴板 / 文件选择 工具 (对照 iosMain IosPlatformOps.ios.kt)
//
// 剪贴板走 @ohos.pasteboard.getSystemPasteboard(), 仅有 ArkTS API,
// 经 [OhosNativeBridge.invokePasteboardSync] napi 桥同步调用 (与 OhosFilePicker 同模式);
// 桥未就绪时记一条降级日志 / 返回 null。

/**
 * 用系统浏览器打开外部链接 (对照 iOS openURL / desktop browseUrl)。
 *
 * url 先经 [canonicalizeUri] 做 RFC 3986 逐段合法化: Want.uri 由系统按 URI 解析,
 * 含空格/非 ASCII/裸 `%` 的串会被判为非法 URI 而 startAbility 失败 (用户看到「点了没反应」),
 * 与 iOS `NSURL.URLWithString` 对非法串返 nil 同一类问题。
 */
fun openURL(url: String) {
    OpenUrlProviders.get().openUrl(url.canonicalizeUri())
}

/** 复制文本到系统剪贴板 (对照 iOS copyToClipboard / desktop Toolkit.systemClipboard)。 */
fun copyToClipboard(text: String) {
    if (!OhosNativeBridge.isPasteboardBridgeReady()) {
        AppLog.putDebug("剪贴板桥未就绪, 丢弃复制: ${text.take(64)}", tag = "ohos-clipboard")
        return
    }
    val payload = KS_JSON.encodeToString(PasteboardWritePayload(text = text))
    OhosNativeBridge.invokePasteboardSync("write", payload)
}

/** 读取系统剪贴板文本 (对照 iOS readFromClipboard / desktop Toolkit.systemClipboard)。 */
fun readFromClipboard(): String? {
    if (!OhosNativeBridge.isPasteboardBridgeReady()) return null
    val resultJson = OhosNativeBridge.invokePasteboardSync("read", "{}") ?: return null
    val resp = runCatching {
        KS_JSON.decodeFromString(PasteboardResponse.serializer(), resultJson)
    }.getOrNull()
    if (resp == null || !resp.ok) return null
    return resp.text?.takeIf { it.isNotEmpty() }
}

// ===== 跨语言 payload / response (与 ArkTS PasteboardBridgeHandler.ets JSON 协议对齐) =====

/** write 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class PasteboardWritePayload(
    val text: String,
)

/**
 * Pasteboard 响应 (ArkTS → Kotlin)。
 *
 * - read 成功: [ok]=true, [text]=剪贴板纯文本 (无内容为空串)
 * - write 成功: [ok]=true
 * - 失败: [ok]=false, [error]=错误信息
 */
@Serializable
private data class PasteboardResponse(
    val ok: Boolean,
    val text: String? = null,
    val error: String? = null,
)
