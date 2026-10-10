package io.legado.app.help.file

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.core.provider.DocumentsContractCompat
import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.isContentScheme
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 安卓用户选定目录的用途登记表。
 *
 * 安卓的 SAF 树权限由系统按树 URI 授予 (读写整棵树) 且没有用途维度: 用户为"备份目录"这类用途选定的
 * 宽目录顺带放行了其下的书籍文件。本表在目录选定当时按用途登记, 本地书文件访问前校验目录归属 ——
 * 只有书籍类入口选定的目录 ([DirUsage.BookTree]: 书籍保存位置、导入目录) 才允许作为书籍文件访问,
 * 为备份/导出/图片/字体选定的目录不放行书籍文件。同一目录可为不同用途分别选定, 命中任一条书籍用途即放行。
 *
 * 未登记的引用 (应用私有区、别处分享进来的文件) 不在本表管辖内, 不做校验直接放行 —— 安卓没有沙盒,
 * 登记只覆盖用户明确为此用途选定的目录。
 *
 * 值: JSON Map<用途, 已选目录的标识列表> (标识为选择器回传的 SAF 树 URI 或 file:// 路径)。
 */
object AndroidDirAuthorizations {

    private const val KEY_DIR_AUTHORIZATIONS = "androidDirAuthorizations"

    @Volatile
    private var cached: Map<DirUsage, List<String>>? = null

    /**
     * 登记用户在 [usage] 用途下选定的目录。
     *
     * 同一用途内重复选定同一目录只留一条 (按位置去重), 其余条目一律保留 —— 用户为不同书籍分别授权过的
     * 目录都留着; 同一目录在其它用途下已登记的条目不受影响。
     *
     * @param uri 选择器回传的目录标识 (SAF 树 URI 或 file:// 路径)
     */
    @Synchronized
    fun remember(uri: String, usage: DirUsage) {
        val location = locationOf(uri) ?: return
        val current = stored().toMutableMap()
        val forUsage = current[usage].orEmpty().filterNot { sameLocation(it, location) }
        current[usage] = forUsage + uri
        cached = current
        PreferenceProviders.get().putString(KEY_DIR_AUTHORIZATIONS, KS_JSON.encodeToString(current))
    }

    /**
     * 本地书文件访问前的用途校验: 非本地书直通, 位置未登记直通, 落在非书籍用途选定的目录下抛
     * [SecurityException] (阅读层据此提示重新选目录, 与 iOS/鸿蒙的授权失败同一形态)。
     *
     * 目标取本地书实际会访问的引用 (含按文件名回退到书籍保存位置与导入目录的解析),
     * 使校验的引用与实际读写的文件是同一个。
     */
    fun ensureBookFileUsage(book: Book) {
        if (!book.isLocal) return
        ensureBookFileUsage(book.getLocalUri().toString())
    }

    /** 同上, 按文件引用校验 (导入的源文件同样只允许来自书籍用途登记的目录)。 */
    @Synchronized
    fun ensureBookFileUsage(target: String) {
        val targetLocation = locationOf(target) ?: return
        val usages = stored().filterValues { entries ->
            entries.any { entry -> locationOf(entry)?.contains(targetLocation) == true }
        }.keys
        if (usages.isEmpty() || DirUsage.BookTree in usages) return
        AppLog.put("本地书访问被用途校验挡下: $target 落在 ${usages.joinToString()} 用途选定的目录下")
        throw SecurityException("没有访问权限: $target")
    }

    /** 已登记标识 [uri] 与本次位置是否同一处 (重复选定同一目录时替换旧条目)。 */
    private fun sameLocation(uri: String, location: Location): Boolean =
        locationOf(uri)?.sameAs(location) == true

    /** 选择器回传的目录标识或书籍文件引用的位置; 无法判定返回 null。 */
    private fun locationOf(raw: String): Location? {
        if (raw.isBlank()) return null
        if (raw.isContentScheme()) {
            val uri = raw.toUri()
            val docId = documentIdOf(uri)
            return Location(uri.authority, docId, docId?.let(::sharedVolumePath))
        }
        val path = if (raw.startsWith("file:")) raw.toUri().path else raw
        return path?.takeIf { it.isNotBlank() }?.let { Location(null, null, filePath(it)) }
    }

    /** 规范化绝对路径 (file:// URI 取路径, 相对路径与符号链接按真实位置规范化)。 */
    private fun filePath(path: String): String {
        val file = File(path)
        return runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }

    /** primary 共享卷的文档标识 ("primary:相对路径") 对应的文件系统路径; 其它卷与云盘无可靠转换返回 null。 */
    @Suppress("DEPRECATION") // primary 卷根路径的取值 (与 RealPathUtil 同)
    private fun sharedVolumePath(docId: String): String? {
        val separator = docId.indexOf(':')
        if (separator <= 0 ||
            !"primary".equals(docId.substring(0, separator), ignoreCase = true)
        ) {
            return null
        }
        val relative = docId.substring(separator + 1).trim('/')
        val root = Environment.getExternalStorageDirectory().absolutePath
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** SAF 文档标识 (树 URI 取树标识, 文档 URI 取文档标识); 非 DocumentsProvider 的 content URI 返回 null。 */
    private fun documentIdOf(uri: Uri): String? = runCatching {
        if (DocumentsContractCompat.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    }.getOrNull()

    /** 锁内调用: 登记集合 (volatile 缓存优先, 首次解析 pref)。 */
    private fun stored(): Map<DirUsage, List<String>> {
        cached?.let { return it }
        val raw = PreferenceProviders.get().getString(KEY_DIR_AUTHORIZATIONS, "")
        val parsed = if (raw.isEmpty()) {
            emptyMap()
        } else {
            runCatching { KS_JSON.decodeFromString<Map<DirUsage, List<String>>>(raw) }.getOrElse {
                AppLog.put("目录用途登记表解析失败\n${it.message}", it)
                emptyMap()
            }
        }
        cached = parsed
        return parsed
    }

    /**
     * 一处目录或文件的可比对位置: SAF 文档位置 (authority + 文档标识) 与文件系统路径。
     *
     * 两种形态都留着, 使路径形式的书籍文件也能匹配按树 URI 登记的目录 (反之亦然)。
     */
    private class Location(
        private val authority: String?,
        private val docId: String?,
        private val path: String?,
    ) {

        /** 本位置是否覆盖 [target] (文档标识段前缀或路径段前缀)。 */
        fun contains(target: Location): Boolean =
            containsDocument(target) || containsPath(target)

        fun sameAs(other: Location): Boolean {
            if (docId != null && docId == other.docId && authority == other.authority) return true
            return path != null && path == other.path
        }

        private fun containsDocument(target: Location): Boolean {
            val rootDocId = docId ?: return false
            if (authority == null || target.authority != authority) return false
            val targetDocId = target.docId ?: return false
            return targetDocId == rootDocId || targetDocId.startsWith("$rootDocId/")
        }

        private fun containsPath(target: Location): Boolean {
            val root = path ?: return false
            val targetPath = target.path ?: return false
            return targetPath == root || targetPath.startsWith("$root/")
        }
    }
}
