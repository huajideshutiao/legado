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
 * 已加载字形按 (路径, 文件身份) 复用一份: 排版重建只取字形不重读字体文件, 换字体文件或文件
 * 内容变化才重新加载。
 *
 * 读不到返回 null, 由 [resolveReaderFontFamily] / 排版度量侧回落系统默认字体; 每条失败路径
 * 留一条可查日志, 不弹任何界面。
 */

/** 自定义字体路径 ([ReadBookConfig.textFont]) → 字形; 路径为空或读不到返回 null。 */
fun readerFontTypeface(fontPath: String): Typeface? {
    if (fontPath.isEmpty()) return null
    val file = readerFontFile(fontPath) ?: return null
    val identity = fileIdentity(file)
    synchronized(typefaceLock) {
        loadedTypefaces[fontPath]?.let { if (it.identity == identity) return it.typeface }
    }
    val typeface = try {
        Typeface.createFromFile(file)
    } catch (e: Exception) {
        return reportFontLoadFailure(fontPath, "字体数据建不出字形", e)
    }
    // 文件在但不是字体: 框架按默认字形返回 (android.graphics.Typeface.createFromFile)
    if (typeface === Typeface.DEFAULT) {
        return reportFontLoadFailure(fontPath, "字体数据建不出字形", null)
    }
    synchronized(typefaceLock) {
        if (loadedTypefaces.size >= LOADED_CACHE_SIZE) {
            loadedTypefaces.remove(loadedTypefaces.keys.first())
        }
        loadedTypefaces[fontPath] = LoadedTypeface(identity, typeface)
    }
    lastFailureReport = null
    return typeface
}

/** 缓存条数上界: 正文与标题共用一条, 用户换字体时旧路径各占一条, 一次阅读只用一份字体。 */
private const val LOADED_CACHE_SIZE = 4

private const val COPY_DIR = "readerFont"
private const val MARKER_FILE = "fontFolder"

/** 已加载字形与它对应的字体文件身份 (大小 + 最后修改时刻)。 */
private class LoadedTypeface(val identity: String, val typeface: Typeface)

private val typefaceLock = Any()

/** 已加载字形, 键为字体文件路径; 超界按加入顺序淘汰最早的一条。 */
private val loadedTypefaces = LinkedHashMap<String, LoadedTypeface>()

/** 复制副本与复用判定一次只做一份字体, 串行执行。 */
private val copyLock = Any()

/** 已在日志里报过的同一个失败, 免得排版反复取字形时重复写日志。 */
@Volatile
private var lastFailureReport: String? = null

/** 字体文件的真实路径: content 地址落到缓存副本, 普通路径原样使用; 取不到返回 null 并记日志。 */
private fun readerFontFile(fontPath: String): File? {
    if (!fontPath.isContentScheme()) {
        val file = File(fontPath)
        if (file.isFile) return file
        return reportFontLoadFailure(fontPath, "文件不存在或不是普通文件", null)
    }
    val context = sharedAppContext
        ?: return reportFontLoadFailure(fontPath, "取授权或读盘失败: 应用上下文未就绪", null)
    return try {
        copyToCache(context, fontPath.toUri())
    } catch (e: Exception) {
        reportFontLoadFailure(fontPath, "取授权或读盘失败", e)
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

/** 字体文件身份: 大小 + 最后修改时刻, 两者都不变即认为内容未变。 */
private fun fileIdentity(file: File): String = "${file.length()}:${file.lastModified()}"

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
    val temp = File(dir, "${target.name}.tmp")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("字体文件打不开: $uri")
        check(temp.renameTo(target)) { "字体副本落盘失败: $target" }
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
