package io.legado.app.help.file

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.Book
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
     * [SecurityException] (阅读层据此提示重新选目录, 与 iOS/鸿蒙的授权失败同一形态),
     * 书籍的文件引用为空时抛 [IllegalArgumentException]。
     *
     * 目标就是书籍自身的引用 ([Book.bookUrl], 归一化交给 [locationOf]) —— 本校验只判定该引用所在的
     * 位置: 不读文件内容, 不搜索同名文件, 不改写书籍引用也不落库。
     * 读取链在引用打不开时会按文件名在书籍保存位置与导入目录里另找一个同名文件打开 (两个目录都是
     * 书籍用途选定的目录, 见 [DirUsage.BookTree]), 那个文件的位置不由本校验判定; 删原文件取的仍是
     * 书籍自身的引用 (路径引用由定位器纯解析成绝对路径, content 引用直接取该 URI)。
     */
    fun ensureBookFileUsage(book: Book) {
        if (!book.isLocal) return
        ensureBookFileUsage(book.bookUrl)
    }

    /**
     * 同上, 按文件引用校验 (导入的源文件同样只允许来自书籍用途登记的目录); 引用为空/空白按输入
     * 不合法拒绝 —— 空引用不属于"未登记引用直通"覆盖的应用私有区与别处分享进来的文件。
     */
    @Synchronized
    fun ensureBookFileUsage(target: String) {
        require(target.isNotBlank()) { "本地书文件引用为空" }
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
            val treeDocId = treeDocumentIdOf(uri)
            return Location(
                listOfNotNull(
                    docId?.let { Position(uri.authority, it, sharedVolumePath(it)) },
                    treeDocId?.takeIf { it != docId }
                        ?.let { Position(uri.authority, it, sharedVolumePath(it)) },
                )
            )
        }
        val path = if (raw.startsWith("file:")) raw.toUri().path else raw
        return path?.takeIf { it.isNotBlank() }
            ?.let { Location(listOf(Position(null, null, filePath(it)))) }
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

    /**
     * SAF 文档标识: 文档 URI 取该文档标识, 树+文档 URI 取该文档的标识; 取不到文档标识的 URI
     * (纯树 URI) 退回树标识。非 DocumentsProvider 的 content URI 返回 null。
     */
    private fun documentIdOf(uri: Uri): String? = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.recoverCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull()

    /** 引用所依据的树标识 (树 URI 与树+文档 URI 都有); 其它 content URI 返回 null。 */
    private fun treeDocumentIdOf(uri: Uri): String? =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

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
     * 一处 SAF 文档位置: authority + 文档标识 + 由文档标识转换出的文件系统路径 (无可靠转换时为 null)。
     *
     * 文档标识与文件系统路径两种形态都留着, 使路径形式的书籍文件也能匹配按树 URI 登记的目录
     * (反之亦然)。
     */
    private class Position(
        private val authority: String?,
        private val docId: String?,
        private val path: String?,
    ) {

        /** 本位置是否覆盖 [target] (文档标识段前缀或路径段前缀)。 */
        fun covers(target: Position): Boolean =
            coversDocument(target) || coversPath(target)

        /** 本位置与 [other] 是否同一处。 */
        fun sameAs(other: Position): Boolean {
            if (docId != null && docId == other.docId && authority == other.authority) return true
            return path != null && path == other.path
        }

        private fun coversDocument(target: Position): Boolean {
            val rootDocId = docId ?: return false
            if (authority == null || target.authority != authority) return false
            val targetDocId = target.docId ?: return false
            return targetDocId == rootDocId || targetDocId.startsWith("$rootDocId/")
        }

        private fun coversPath(target: Position): Boolean {
            val root = path ?: return false
            val targetPath = target.path ?: return false
            return targetPath == root || targetPath.startsWith("$root/")
        }
    }

    /**
     * 一处目录或文件的可比对位置: 一组 SAF 文档位置。
     *
     * 树+文档形态的引用 (形如 `content://…/tree/A/document/B`, 取子文件与新建文件都会产出该形态)
     * 同时带着文档自身的位置 (B) 与引用所依据的树的位置 (A), 覆盖任一即算覆盖这本书的文件 ——
     * 只有文档标识能区分同一棵树内的不同目录, 而文档标识不逐级延伸的提供方 (云盘等) 上, 可比对的
     * 只有树标识。
     */
    private class Location(private val positions: List<Position>) {

        /** 本位置是否覆盖 [target] (任一对文档标识段前缀或路径段前缀命中即算覆盖)。 */
        fun contains(target: Location): Boolean =
            positions.any { mine -> target.positions.any { mine.covers(it) } }

        /** 本位置与 [other] 是否同一处 (同一目录重复选定时替换旧条目)。 */
        fun sameAs(other: Location): Boolean =
            positions.any { mine -> other.positions.any { mine.sameAs(it) } }
    }
}
