package io.legado.app.utils

import io.legado.app.exception.NoStackTraceException
import io.legado.app.napi.OhosNativeBridge
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * [platformDecodeCjk]/[platformEncodeCjk] 鸿蒙 actual: @ohos.util TextDecoder/TextEncoder napi 桥。
 *
 * 鸿蒙无 NDK C 层字符集转换 API, GB18030/Big5 编解码走 ArkTS `@ohos.util.TextDecoder`
 * (decodeToString, 支持 gbk/gb18030/big5) / `@ohos.util.TextEncoder` (encodeInto),
 * 同 Crypto/Pasteboard 的 "tsfn 发请求 + @CName 回调返回结果" 同步等待模式:
 *
 * KMP → [OhosNativeBridge.invokeTextCodecSync] → ArkTS TextCodecBridgeHandler →
 * `legado.textCodecCallback(requestId, resultJson)` → 唤醒 KMP 侧 CompletableDeferred。
 *
 * 桥未注册/超时 → 返回 null, 由 nativeMain CjkCodec 抛"暂不支持请转码" (保持现有文案);
 * 桥就绪但 ArkTS 报错 → 抛明确异常 (区分"平台不支持"与"数据/桥执行错误")。
 *
 * 字节经 Base64 传输 (与 CryptoBridgeHandler 协议一致)。
 */
internal actual fun platformDecodeCjk(
    bytes: ByteArray, offset: Int, length: Int, charset: PlatformCjkCharset
): String? {
    if (length <= 0) return ""
    if (!OhosNativeBridge.isTextCodecBridgeReady()) return null
    val payload = KS_JSON.encodeToString(
        TextCodecDecodePayload.serializer(),
        TextCodecDecodePayload(
            charset = charset.transportName,
            data = Base64.encode(bytes, startIndex = offset, endIndex = offset + length),
        )
    )
    val result = OhosNativeBridge.invokeTextCodecSync("decode", payload) ?: return null
    val resp = parseResponse("decode", result)
    return resp.text ?: throw NoStackTraceException(
        "鸿蒙端 ${charset.transportName} 解码返回为空: $result"
    )
}

internal actual fun platformEncodeCjk(str: String, charset: PlatformCjkCharset): ByteArray? {
    if (str.isEmpty()) return ByteArray(0)
    if (!OhosNativeBridge.isTextCodecBridgeReady()) return null
    val payload = KS_JSON.encodeToString(
        TextCodecEncodePayload.serializer(),
        TextCodecEncodePayload(charset = charset.transportName, text = str)
    )
    val result = OhosNativeBridge.invokeTextCodecSync("encode", payload) ?: return null
    val resp = parseResponse("encode", result)
    val data = resp.data ?: throw NoStackTraceException(
        "鸿蒙端 ${charset.transportName} 编码返回为空: $result"
    )
    return Base64.decode(data)
}

/** 解析 ArkTS 响应; 解析失败/ok=false 抛明确异常 (桥已就绪, 属数据或桥执行错误)。 */
private fun parseResponse(action: String, resultJson: String): TextCodecResponse {
    val resp = runCatching {
        KS_JSON.decodeFromString(TextCodecResponse.serializer(), resultJson)
    }.getOrNull() ?: throw NoStackTraceException(
        "鸿蒙端 textCodec.$action 响应解析失败: $resultJson"
    )
    if (!resp.ok) {
        throw NoStackTraceException(
            "鸿蒙端 textCodec.$action 失败: ${resp.error ?: "unknown error"}"
        )
    }
    return resp
}

/** decode 请求 payload (data 为 Base64 字节)。 */
@Serializable
private data class TextCodecDecodePayload(
    val charset: String,
    val data: String,
)

/** encode 请求 payload。 */
@Serializable
private data class TextCodecEncodePayload(
    val charset: String,
    val text: String,
)

/** 响应: decode 成功 `{ok,text}`, encode 成功 `{ok,data}` (Base64), 失败 `{ok:false,error}`。 */
@Serializable
private data class TextCodecResponse(
    val ok: Boolean = false,
    val text: String? = null,
    val data: String? = null,
    val error: String? = null,
)
