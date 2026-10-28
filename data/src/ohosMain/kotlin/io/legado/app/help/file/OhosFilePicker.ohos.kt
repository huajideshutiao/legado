package io.legado.app.help.file

import io.legado.app.constant.AppLog
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.KS_JSON
import kotlinx.coroutines.Job
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
// 业务侧 pickDocuments(fileSuffixes, allowsMultiple)
//   → OhosNativeBridge.invokeFilePickerSync("pickDocuments", {"fileSuffixes":[...],"allowsMultiple":...})
//   → [tsfn] ArkTS FilePickerBridgeHandler: DocumentViewPicker.select → uris
//   → [napi @CName] legado_file_picker_callback(requestId, {"ok":true,"uris":[...]})
//   → pickDocuments 解析 uris 返回 List<String>
// ```
//
// # 降级与失败
// [OhosNativeBridge.isFilePickerBridgeReady] 返回 false (napi 未接入) 时, 选择类接口返回 null,
// 调用方按取消降级。桥回传 ok=false 是真实失败 (权限/文件/参数/通道失效), 以
// [OhosFilePickerException] 抛出并带上错误码与原因, 由调用方如实告知用户 —— 不折成"用户取消"。
// 用户取消由 ArkTS 侧以空数组识别 (cancelled=true), 选择类接口同样返回 null。

/**
 * 文件选择桥回传的真实失败 (非用户取消)。
 *
 * [message] 带桥给出的错误码与原因 (如 `code=13900020 Invalid argument`), 供调用方直接展示。
 */
class OhosFilePickerException(
    message: String,
    /** 通道在请求运行期间失效: 消费已结算, 但文件 IO 终态由 ArkTS 清理。 */
    val channelClosed: Boolean = false,
) : Exception(message)

/** 把桥响应的失败明细拼成可读原因 (错误码 + 文本 + 逐条授权策略失败)。 */
private fun FilePickerResponse.failureMessage(action: String): String = buildString {
    append(action)
    append("失败")
    code?.takeIf { it != 0 }?.let { append(" (code=").append(it).append(')') }
    error?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    policyErrors.orEmpty().takeIf { it.isNotEmpty() }?.let { errors ->
        append(' ')
        append(errors.joinToString { "policyCode=${it.code} uri=${it.uri}" })
    }
}

/** 真实失败: 抛 [OhosFilePickerException] (携带 [action] 与桥给的原因)。 */
private fun FilePickerResponse.throwFailure(action: String): Nothing =
    throw OhosFilePickerException(failureMessage(action), channelClosed == true)

/**
 * 选择文档 (对照 iOS pickDocuments / desktop FileDialog)。
 *
 * @param fileSuffixes 文件扩展名过滤 (不含点, 如 "json"/"txt"), ArkTS 侧拼成 DocumentViewPicker 的 fileSuffixFilters; 空列表表示不限制
 * @param allowsMultiple 是否允许多选
 * @param parent 消费方协程的 Job: 它被撤销时等待立即结束 (选择结果不再交给任何人)
 * @return 选中文件 URI 列表, 用户取消或桥接未就绪返回 null
 * @throws OhosFilePickerException 真实失败 (权限/参数/通道失效等), 携带错误码与原因
 */
@OptIn(ExperimentalEncodingApi::class)
fun pickDocuments(
    fileSuffixes: List<String>,
    allowsMultiple: Boolean,
    parent: Job? = null,
): List<String>? {
    // 桥接未就绪: 降级返回 null (与 stub 行为一致, 调用方降级处理)
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(
        PickDocumentsPayload(fileSuffixes = fileSuffixes, allowsMultiple = allowsMultiple)
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDocuments", payload, parent)
        ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }
        .getOrElse { throw OhosFilePickerException("选择文件响应解析失败: $resultJson") }
    if (!resp.ok) resp.throwFailure("选择文件")
    // 用户取消: 返回 null (与 iOS documentPickerDidCancel → resume(null) 语义一致)
    if (resp.cancelled == true) return null
    return resp.uris.orEmpty()
}

/**
 * 读取文档内容 (对照 iOS pickDocumentContent / desktop File.readBytes)。
 *
 * @param uri 从 [pickDocuments] 返回的文件 URI
 * @param parent 消费方协程的 Job: 它被撤销时等待立即结束 (读取结果不再交给任何人)
 * @return 文件字节内容, 桥接未就绪返回 null
 * @throws OhosFilePickerException 真实失败 (文件不可读/已删除/通道失效等), 携带错误码与原因
 */
@OptIn(ExperimentalEncodingApi::class)
fun pickDocumentContent(uri: String, parent: Job? = null): ByteArray? {
    // 桥接未就绪: 降级返回 null
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(PickDocumentContentPayload(uri = uri))
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDocumentContent", payload, parent)
        ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }
        .getOrElse { throw OhosFilePickerException("读取文件响应解析失败: $resultJson") }
    if (!resp.ok) resp.throwFailure("读取文件")
    val data = resp.data
        ?: throw OhosFilePickerException("读取文件失败: 桥未回传文件内容")
    // 空文件: ArkTS 侧返回空字符串, 解码为 ByteArray(0)
    if (data.isEmpty()) return ByteArray(0)
    return runCatching { Base64.decode(data) }.getOrElse {
        throw OhosFilePickerException("读取文件失败: 文件内容解码失败")
    }
}

/**
 * 把选中的文档 URI 以 SDK FD 异步复制到 [destPath] (大文件不经过 base64/整份内存)。
 *
 * @return 复制成功 true; 桥接未就绪 false
 * @throws OhosFilePickerException 真实失败 (不可读/目标不可写/通道失效), 携带错误码
 */
fun copyDocumentTo(uri: String, destPath: String, parent: Job? = null): Boolean {
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return false
    val payload = KS_JSON.encodeToString(
        CopyDocumentPayload(
            uri = uri,
            destPath = destPath,
            destDir = destPath.substringBeforeLast('/'),
        )
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync(
        "copyDocumentTo", payload, parent, awaitOnCancel = true,
    )
        ?: return false
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }
        .getOrElse { throw OhosFilePickerException("保存导入副本响应解析失败: $resultJson") }
    if (resp.cancelled == true) throw kotlinx.coroutines.CancellationException("保存导入副本已取消")
    if (!resp.ok) resp.throwFailure("保存导入副本")
    return true
}

/** 消费侧已结算但复制目标仍归本请求: 通知 ETS 在真实 IO 终结后按该目录回收。 */
fun releasePickedDir(destDir: String, destPath: String) {
    OhosNativeBridge.releasePickedDir(destDir, destPath)
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
 * @param parent 消费方协程的 Job: 它被撤销时等待立即结束 (保存结果不再交给任何人)
 * @return 写入成功返回 true; 相册未授权/图库拒绝/桥回传失败返回 false (原因已记日志, 调用方报失败)
 * @throws IllegalStateException 桥未就绪 / 桥调用失败 / 消费方已撤销 (本端不变量被破坏)
 */
@OptIn(ExperimentalEncodingApi::class)
fun saveImageToAlbum(extension: String, bytes: ByteArray, parent: Job? = null): Boolean {
    check(OhosNativeBridge.isFilePickerBridgeReady()) {
        "鸿蒙文件保存桥未就绪: napi filePicker tsfn 未注册 (EntryAbility.registerFilePickerCallback 未执行)"
    }
    val payload = KS_JSON.encodeToString(
        SaveImageToAlbumPayload(extension = extension, data = Base64.encode(bytes))
    )
    val resultJson = OhosNativeBridge.invokeFilePickerSync("saveImageToAlbum", payload, parent)
        ?: error("鸿蒙相册保存桥调用失败: 桥未就绪、消费方已撤销或 tsfn 调用异常")
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }
        .getOrElse {
            AppLog.put("鸿蒙相册保存响应解析失败: $resultJson")
            return false
        }
    if (!resp.ok) {
        AppLog.put("鸿蒙相册保存失败: ${resp.failureMessage("保存图片到相册")}")
    }
    return resp.ok
}

/**
 * 选择目录 (对照 iOS pickDirectory / desktop FileDialogs.pickDirectory)。
 *
 * 2in1/Tablet (FolderSelection syscap): DocumentViewPicker 原生选目录, 选完立即
 * fileShare.persistPermission 持久化 (读写模式, 官方《授权持久化》: Picker 选文件或文件夹得到的是
 * "临时读写权限"), 持久化/路径转换失败即本次选择失败 (返回 null, 不放行临时可读的路径);
 * fileUri.FileUri(uri).path 官方转换出 POSIX 路径。
 * Phone (无 FolderSelection syscap): ArkTS 降级“选一个文件取父目录”返回原 URI
 * (path 为 null), 由调用方按原折回规则处理。
 *
 * @param parent 消费方协程的 Job: 它被撤销时等待立即结束 (选择结果不再交给任何人)
 * @return 选中目录 (uri + 转换路径); 用户取消/桥未就绪返回 null
 * @throws OhosFilePickerException 持久化/转换等真实失败, 携带错误码与原因
 */
fun pickDirectory(parent: Job? = null): OhosPickedDirectory? {
    // 桥接未就绪: 降级返回 null
    if (!OhosNativeBridge.isFilePickerBridgeReady()) return null

    val payload = KS_JSON.encodeToString(PickDirectoryPayload())
    val resultJson = OhosNativeBridge.invokeFilePickerSync("pickDirectory", payload, parent)
        ?: return null
    val resp = runCatching { KS_JSON.decodeFromString(FilePickerResponse.serializer(), resultJson) }
        .getOrElse { throw OhosFilePickerException("选择目录响应解析失败: $resultJson") }
    if (!resp.ok) resp.throwFailure("选择目录")
    // 用户取消: 返回 null
    if (resp.cancelled == true) return null
    // maxSelectNumber=1, 取首个 URI; Phone 分支无 paths (path=null)
    val uri = resp.uris?.firstOrNull()
        ?: throw OhosFilePickerException("选择目录失败: 桥未回传目录 URI")
    return OhosPickedDirectory(uri = uri, path = resp.paths?.firstOrNull())
}

/**
 * 激活已持久化的目录授权 (重启后按需调用)。
 *
 * ArkTS 侧调 fileShare.activatePermission (需 FolderAuthorization syscap +
 * ohos.permission.FILE_ACCESS_PERSIST, 与持久化同策略的读写模式); 设备无该能力时返回
 * code=801; 13900001 部分失败时 [ActivatePermissionsResult.activatedUris] 为成功子集,
 * [ActivatePermissionsResult.policyErrors] 为逐条失败明细 (含 policyCode)。
 *
 * @param uris 已持久化的目录授权 URI 列表 (上限 500)
 * @param parent 消费方协程的 Job: 它被撤销时等待立即结束
 */
fun activateDirectoryPermissions(uris: List<String>, parent: Job? = null): ActivatePermissionsResult {
    if (!OhosNativeBridge.isFilePickerBridgeReady()) {
        return ActivatePermissionsResult(ok = false, code = 0, message = "filePicker 桥未就绪")
    }
    val payload = KS_JSON.encodeToString(ActivatePermissionsPayload(uris = uris))
    val resultJson = OhosNativeBridge.invokeFilePickerSync("activateDirectoryPermissions", payload, parent)
        ?: return ActivatePermissionsResult(ok = false, code = 0, message = "桥未就绪、调用被拒或消费方已撤销")
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
    val fileSuffixes: List<String>,
    val allowsMultiple: Boolean,
)

/** pickDocumentContent 请求 payload (Kotlin → ArkTS)。 */
@Serializable
private data class PickDocumentContentPayload(
    val uri: String,
)

/** copyDocumentTo 请求 payload: 源 URI + 调用方独占目录内的目标路径。 */
@Serializable
private data class CopyDocumentPayload(
    val uri: String,
    val destPath: String,
    /** 本次请求独占目录: 通道失效时由 ArkTS 在 IO 真终结后一并清理。 */
    val destDir: String,
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
 * - pickDirectory 成功 (2in1/Tablet): [ok]=true, [uris]=[单个目录 URI], [paths]=[转换路径]
 * - pickDirectory 成功 (Phone): [ok]=true, [uris]=[父目录 URI] (无 paths)
 * - pickDirectory 持久化/转换失败: [ok]=false, [code]/[error]/[policyErrors]=失败明细
 * - pickDirectory 用户取消: [ok]=true, [cancelled]=true
 * - activateDirectoryPermissions 成功: [ok]=true, [uris]=[已激活的 uri]
 * - activateDirectoryPermissions 部分失败: [ok]=false, [code]=13900001,
 *   [policyErrors]=失败明细, [uris]=[成功子集]
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
    /** 通道在请求运行期间失效 (窗口销毁): 消费可结算, 但运行中的文件 IO 仍由 ArkTS 侧收尾。 */
    val channelClosed: Boolean? = null,
    // pickDirectory: 与 uris 一一对应的沙箱路径 (ArkTS fileUri.FileUri(uri).path 官方转换; Phone 分支不转换)
    val paths: List<String>? = null,
    // BusinessError.code (801/13900001 等)
    val code: Int? = null,
    // 13900001 部分失败明细 (PolicyErrorResult)
    val policyErrors: List<OhosPolicyError>? = null,
)
