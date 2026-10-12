package io.legado.app.ui.book.read.page

import android.content.Context
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.platform.sharedAppContext
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.atomicWriteTempPath
import io.legado.app.utils.isContentScheme
import java.io.File

/**
 * 安卓自定义字体文件 → 字形。
 *
 * 用户在应用容器外选定的字体目录经 SAF 授权, 目录内的字体文件只有 content 地址
 * (`content://`, 扫描列表给出的就是它)。API 24 没有从字节或文件描述符建字形的公开接口
 * —— `Typeface.Builder` 与 `Typeface.createFromFile(FileDescriptor)` 均为 API 26 起,
 * androidx 在 API 26 以下同样落一份临时文件再 `Typeface.createFromFile`
 * (androidx.core `TypefaceCompatBaseImpl.createFromResourcesFontFile`)。
 * content 地址的字体复制一份到应用缓存目录再按真实路径加载, 全部系统版本走同一条路。
 *
 * 副本 (`{cacheDir}/readerFont`) 一个字体文件一份, 文件名带源文件的 (大小, 修改时间):
 * 同一份内容只复制一次, 源文件变过按新身份再复制并清掉旧副本; 换字体目录 (目录 pref 变过)
 * 与"清除缓存"(整个缓存目录)都会清掉副本。
 *
 * 每个字体路径上只有一条结论 ([FontEntry]): 本次读到的文件与它的标识, 以及按它建出的字形 ——
 * 绘制侧与排版度量侧读同一张表, 两侧因此总是同一份字形。绘制侧每次解析 (进阅读页 / 换字体)
 * 重读一次字体文件 (内容变过才重建字形) 并更新那条结论; 排版度量侧只按结论复用, 不探测文件、
 * 不重读字体文件。于是排版重建 (首排 / 视口重排 / 段评补排) 只在结论变化或条目被淘汰时才付一次
 * 读盘与建字形。
 *
 * 读一次字体文件与按它建字形同在副本锁内: 副本通道清掉或换掉副本不会落在两者之间。
 *
 * 字体文件读不到时那条结论里已建出的字形继续用 —— 源暂时不可读不让字体平白退回默认, 两侧仍是
 * 同一份字形; 从未建出字形 (首次加载就读不到) 才两侧一起回落系统默认字体, 由
 * [resolveReaderFontFamily] / 排版度量侧处理。真回落时留一条可查日志, 同一失败只记一次,
 * 不弹任何界面。
 */

/**
 * 绘制侧: 读一次字体文件并按这次读到的东西更新本条结论, 与本条结论对同一次读就复用已建字形;
 * 字体文件读不到时本条已建字形继续用。
 *
 * 读盘在 [loadLock] 内执行: 读到哪份文件与按它建字形必须是同一份 (见 [readAndBuild])。
 */
fun readerFontTypeface(fontPath: String): Typeface? {
    if (fontPath.isEmpty()) return null
    return synchronized(loadLock) { readAndBuild(fontPath) }
}

/**
 * 排版度量侧: 用 [fontPath] 上那条结论里的字形, 命中路径不探测文件、不重读字体文件 —— 首排 /
 * 视口重排 / 段评补排每次构造度量器都到这里。
 *
 * 本进程还没读过这个路径时真读一次并留下结论 (绘制侧尚未解析过的首排): 与绘制侧同一套读法,
 * 两侧得到同一条结论。
 */
fun readerLayoutTypeface(fontPath: String): Typeface? {
    if (fontPath.isEmpty()) return null
    return synchronized(loadLock) {
        fontEntries[fontPath]?.let { return it.typeface }
        readAndBuild(fontPath)
    }
}

/** 缓存条数上界: 正文与标题共用一条, 用户换字体时旧路径各占一条, 一次阅读只用一份字体。 */
private const val LOADED_CACHE_SIZE = 4

private const val COPY_DIR = "readerFont"
private const val MARKER_FILE = "fontFolder"

/** 字体文件标识: 大小 + 最后修改时刻 (毫秒), 两者都不变即认为文件内容未变。 */
private data class FileIdentity(val size: Long, val lastModified: Long)

/** 一次读取字体文件的结果。 */
private sealed interface FontRead {
    /** [file] 为本次实际读到的文件 (content 地址是它的缓存副本), [identity] 为它当时的标识。 */
    data class Loaded(val file: File, val identity: FileIdentity) : FontRead

    /** 文件不存在 / 读盘或复制失败; [reason] 与 [cause] 供真要回落默认字体的一侧记日志。 */
    data class Unreadable(val reason: String, val cause: Throwable?) : FontRead
}

/**
 * 一个字体路径上的一条结论: [read] 是这次读到的文件与它的标识 (或读不到), [typeface] 是按它建出的
 * 字形 (null 表示没建出)。
 */
private class FontEntry(val read: FontRead, val typeface: Typeface?)

/** 读盘与建字形一次只做一份字体, 串行执行; 内层持 [copyLock], 锁序固定为它在前。 */
private val loadLock = Any()

/** 结论表, 键为字体路径, 绘制侧与排版度量侧共用同一张; 超界按加入顺序淘汰最早的一条。 */
private val fontEntries = LinkedHashMap<String, FontEntry>()

/**
 * 复制副本一次只做一份字体, 串行执行; 读盘与建字形也持此锁 —— 副本被清掉或换掉不会落在
 * 「读到哪份文件」与「按它建字形」之间。
 */
private val copyLock = Any()

/** 已在日志里报过的同一个失败, 免得排版反复取字形时重复写日志。 */
@Volatile
private var lastFailureReport: String? = null

/**
 * 读一次字体文件并按这次读到的东西建字形, 把结论写回 [fontEntries]; 与本条结论对同一次读就复用。
 *
 * 调用方持 [loadLock], 本函数持 [copyLock]: 副本通道 (复制副本、换字体目录清副本、同名字体内容
 * 变过清旧副本) 的删除与替换不会落在「读到哪份文件」与「按它建字形」之间。读盘与建字形一次只做
 * 一份字体: 首排时并发的章节排版同时到达同一路径, 持锁让后来者复用这一次的结果。
 *
 * 建字形期间读到的那份文件被清掉或换掉 (整个缓存目录被清) 时这份数据不作数, 按当前状态重读
 * (副本没了重新抄一份); 文件没变过仍建不出字形时按这份数据判失败。字体文件读不到时本条已建出的
 * 字形继续用 (绘制侧手里那份就是它), 返回 null 只发生在从未建出字形时, 调用方回落系统默认字形。
 */
private fun readAndBuild(fontPath: String): Typeface? {
    var typeface: Typeface? = null
    synchronized(copyLock) {
        while (true) {
            val read = readFont(fontPath)
            val entry = fontEntries[fontPath]
            when (read) {
                is FontRead.Unreadable -> {
                    typeface = entry?.typeface
                        ?: remember(fontPath, read, reportFontLoadFailure(fontPath, read.reason, read.cause))
                    break
                }
                is FontRead.Loaded -> {
                    if (entry != null && entry.read == read) {
                        typeface = entry.typeface
                        break
                    }
                    var cause: Throwable? = null
                    val built = try {
                        Typeface.createFromFile(read.file)
                    } catch (e: Exception) {
                        cause = e
                        null
                    }
                    // 文件在但不是字体: 框架按默认字形返回 (android.graphics.Typeface.createFromFile)
                    if (built != null && built !== Typeface.DEFAULT) {
                        lastFailureReport = null
                        typeface = remember(fontPath, read, built)
                        break
                    }
                    if (fileIdentity(read.file) == read.identity) {
                        typeface = remember(fontPath, read, reportFontLoadFailure(fontPath, "字体数据建不出字形", cause))
                        break
                    }
                }
            }
        }
    }
    return typeface
}

/** 留下本次结论并回传字形; 超出上界时淘汰最早的一条。 */
private fun remember(fontPath: String, read: FontRead, typeface: Typeface?): Typeface? {
    if (fontPath !in fontEntries && fontEntries.size >= LOADED_CACHE_SIZE) {
        fontEntries.remove(fontEntries.keys.first())
    }
    fontEntries[fontPath] = FontEntry(read, typeface)
    return typeface
}

/**
 * 真读一次字体文件: 取到可加载的文件与它当时的标识; 读不到时连原因一起带回去, 留给真要回落默认
 * 字体的一侧记日志。
 */
private fun readFont(fontPath: String): FontRead {
    if (!fontPath.isContentScheme()) {
        val file = File(fontPath)
        if (file.isFile) return FontRead.Loaded(file, fileIdentity(file))
        return FontRead.Unreadable("文件不存在或不是普通文件", null)
    }
    val context = sharedAppContext
        ?: return FontRead.Unreadable("取授权或读盘失败: 应用上下文未就绪", null)
    return try {
        val copy = copyToCache(context, fontPath.toUri())
        FontRead.Loaded(copy, fileIdentity(copy))
    } catch (e: Exception) {
        FontRead.Unreadable("取授权或读盘失败", e)
    }
}

/** 字体用不了: 留一条可查日志并回落默认字体。 */
private fun reportFontLoadFailure(path: String, reason: String, cause: Throwable?): Nothing? {
    val message = "正文字体加载失败, 回落默认字体: $path\n$reason"
    if (message != lastFailureReport) {
        lastFailureReport = message
        AppLog.put(message, cause)
    }
    return null
}

/** 取 [file] 当前的标识。 */
private fun fileIdentity(file: File): FileIdentity =
    FileIdentity(size = file.length(), lastModified = file.lastModified())

/** 复制 content 地址的字体到缓存副本; 同一份内容 (文件名带源文件身份) 直接复用。复制失败抛异常。 */
private fun copyToCache(context: Context, uri: Uri): File = synchronized(copyLock) {
    val dir = File(context.cacheDir, COPY_DIR)
    discardCopiesOfOtherFontFolder(dir)
    val name = uri.lastPathSegment
        ?.substringAfter(':')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        // 文件名过长时截短: 目标名还要接身份后缀, 超长会直接写不进缓存
        ?.take(96)
        ?: error("字体文件名取不到: $uri")
    // 提供方不给 (大小, 修改时间) 时内容是否变过无法判断, 按源地址区分并每次重新复制
    val identity = documentIdentity(context, uri)
    val target = File(dir, "$name.${identity ?: "u${uri.hashCode()}"}")
    if (identity != null && target.isFile) return target
    // 临时件名走共享契约 (随机唯一串, 与本目录下其他整份写入同一套判定):
    // 唯一串不与任何一次复制共用路径, 被强杀残留的那份也只是缓存目录里的可清点件
    val temp = File(atomicWriteTempPath(target.path))
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("字体文件打不开: $uri")
        FileUtilsBase.replaceFileAtomically(temp, target)
    } finally {
        temp.delete()
    }
    // 一个字体名只留刚写好的这一份, 内容变过或换过来的同名字体旧副本一起清掉
    dir.listFiles()?.forEach { if (it != target && it.name.startsWith("$name.")) it.delete() }
    target
}

/** 字体目录 (pref) 换过后删掉旧目录留下的副本; 副本目录不存在时一并建好。 */
private fun discardCopiesOfOtherFontFolder(dir: File) {
    val marker = File(dir, MARKER_FILE)
    val fontFolder = PreferenceProviders.get().getStringOrNull(PreferKey.fontFolder).orEmpty()
    if (runCatching { marker.readText() }.getOrNull() == fontFolder) return
    dir.deleteRecursively()
    dir.mkdirs()
    runCatching { marker.writeText(fontFolder) }
}

/** 源字体文件的 (大小, 修改时间) 身份; 提供方不给这两列时返回 null。 */
private fun documentIdentity(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val size = cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE)
        val modified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        if (size == null || modified == null) null else "s${size}m$modified"
    }
}.getOrNull()

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
