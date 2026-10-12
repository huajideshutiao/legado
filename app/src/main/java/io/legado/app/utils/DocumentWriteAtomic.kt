package io.legado.app.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.legado.app.constant.AppLog
import java.io.InputStream
import java.io.OutputStream

/**
 * 整份写入用户选定的 SAF 目录 (content:// 树 URI), 返回是否写入成功。
 *
 * SAF 没有"整份覆盖文档"的原语: [ContentResolver.openOutputStream] 一打开目标就按提供方的实现
 * 在目标上写 (官方文档明示 "w" 是否先截断由各提供方决定), 写到一半被打断就把目标文件变成半截或
 * 新旧混合。能力范围内最接近整份落盘的做法是同目录就位 —— 先建临时文档写完整内容, 再把临时文档
 * 改名成目标名。
 *
 * 改名不能顶替已有名字: 平台文件提供方 (外置存储的 ExternalStorageProvider 继承
 * com.android.internal.content.FileSystemProvider) 的改名走 `FileUtils.buildUniqueFile` 按名取
 * 唯一名 (官方源码: `file = buildFile(parent, name + " (" + n + ")", ext)`), 目标名被占时落到别名
 * 而不是顶替。故目标名上已有旧文档时, 先把旧文档改名成 [ATOMIC_WRITE_TEMP_SUFFIX] 结尾的过渡名
 * 把目标名空出来, 新内容就位后再删过渡件; 任一步失败都把过渡件改回目标名 —— 就位分支成立时目标
 * 名上是旧的完整内容或新的完整内容, 不会旧件已删而新件没写成 (改名换代的瞬间目标名短暂空着,
 * 失败时旧件改回目标名)。
 *
 * 临时文档与过渡件的名字都按共享契约生成 ([ATOMIC_WRITE_TEMP_SUFFIX]), 残留件按名可识别, 目录
 * 清点方 ([isAtomicWriteTempName]) 能与正常条目区分: 临时文档建出后名字没落在契约名上时先改名回
 * 去 (提供方建文档会按 MIME 给名字补扩展名), 改名也不落在契约名上就删掉并按不支持就位处理; 就位
 * 与搬运都没成的内容也改回契约名, 改不回来就删掉, 不给用户目录留认不出来的中间件。
 *
 * 就位只在提供方支持同目录建文档与改名时成立, 支持不到时按直写回落, 与不做就位处理同一落点
 * (写入被打断时目标名上可能是半截): 内容还没被读出的分支直写目标名, 内容已整份写进临时文档的
 * 分支把那份内容搬进目标名。
 *
 * 单文档目标 (ACTION_CREATE_DOCUMENT 的结果, [DocumentsContract.isTreeUri] 为 false) 只直写:
 * 它的名字被它自己占着, 同目录就位改不掉它的名字; 且它的父目录通常没有写授权 (官方文档:
 * ACTION_CREATE_DOCUMENT 选出的文件只带该文件自身的读写授权, 建同级文档需要父目录的写权限)。
 *
 * 用户选定目录没有孤儿件清点方 (见 [ATOMIC_WRITE_TEMP_SUFFIX] 的回收边界说明), 每条退出路径都
 * 自行清掉中间件, 只有进程被强杀会残留契约名可识别的临时件/过渡件。
 *
 * @param target 目录 (OpenDocumentTree 选出的树 URI, 或它下面某个目录的文档 URI)
 * @param fileName 目标文件名
 */
fun writeDocumentBytesAtomically(
    context: Context,
    target: Uri,
    fileName: String,
    data: ByteArray,
): Boolean = writeDocumentAtomically(context, target, fileName) { it.write(data) }

/**
 * 流式整份写入用户选定的 SAF 目录 (content:// 树 URI), 返回是否写入成功。
 *
 * 与 [writeDocumentBytesAtomically] 同一落盘机制 (同目录临时文档 → 写整份 → 改名就位), 内容按流
 * 写: 大体积内容 (堆转储) 不该有整份内存峰值。内容只读一次 —— 没建出可用临时文档时内容还没被
 * 读出, 回落直写目标名; 内容已写进临时文档时就位不成立时把那份内容搬进目标名, 不再从流重读。
 *
 * @param input 内容来源, 只在写临时文档或回落直写时读取一次, 由调用方负责关闭
 */
fun copyToDocumentAtomically(
    context: Context,
    target: Uri,
    fileName: String,
    input: InputStream,
): Boolean = writeDocumentAtomically(context, target, fileName) { input.copyTo(it) }

/**
 * 整份写入 [target] 下的 [fileName]: 同目录就位优先, 就位不成立时按直写回落。
 *
 * [writeContent] 最多执行一次。
 */
private fun writeDocumentAtomically(
    context: Context,
    target: Uri,
    fileName: String,
    writeContent: (OutputStream) -> Unit,
): Boolean {
    if (!DocumentsContract.isTreeUri(target)) return target.writeContent(context, writeContent)
    val parent = directoryDocumentUri(target)
    return when (placeDocumentAtomically(context, parent, fileName, writeContent)) {
        PlacementResult.Placed -> true
        PlacementResult.Failed -> false
        // 内容还没被读出: 直写目标名 (与不做就位处理时同一落点)
        PlacementResult.Unsupported -> writeChildContent(context, parent, fileName, writeContent)
    }
}

/** 同目录整份就位的结果。 */
private sealed class PlacementResult {
    /** 目标名上是整份内容 (旧件已清)。 */
    object Placed : PlacementResult()

    /** 未建出可用的临时文档 (提供方不支持同目录建/改名), 内容还没读出, 调用方直写目标名。 */
    object Unsupported : PlacementResult()

    /** 没写成就位: 临时文档写入失败 (目标未被触碰), 或内容搬不进目标名 (旧件已改回目标名)。 */
    object Failed : PlacementResult()
}

/**
 * 建临时文档 → 写整份 → 把目标名上的旧文档挪成过渡件 → 改名就位; 每条退出路径都清掉中间件,
 * 就位不成时把内容搬进目标名、把旧文档改回目标名。
 *
 * @param parentUri 目标所在目录的文档 URI (树前缀的目录文档 URI)
 * @param writeContent 往临时文档写整份内容; 只在临时文档建出且名字落在契约名上后调用一次
 */
private fun placeDocumentAtomically(
    context: Context,
    parentUri: Uri,
    fileName: String,
    writeContent: (OutputStream) -> Unit,
): PlacementResult {
    val resolver = context.contentResolver
    val tempName = "$fileName.${randomUUIDString()}$ATOMIC_WRITE_TEMP_SUFFIX"
    val tempUri = createTempDocument(resolver, parentUri, fileName, tempName)
        ?: return PlacementResult.Unsupported

    val written = runCatching {
        resolver.openOutputStream(tempUri)?.use(writeContent) != null
    }.getOrDefault(false)
    if (!written) {
        deleteDocument(resolver, tempUri)
        AppLog.put("SAF 临时文档写入失败, 目标保持原内容: $fileName")
        return PlacementResult.Failed
    }

    // 改名不能顶替已有名字: 目标名上已有旧文档时先挪成过渡名, 目标名空出来
    val parkedUri = parkExistingDocument(resolver, parentUri, fileName)
    val renamed = runCatching {
        DocumentsContract.renameDocument(resolver, tempUri, fileName)
    }.getOrNull()
    // 改名被拒 (返回 null) 时内容仍在临时文档; 改名成功时内容在改名后的那份文档里
    if (renamed != null && documentNameOf(resolver, renamed) == fileName) {
        parkedUri?.let { deleteDocument(resolver, it) }
        return PlacementResult.Placed
    }

    AppLog.put("SAF 就位未落在目标名上: $fileName")
    val contentUri = renamed ?: tempUri
    if (moveContentToChild(context, parentUri, contentUri, fileName)) {
        parkedUri?.let { deleteDocument(resolver, it) }
        return PlacementResult.Placed
    }
    keepContentRecognizable(resolver, contentUri, tempName)
    restoreParkedDocument(resolver, parkedUri, fileName)
    return PlacementResult.Failed
}

/**
 * 按 [tempName] 建出临时文档; 建不出的提供方按不支持就位处理。
 *
 * 提供方建文档时按 MIME 给名字补扩展名 (平台文件提供方走 `FileUtils.buildUniqueFile` 的
 * splitFileName), 名字没落在 [tempName] 上时先改名回去 —— 临时件在进程被强杀时会残留, 名字必须
 * 按契约可识别; 改名也不落回契约名的提供方删掉临时件, 走直写回落 (内容还没读出)。
 */
private fun createTempDocument(
    resolver: ContentResolver,
    parentUri: Uri,
    fileName: String,
    tempName: String,
): Uri? {
    val created = runCatching {
        DocumentsContract.createDocument(
            resolver,
            parentUri,
            FileUtils.getMimeType(fileName),
            tempName,
        )
    }.onFailure {
        // 拿不到目录写权限 (单文档授权的常规情况) 或提供方不允许建文档: 属已知能力边界, 按调试级记
        AppLog.putDebug("SAF 无法在同目录建临时文档, 目标直写: $fileName\n${it.localizedMessage}")
    }.getOrNull() ?: return null
    if (documentNameOf(resolver, created) == tempName) return created
    val renamed = runCatching {
        DocumentsContract.renameDocument(resolver, created, tempName)
    }.getOrNull()
    if (renamed != null && documentNameOf(resolver, renamed) == tempName) return renamed
    deleteDocument(resolver, created)
    return null
}

/**
 * 目标名上已有旧文档时把它改成契约过渡名 (不占目标名), 返回过渡文档 URI;
 * 目标名上没有旧文档、或提供方不接受改名时返回 null (旧文档仍占着目标名)。
 */
private fun parkExistingDocument(
    resolver: ContentResolver,
    parentUri: Uri,
    fileName: String,
): Uri? {
    val existing = findChildDocument(resolver, parentUri, fileName) ?: return null
    val parkName = "$fileName.${randomUUIDString()}$ATOMIC_WRITE_TEMP_SUFFIX"
    return runCatching {
        DocumentsContract.renameDocument(resolver, existing, parkName)
    }.onFailure {
        AppLog.putDebug("SAF 旧文档无法改成过渡名, 目标名直写: $fileName\n${it.localizedMessage}")
    }.getOrNull()
}

/** 就位失败: 把过渡件改回目标名, 目标名上恢复旧内容。 */
private fun restoreParkedDocument(
    resolver: ContentResolver,
    parkedUri: Uri?,
    fileName: String,
) {
    parkedUri ?: return
    val restored = runCatching {
        DocumentsContract.renameDocument(resolver, parkedUri, fileName)
    }.getOrNull()
    if (restored != null && documentNameOf(resolver, restored) == fileName) return
    AppLog.put("SAF 过渡件未能改回目标名, 旧内容留在: ${restored ?: parkedUri}")
}

/**
 * 就位与搬运都没成: 内容文档的名字不在共享契约上时改回契约临时名, 让残留件按名可识别;
 * 改不回来就删掉, 不给用户目录留认不出来的中间件。
 */
private fun keepContentRecognizable(
    resolver: ContentResolver,
    contentUri: Uri,
    tempName: String,
) {
    if (isAtomicWriteTempName(documentNameOf(resolver, contentUri) ?: "")) {
        AppLog.put("SAF 未就位内容留在临时件: $contentUri")
        return
    }
    val renamed = runCatching {
        DocumentsContract.renameDocument(resolver, contentUri, tempName)
    }.getOrNull()
    if (renamed != null && isAtomicWriteTempName(documentNameOf(resolver, renamed) ?: "")) {
        AppLog.put("SAF 未就位内容改回临时件: $renamed")
        return
    }
    AppLog.put("SAF 未就位内容无法回到契约名, 删除: $contentUri")
    deleteDocument(resolver, contentUri)
}

/**
 * 目录文档 URI: createDocument/renameDocument 的父 URI 必须是文档形式。
 *
 * 树 URI 有两种形态: OpenDocumentTree 返回的只有树段 (树根), 以及树前缀下带文档段的目录
 * (它下面某一级目录); 前者的文档标识取树标识, 后者取文档段标识。
 */
private fun directoryDocumentUri(treeUri: Uri): Uri {
    val documentId = runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull()
        ?: DocumentsContract.getTreeDocumentId(treeUri)
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}

/**
 * [parentUri] 下名为 [name] 的子文档; 没有同名子文档返回 null。
 *
 * 同名目录不算: 目录不是可替换的旧件 (把它挪成过渡件再删会连同里面内容一起删掉),
 * 写入方按拿不到目标文档处理。
 */
private fun findChildDocument(resolver: ContentResolver, parentUri: Uri, name: String): Uri? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        parentUri,
        DocumentsContract.getDocumentId(parentUri),
    )
    return runCatching {
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val isDir = cursor.getString(mimeColumn) ==
                    DocumentsContract.Document.MIME_TYPE_DIR
                if (!isDir && cursor.getString(nameColumn) == name) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(
                        parentUri,
                        cursor.getString(idColumn),
                    )
                }
            }
            null
        }
    }.getOrNull()
}

/**
 * [parentUri] 下按 [fileName] 建出的子文档; 提供方给撞名文档加序号 (显示名不再是目标名) 时删掉
 * 并按没建出目标文档处理 (内容不能留在别名下)。
 */
private fun createChildDocument(
    resolver: ContentResolver,
    parentUri: Uri,
    fileName: String,
): Uri? {
    val created = runCatching {
        DocumentsContract.createDocument(
            resolver,
            parentUri,
            FileUtils.getMimeType(fileName),
            fileName,
        )
    }.getOrNull() ?: return null
    if (documentNameOf(resolver, created) == fileName) return created
    AppLog.put("SAF 建文档未落在目标名上: $fileName")
    deleteDocument(resolver, created)
    return null
}

/** [documentUri] 的显示名; 取不到返回 null。 */
private fun documentNameOf(resolver: ContentResolver, documentUri: Uri): String? = runCatching {
    resolver.query(
        documentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

/** 按目标名整份直写目录下的子文档 (没建出目标文档时返回 false)。 */
private fun writeChildContent(
    context: Context,
    parentUri: Uri,
    fileName: String,
    writeContent: (OutputStream) -> Unit,
): Boolean {
    val resolver = context.contentResolver
    val childUri = findChildDocument(resolver, parentUri, fileName)
        ?: createChildDocument(resolver, parentUri, fileName) ?: return false
    return childUri.writeContent(context, writeContent)
}

/** 把 [contentUri] 里的整份内容搬进目录下名为 [fileName] 的子文档, 搬完删掉源文档。 */
private fun moveContentToChild(
    context: Context,
    parentUri: Uri,
    contentUri: Uri,
    fileName: String,
): Boolean {
    val resolver = context.contentResolver
    val existing = findChildDocument(resolver, parentUri, fileName)
    val childUri = existing ?: createChildDocument(resolver, parentUri, fileName) ?: return false
    val copied = runCatching {
        resolver.openInputStream(contentUri)?.use { input ->
            childUri.writeContent(context) { input.copyTo(it) }
        } ?: false
    }.getOrDefault(false)
    if (copied) {
        deleteDocument(resolver, contentUri)
        return true
    }
    // 新建的目标文档写失败: 删掉这份半截, 目标名留给旧件 (调用方把过渡件改回来)
    if (existing == null) deleteDocument(resolver, childUri)
    return false
}

/** 在 [this] 文档上开输出流写内容; 打不开输出流返回 false。 */
private fun Uri.writeContent(context: Context, writeContent: (OutputStream) -> Unit): Boolean =
    context.contentResolver.openOutputStream(this)?.use { output ->
        writeContent(output)
        true
    } ?: false

/** 删掉写入中间留下的文档 (失败只记调试级日志: 中间件不影响目标)。 */
private fun deleteDocument(resolver: ContentResolver, documentUri: Uri) {
    runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
        .onFailure { AppLog.putDebug("SAF 清理中间文档失败: $documentUri\n${it.localizedMessage}") }
}
