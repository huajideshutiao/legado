package io.legado.app.help.file

import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ohos 平台文件选择工具 (对照 iosMain IosFilePicker.ios.kt)
//
// # 实现 (KP8+: 基于 @ohos.file.picker / @ohos.file.fs 的真实实现)
//
// 鸿蒙 @ohos.file.picker.DocumentViewPicker / @ohos.file.fs 仅提供 ArkTS API, Kotlin/Native 无法
// 直接调用, 通过 [OhosNativeBridge.invokeFilePickerSync] napi 桥接 (tsfn 发请求 + @CName 回调返回结果)
// 实现真实文件选择与读取 (与 OhosImageOps / KmpHttpTypes 同模式)。
//
// # 调用链 (以 pickDocuments 为例)
// ```
// 业务侧 pickDocuments(contentTypes, allowsMultiple)
//   → OhosNativeBridge.invokeFilePickerSync("pickDocuments", {"contentTypes":[...],"allowsMultiple":...})
//   → [tsfn] ArkTS FilePickerBridgeHandler: DocumentViewPicker.select → uris
//   → [napi @CName] legado_file_picker_callback(requestId, {"ok":true,"uris":[...]})
//   → pickDocuments 解析 uris 返回 List<String>
// ```
//
// # 降级策略 (桥接未就绪时, 与 stub 行为一致)
// [OhosNativeBridge.isFilePickerBridgeReady] 返回 false 或 invokeFilePickerSync 返回 null /
// 响应 ok=false / 用户取消 (cancelled=true) 时, pickDocuments/pickDocumentContent 返回 null,
// 让调用方 (ImportBookSourceViewModelShared 等) 降级处理 (与 iOS 取消返回 null 语义一致)。

/**
 * 选择文档 (对照 iOS pickDocuments / desktop FileDialog)。
 *
 * @param contentTypes 文件类型过滤 (iOS UTI 风格, 如 "public.json"/"public.text",
 *   ArkTS 侧 FilePickerBridgeHandler 映射到 DocumentViewPicker fileMimeTypeFilters)
 * @param allowsMultiple 是否允许多选
 * @return 选中文件 URI 列表, 用户取消或桥接未就绪返回 null
 */
@OptIn(ExperimentalEncodingApi::class)
fun pickDocuments(
    contentTypes: List<String>,
    allowsMultiple: Boolean,
): List<String>? {
    // 桥接未就绪: 降级返回 null (与 stub 行为一致, 调用方降级处理)
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(
        PickDocumentsPayload(contentTypes = contentTypes, allowsMultiple = allowsMultiple)
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDocuments", payload) ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
    if (resp == null || !resp.ok) return null
    // 用户取消: 返回 null (与 iOS documentPickerDidCancel → resume(null) 语义一致)
    if (resp.cancelled == true) return null
    return resp.uris
}

/**
 * 读取文档内容 (对照 iOS pickDocumentContent / desktop File.readBytes)。
 *
 * @param uri 从 [pickDocuments] 返回的文件 URI
 * @return 文件字节内容, 读取失败或桥接未就绪返回 null
 */
@OptIn(ExperimentalEncodingApi::class)
fun pickDocumentContent(uri: String): ByteArray? {
    // 桥接未就绪: 降级返回 null
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(PickDocumentContentPayload(uri = uri))
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDocumentContent", payload) ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
    if (resp == null || !resp.ok) return null
    val data = resp.data ?: return null
    // 空文件: ArkTS 侧返回空字符串, 解码为 ByteArray(0)
    if (data.isEmpty()) return ByteArray(0)
    return runCatching { Base64.decode(data) }.getOrNull()
}

/**
 * 选择图片 (对照 iOS pickImages / desktop 图片选择)。
 *
 * @param contentTypes 图片类型过滤 (iOS UTI 风格, 如 "public.image"/"public.jpeg",
 *   ArkTS 侧 FilePickerBridgeHandler 映射到 PhotoViewPicker PhotoSelectOptions.MIMETypeFilter)
 * @param allowsMultiple 是否允许多选
 * @return 选中图片 URI 列表, 用户取消或桥接未就绪返回 null
 */
fun pickImages(
    contentTypes: List<String>,
    allowsMultiple: Boolean,
): List<String>? {
    // 桥接未就绪: 降级返回 null (与 stub 行为一致, 调用方降级处理)
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(
        PickImagesPayload(contentTypes = contentTypes, allowsMultiple = allowsMultiple)
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickImages", payload) ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
    if (resp == null || !resp.ok) return null
    // 用户取消: 返回 null (与 iOS documentPickerDidCancel → resume(null) 语义一致)
    if (resp.cancelled == true) return null
    return resp.uris
}

/**
 * 选择目录 (对照 iOS pickDirectory / desktop FileDialogs.pickDirectory)。
 *
 * 鸿蒙端用 DocumentViewPicker 选目录 (maxSelectNumber=1), 返回目录 URI;
 * 调用方需通过 @ohos.file.fs 或 security-scoped 访问 URI 对应目录。
 *
 * @return 选中目录 URI, 用户取消或桥接未就绪返回 null
 */
fun pickDirectory(): String? {
    // 桥接未就绪: 降级返回 null
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(PickDirectoryPayload())
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDirectory", payload) ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
    if (resp == null || !resp.ok) return null
    // 用户取消: 返回 null
    if (resp.cancelled == true) return null
    // maxSelectNumber=1, 取首个 URI 作为目录 URI
    return resp.uris?.firstOrNull()
}

// ===== 跨语言 payload / response (与 ArkTS FilePickerBridgeHandler.ets JSON 协议对齐) =====

/** pickDocuments 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class PickDocumentsPayload(
    val contentTypes: List<String>,
    val allowsMultiple: Boolean,
)

/** pickDocumentContent 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class PickDocumentContentPayload(
    val uri: String,
)

/** pickImages 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class PickImagesPayload(
    val contentTypes: List<String>,
    val allowsMultiple: Boolean,
)

/** pickDirectory 请求 payload (Kotlin → ArkTS, 无参数)。 */
@Serializable
private data class PickDirectoryPayload

/**
 * FilePicker 响应 (ArkTS → Kotlin)。
 *
 * - pickDocuments 成功: [ok]=true, [uris]=选中 URI 列表
 * - pickDocuments 用户取消: [ok]=true, [cancelled]=true
 * - pickDocumentContent 成功: [ok]=true, [data]=base64 编码字节
 * - pickImages 成功: [ok]=true, [uris]=选中图片 URI 列表
 * - pickImages 用户取消: [ok]=true, [cancelled]=true
 * - pickDirectory 成功: [ok]=true, [uris]=[单个目录 URI]
 * - pickDirectory 用户取消: [ok]=true, [cancelled]=true
 * - 失败: [ok]=false, [error]=错误信息
 */
@Serializable
private data class FilePickerResponse(
    val ok: Boolean,
    val uris: List<String>? = null,
    val cancelled: Boolean? = null,
    val data: String? = null,
    val error: String? = null,
)
