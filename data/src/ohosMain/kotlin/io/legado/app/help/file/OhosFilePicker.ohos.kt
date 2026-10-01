package io.legado.app.help.file

import io.legado.app.constant.AppLog
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
 * 保存文档 (对照 iOS exportFile / app 端 HandleFileContract.EXPORT / desktop FileDialog SAVE)。
 *
 * ArkTS 侧 FilePickerBridgeHandler 调 DocumentViewPicker.save (弹系统保存器, [fileName] 为建议文件名)
 * 拿到目标 URI 后用 @ohos.file.fs 写入 [bytes] (base64 传输, 与 pickDocumentContent 反向同协议)。
 *
 * 与 pick 系列"降级返回 null"不同, 保存失败必须让调用方感知 (降级到剪贴板并提示),
 * 故桥未就绪/调用失败/ArkTS 报错时抛 [IllegalStateException] (带明确文案); 用户取消返回 false。
 *
 * @param fileName 建议文件名 (含扩展名, 如 "exportTxtTocRule.json", 与 app 端 FileData.fileName 一致)
 * @param bytes 待写出的字节内容
 * @return true=保存成功; false=用户取消
 * @throws IllegalStateException 桥未就绪 / 桥调用超时 / ArkTS 侧保存失败
 */
@OptIn(ExperimentalEncodingApi::class)
fun saveDocument(fileName: String, bytes: ByteArray): Boolean {
    check(OhosNativeBridge.isFilePickerBridgeReady()) {
        "鸿蒙文件保存桥未就绪: napi filePicker tsfn 未注册 (EntryAbility.registerFilePickerCallback 未执行)"
    }
    val payload = KS_JSON.encodeToString(
        SaveDocumentPayload(fileName = fileName, data = Base64.encode(bytes))
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync("saveDocument", payload)
        ?: error("鸿蒙文件保存桥调用失败: invokeFilePickerSync 超时或 tsfn 调用异常")
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
        ?: error("鸿蒙文件保存响应解析失败: $resultJson")
    if (!resp.ok) error("鸿蒙文件保存失败: ${resp.error ?: "未知错误"}")
    // 用户取消: 返回 false (与 iOS exportFile 取消语义一致, 调用方降级处理)
    if (resp.cancelled == true) return false
    return true
}

/**
 * 保存图片到系统相册 (对照 iOS UIImageWriteToSavedPhotosAlbum / Android SAF 落盘)。
 *
 * ArkTS 侧 FilePickerBridgeHandler 申请 ohos.permission.WRITE_IMAGEVIDEO 后调
 * photoAccessHelper.createAsset 建图库资源, 再用 @ohos.file.fs 写入 [bytes]
 * (photoAccessHelper 仅 ArkTS 可用, 故经桥)。
 *
 * @param extension 图片扩展名 (不含点, 如 "jpg", 由调用方按字节魔数判定)
 * @param bytes 图片字节
 * @return 是否写入成功 (未授予相册权限 / 写入失败均为 false, 属设备环境条件, 调用方 toast)
 * @throws IllegalStateException 桥未就绪 / 桥调用超时 (本端不变量被破坏)
 */
@OptIn(ExperimentalEncodingApi::class)
fun saveImageToAlbum(extension: String, bytes: ByteArray): Boolean {
    check(OhosNativeBridge.isFilePickerBridgeReady()) {
        "鸿蒙文件保存桥未就绪: napi filePicker tsfn 未注册 (EntryAbility.registerFilePickerCallback 未执行)"
    }
    val payload = KS_JSON.encodeToString(
        SaveImageToAlbumPayload(extension = extension, data = Base64.encode(bytes))
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync("saveImageToAlbum", payload)
        ?: error("鸿蒙相册保存桥调用失败: invokeFilePickerSync 超时或 tsfn 调用异常")
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
        ?: error("鸿蒙相册保存响应解析失败: $resultJson")
    return resp.ok
}

/**
 * 选择目录 (对照 iOS pickDirectory / desktop FileDialogs.pickDirectory)。
 *
 * 2in1/Tablet (FolderSelection syscap): DocumentViewPicker 原生选目录, 选完立即
 * fileShare.persistPermission 持久化 (select 只授临时只读权限, 仅持久化 READ_MODE),
 * 持久化/路径转换失败即本次选择失败 (返回 null, 不放行临时可读的路径);
 * fileUri.FileUri(uri).path 官方转换出 POSIX 路径。
 * Phone (无 FolderSelection syscap): ArkTS 降级“选一个文件取父目录”返回原 URI
 * (path 为 null), 由调用方按原折回规则处理。
 *
 * @return 选中目录 (uri + 转换路径); 用户取消/桥未就绪/持久化或转换失败返回 null
 */
fun pickDirectory(): OhosPickedDirectory? {
    // 桥接未就绪: 降级返回 null
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(PickDirectoryPayload())
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDirectory", payload) ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
        ?: return null
    if (!resp.ok) {
        val detail = resp.policyErrors.orEmpty()
            .joinToString { "policyCode=${it.code} uri=${it.uri}" }
        AppLog.put("选择目录失败 errCode=${resp.code ?: 0} ${resp.error ?: ""} $detail")
        return null
    }
    // 用户取消: 返回 null
    if (resp.cancelled == true) return null
    // maxSelectNumber=1, 取首个 URI; Phone 分支无 paths (path=null)
    val uri = resp.uris?.firstOrNull() ?: return null
    return OhosPickedDirectory(uri = uri, path = resp.paths?.firstOrNull())
}

/**
 * 激活已持久化的目录授权 (重启后按需调用)。
 *
 * ArkTS 侧调 fileShare.activatePermission (需 FolderAuthorization syscap +
 * ohos.permission.FILE_ACCESS_PERSIST, READ_MODE 与持久化同策略); 设备无该能力时返回
 * code=801; 13900001 部分失败时 [ActivatePermissionsResult.activatedUris] 为成功子集,
 * [ActivatePermissionsResult.policyErrors] 为逐条失败明细 (含 policyCode)。
 *
 * @param uris 已持久化的目录授权 URI 列表 (上限 500)
 */
fun activateDirectoryPermissions(uris: List<String>): ActivatePermissionsResult {
    if (!OhosNativeBridge.isFilePickerBridgeReady()) {
        return ActivatePermissionsResult(ok = false, code = 0, message = "filePicker 桥未就绪")
    }
    val payload = KS_JSON.encodeToString(ActivatePermissionsPayload(uris = uris))
    val resultJson = OhosNativeBridge.invokeFilePickerSync("activateDirectoryPermissions", payload)
        ?: return ActivatePermissionsResult(ok = false, code = 0, message = "桥调用超时或 tsfn 调用异常")
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }.getOrNull()
        ?: return ActivatePermissionsResult(ok = false, code = 0, message = "响应解析失败: $resultJson")
    if (!resp.ok) {
        return ActivatePermissionsResult(
            ok = false,
            code = resp.code ?: 0,
            message = resp.error,
            policyErrors = resp.policyErrors.orEmpty(),
            activatedUris = resp.uris.orEmpty(),
        )
    }
    return ActivatePermissionsResult(ok = true, activatedUris = resp.uris.orEmpty())
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

/** saveDocument 请求 payload (Kotlin → ArkTS, data 为 base64 编码文件内容)。 */
@Serializable
private data class SaveDocumentPayload(
    val fileName: String,
    val data: String,
)

/** saveImageToAlbum 请求 payload (Kotlin → ArkTS, data 为 base64 编码图片字节)。 */
@Serializable
private data class SaveImageToAlbumPayload(
    val extension: String,
    val data: String,
)

/** pickDirectory 请求 payload (Kotlin → ArkTS, 无参数)。 */
@Serializable
private class PickDirectoryPayload

/** activateDirectoryPermissions 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class ActivatePermissionsPayload(
    val uris: List<String>,
)

/** 选中的目录 (pickDirectory 返回)。 */
data class OhosPickedDirectory(
    /** 目录授权 URI (file://docs/...)。 */
    val uri: String,
    /** fileUri.FileUri 官方转换出的 POSIX 路径; Phone 降级分支未转换, 为 null。 */
    val path: String?,
)

/** fileShare.PolicyErrorResult (授权策略失败的 URI 明细)。 */
@Serializable
data class OhosPolicyError(
    val uri: String,
    /** PolicyErrorCode: 1=PERSISTENCE_FORBIDDEN 2=INVALID_MODE 3=INVALID_PATH 4=PERMISSION_NOT_PERSISTED。 */
    val code: Int,
    val message: String?,
)

/** activateDirectoryPermissions 结果。 */
data class ActivatePermissionsResult(
    /** 全部成功 (13900001 部分失败时为 false, 成功子集在 [activatedUris])。 */
    val ok: Boolean,
    /** BusinessError.code (801=设备无 FolderAuthorization 能力, 13900001=部分失败)。 */
    val code: Int = 0,
    val message: String? = null,
    /** 逐条失败明细 (13900001 时非空)。 */
    val policyErrors: List<OhosPolicyError> = emptyList(),
    /** 成功激活的 URI 子集 (ok=true 时等于请求全集)。 */
    val activatedUris: List<String> = emptyList(),
)

/**
 * FilePicker 响应 (ArkTS → Kotlin)。
 *
 * - pickDocuments 成功: [ok]=true, [uris]=选中 URI 列表
 * - pickDocuments 用户取消: [ok]=true, [cancelled]=true
 * - pickDocumentContent 成功: [ok]=true, [data]=base64 编码字节
 * - pickImages 成功: [ok]=true, [uris]=选中图片 URI 列表
 * - pickImages 用户取消: [ok]=true, [cancelled]=true
 * - pickDirectory 成功 (2in1/Tablet): [ok]=true, [uris]=[单个目录 URI], [paths]=[转换路径]
 * - pickDirectory 成功 (Phone): [ok]=true, [uris]=[父目录 URI] (无 paths)
 * - pickDirectory 持久化/转换失败: [ok]=false, [code]/[error]/[policyErrors]=失败明细
 * - pickDirectory 用户取消: [ok]=true, [cancelled]=true
 * - activateDirectoryPermissions 成功: [ok]=true, [uris]=[已激活的 uri]
 * - activateDirectoryPermissions 部分失败: [ok]=false, [code]=13900001,
 *   [policyErrors]=失败明细, [uris]=[成功子集]
 * - saveDocument 成功: [ok]=true, [uris]=[目标文件 URI]
 * - saveDocument 用户取消: [ok]=true, [cancelled]=true
 * - saveImageToAlbum 成功: [ok]=true
 * - 失败: [ok]=false, [error]=错误信息
 */
@Serializable
private data class FilePickerResponse(
    val ok: Boolean,
    val uris: List<String>? = null,
    val cancelled: Boolean? = null,
    val data: String? = null,
    val error: String? = null,
    // pickDirectory: 与 uris 一一对应的沙箱路径 (ArkTS fileUri.FileUri(uri).path 官方转换; Phone 分支不转换)
    val paths: List<String>? = null,
    // BusinessError.code (801/13900001 等)
    val code: Int? = null,
    // 13900001 部分失败明细 (PolicyErrorResult)
    val policyErrors: List<OhosPolicyError>? = null,
)
