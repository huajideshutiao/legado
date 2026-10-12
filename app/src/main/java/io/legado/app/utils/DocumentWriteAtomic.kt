package io.legado.app.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog

/**
 * 整份写入用户选定的 SAF 目标 (content://), 返回是否写入成功。
 *
 * SAF 没有"整份覆盖文档"的原语: [ContentResolver.openOutputStream] 一打开目标就按提供方的实现
 * 在目标上写 (官方文档明示 "w" 是否先截断由各提供方决定), 写到一半被打断就把目标文件变成半截或
 * 新旧混合。能力范围内最接近整份落盘的做法是同目录就位 —— 先建临时文档写完整内容, 再用
 * [DocumentsContract.renameDocument] 改名顶替目标名。
 *
 * 两个入口被打断时的后果不同: 目录入口按目标名建/找文档, 同名重存写的就是用户原有文件; 单文档
 * 入口拿到的文件是系统选择器新建的 (官方文档明示 ACTION_CREATE_DOCUMENT 不能覆盖已存在文件,
 * 同名时系统给新文件加编号), 中断留下的是半截新文件。
 *
 * 就位依赖提供方三项能力, 任一不成立都回落直写 (行为与不做就位处理时相同):
 * 1. 允许在目标目录下建文档: ACTION_CREATE_DOCUMENT 选出的单文档只带该文档自身的读写授权,
 *    建同级文档需要父目录的写权限;
 * 2. 支持改名: [DocumentsContract.renameDocument] 只承诺"改已有文档的显示名, 失败返回 null";
 * 3. 同名目标可以顶替: 目标名已存在时是否允许改名顶替由提供方决定 (先建同名再顶替不在契约里),
 *    不允许时只能回落直写。
 *
 * 临时文档名用整份写入的共享契约 ([ATOMIC_WRITE_TEMP_SUFFIX]), 它在每条退出路径上都会被删掉
 * (改名成功即被目标名取代, 失败就地删除); 只有进程被强杀会残留 —— 用户选定目录没有孤儿件清点方
 * (见 [ATOMIC_WRITE_TEMP_SUFFIX] 的回收边界说明)。
 *
 * @param target 目录 (OpenDocumentTree 选出的树 URI) 或单文档 (ACTION_CREATE_DOCUMENT 的结果)
 * @param fileName [target] 是目录时的目标文件名; 是单文档时仅在取不到显示名时用作回退
 */
fun writeDocumentBytesAtomically(
    context: Context,
    target: Uri,
    fileName: String,
    data: ByteArray,
): Boolean {
    val resolver = context.contentResolver
    val isDir = DocumentsContract.isTreeUri(target)
    // 单文档的就位落点是它所在目录, 目标名取用户最终选定的显示名 (系统选择器里可以改名)
    val parent = if (isDir) target else parentDocumentUri(target)
    val name = if (isDir) fileName else documentDisplayName(context, target) ?: fileName
    return when (placeDocumentBytesAtomically(resolver, parent, name, data)) {
        PlacementResult.PLACED -> true
        PlacementResult.FAILED -> false
        // 就位不成立: 直写 (目录 = 建/找目标文件后写, 单文档 = 写该文档)
        PlacementResult.UNSUPPORTED ->
            if (isDir) target.writeBytes(context, fileName, data) else target.writeBytes(context, data)
    }
}

/** 同目录整份就位的结果。 */
private enum class PlacementResult {
    /** 已改名就位, 目标名上是完整内容。 */
    PLACED,

    /** 提供方或授权不支持就位 (未建出临时文档, 或改名被拒), 调用方回落直写。 */
    UNSUPPORTED,

    /** 临时文档写入失败: 目标未被触碰, 调用方只能报失败 (不能改直写, 那会截断目标)。 */
    FAILED,
}

/**
 * 建临时文档 → 写整份 → 改名顶替目标名; 临时文档在每条退出路径上都被删掉。
 *
 * @param parentUri 目标所在目录的文档 URI (树 URI 或目录文档 URI); null 表示取不出目录, 只能直写
 */
private fun placeDocumentBytesAtomically(
    resolver: ContentResolver,
    parentUri: Uri?,
    fileName: String,
    data: ByteArray,
): PlacementResult {
    parentUri ?: return PlacementResult.UNSUPPORTED
    val tempName = "$fileName.${randomUUIDString()}$ATOMIC_WRITE_TEMP_SUFFIX"
    val tempUri = runCatching {
        DocumentsContract.createDocument(
            resolver,
            parentUri,
            FileUtils.getMimeType(fileName),
            tempName,
        )
    }.onFailure {
        // 拿不到目录写权限 (单文档授权的常规情况) 或提供方不允许建文档: 属已知能力边界, 按调试级记
        AppLog.putDebug("SAF 无法在同目录建临时文档, 目标直写: $fileName\n${it.localizedMessage}")
    }.getOrNull() ?: return PlacementResult.UNSUPPORTED

    val written = runCatching {
        resolver.openOutputStream(tempUri)?.use { it.write(data) } != null
    }.getOrDefault(false)
    if (!written) {
        runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
        AppLog.put("SAF 临时文档写入失败, 目标保持原内容: $fileName")
        return PlacementResult.FAILED
    }

    val renamed = runCatching {
        DocumentsContract.renameDocument(resolver, tempUri, fileName)
    }.getOrNull()
    if (renamed != null) return PlacementResult.PLACED

    runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
    AppLog.put("SAF 提供方不支持改名或同名目标不允许顶替, 目标直写: $fileName")
    return PlacementResult.UNSUPPORTED
}

/** 单文档 URI 的父目录 URI: 文档 id 形如 "primary:Download/1.png" 时取目录那一段, 取不出返回 null。 */
private fun parentDocumentUri(documentUri: Uri): Uri? {
    val authority = documentUri.authority ?: return null
    val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
        ?: return null
    val parentId = documentId.substringBeforeLast('/', "")
    if (parentId.isEmpty()) return null
    return DocumentsContract.buildDocumentUri(authority, parentId)
}

/** 单文档当前显示名 (用户在系统选择器里可能改过名); 取不到返回 null。 */
private fun documentDisplayName(context: Context, documentUri: Uri): String? =
    runCatching { DocumentFile.fromSingleUri(context, documentUri)?.name }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
