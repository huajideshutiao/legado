package io.legado.app.help.book

import io.legado.app.constant.LOG_DIR_NAME
import io.legado.app.constant.PreferKey
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString

/**
 * 有效书籍保存目录: 用户在"其它设置 → 书籍保存位置"选定后写入 [PreferKey.defaultBookTreeUri]
 * 的那个目录; 未设置时为 null (不回退任何平台惯例目录, 保存位置必须由用户显式选定)。
 *
 * 只负责取出"有效目录标识", 不做路径解析: Android 端选过的值是 SAF `content://` 树引用,
 * 由 app 端 FileBookAccessorImpl 自行按 SAF 解析; 桌面/iOS/鸿蒙三端是普通 POSIX 路径,
 * 由各端 FileBookAccessor 自行解析 (与各端 `findBookFileInDir(dirUri, ...)` 的入参同一形态)。
 */
fun effectiveBookSaveDir(): String? =
    PreferenceProviders.get().getString(PreferKey.defaultBookTreeUri)
        .takeIf { it.isNotBlank() }

/**
 * 断言存在有效书籍保存目录并返回 (见 [effectiveBookSaveDir])。
 *
 * 未设置时抛 [InvalidBooksDirException] (本仓库中原版 `NoBooksDirException` 的共用等价类型,
 * 文案取原版同一条资源 `no_books_dir`), 由调用链既有的"去设置目录"出口
 * ([io.legado.app.ui.root.PlatformCapabilities.pickBookTreeUri]) 拉起目录选择器。
 */
fun requireBookSaveDir(): String =
    effectiveBookSaveDir()
        ?: throw InvalidBooksDirException(appString(AppStringKey.no_books_dir))

/**
 * 书籍保存位置下的产物子目录 (普通路径形态): [saveDir] + 分隔符 + [name]。
 *
 * 只给路径形态的端用 (桌面/iOS/鸿蒙); 安卓的保存位置是 SAF 树引用, 不能拼路径 —— 那里把子目录
 * **名**交给文档 API 在树内建出同名子目录。
 */
fun bookSaveSubDir(saveDir: String, name: String): String =
    saveDir.trimEnd('/', '\\') + BackupFileOps.separator + name

/** 日志落点 (书籍保存位置/log, 普通路径形态); 未设置保存位置返回 null。 */
fun bookLogDirOrNull(): String? =
    effectiveBookSaveDir()?.let { bookSaveSubDir(it, LOG_DIR_NAME) }

/** 导入目录的 pref key (app 端 `AppConfig.importBookPath`、桌面与 iOS/鸿蒙导入页同键)。 */
const val IMPORT_BOOK_PATH_KEY = "importBookPath"

/**
 * 导入目录: 用户上次在导入页选定的来源目录; 未设置返回 null。
 *
 * 与 [effectiveBookSaveDir] 同属"引用失效后在哪些目录里按文件名找回本地书"的定位口径
 * (见 [LocalBookLocator.getReadableFileRef]), 与各端 `findBookFileInDir(dirUri, ...)` 的入参同一形态。
 */
fun effectiveImportBookDir(): String? =
    PreferenceProviders.get().getString(IMPORT_BOOK_PATH_KEY).takeIf { it.isNotBlank() }

/** 按名找回时的下探层数与列举目录数上限 (原版 `FileDoc.find(originName, 5, 100)` 的两个实参)。 */
private const val FIND_DEPTH = 5
private const val FIND_MAX_DIRS = 100

/**
 * 依次在书籍保存位置 [saveDir] 与导入目录 [importDir] 里按名找回, 两个目录指向同一处时只查一次。
 *
 * 单目录内的查找交给 searchInDir —— iOS/鸿蒙覆写的实现把整个查找放进该目录的授权内
 * (目录列举与文件探测都要在授权生效期间才能看到容器外的文件)。
 *
 * @param searchInDir 单个目录内的按名查找, 入参是目录路径, 返回找到的文件路径或 null
 */
fun findLocalBookFileInDirs(
    saveDir: String?,
    importDir: String?,
    searchInDir: (String) -> String?,
): String? {
    val save = saveDir?.takeIf { it.isNotBlank() }
    if (save != null) {
        searchInDir(save)?.let { return it }
    }
    val import = importDir?.takeIf { it.isNotBlank() }
    if (import != null && import != save) {
        searchInDir(import)?.let { return it }
    }
    return null
}

/**
 * 在目录 [dir] 里按文件名 [name] 逐层找第一个满足 isReadable 的文件, 返回绝对路径; 找不到返回 null。
 *
 * 口径对齐原版 `Book.getLocalUri()` 的按名找回: 下探 [FIND_DEPTH] 层, 最多列举 [FIND_MAX_DIRS] 个
 * 目录; 目录列举复用 [FileUtilsCommon] 的既有能力 (各端 actual 分别走 java.io.File 与 okio)。
 *
 * 只做定位: 返回的路径不回写书籍记录、不写路径缓存。
 *
 * @param isReadable 单个候选文件的可用性判据 (由各端提供)
 */
fun findReadableLocalBookFileInDir(
    dir: String,
    name: String,
    isReadable: (String) -> Boolean,
): String? = findReadableFileInTree(dir, name, isReadable)

/**
 * [findReadableLocalBookFileInDir] 的逐层实现: 先看本层同名文件, 再按列举顺序进入子目录
 * (深度优先, 与原版 `FileDoc.find` 的顺序一致)。
 */
private fun findReadableFileInTree(
    root: String,
    name: String,
    isReadable: (String) -> Boolean,
): String? {
    // 待列举目录及其剩余下探层数; FIND_MAX_DIRS 是本次查找列举目录数的上限 (与原版 maxFinds 同计法)
    val pending = ArrayDeque<Pair<String, Int>>()
    pending.addLast(root to FIND_DEPTH)
    var visited = 0
    while (pending.isNotEmpty()) {
        if (visited++ >= FIND_MAX_DIRS) return null
        val (dir, depth) = pending.removeFirst()
        FileUtilsCommon.listFiles(dir)
            .firstOrNull { FileUtilsCommon.getName(it) == name && isReadable(it) }
            ?.let { return it }
        if (depth <= 0) continue
        val subDirs = FileUtilsCommon.listSubDirs(dir)
        for (i in subDirs.indices.reversed()) {
            pending.addFirst(subDirs[i] to (depth - 1))
        }
    }
    return null
}
