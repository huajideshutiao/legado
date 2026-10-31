package io.legado.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.archive.ArchiveProviders
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.TempBookPersistence
import io.legado.app.ui.association.DeepLinkImportRequest
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.association.detectJsonType
import io.legado.app.ui.association.toDeepLinkImportType
import io.legado.app.ui.compose.platform.syncGetString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.video.VideoDirect
import io.legado.app.utils.isJson
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.atomic
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * 文件关联导入分发链 (iOS/鸿蒙/桌面共用, 各端逻辑完全一致)。
 *
 * 对照 app 端 `FileAssociationViewModel.dispatchIntent` / `dispatch`:
 * 0. 视频文件 (videoFileRegex) → 播放页直投态 (不建书、不查书源);
 * 1. 压缩包 (archiveFileRegex) → 解压后只把包内的**书籍文件**逐个交回 [dispatchFile]
 *    (包内视频不在本层放行, 原因见 [dispatch]);
 * 2. JSON → [detectJsonType] 识别类型, 经 [LegadoDeepLinkHandler.enqueue] 交
 *    sharedUiMain `DeepLinkImportHost` 走对应 Import*ViewModelShared (与 deep link 同链,
 *    弹勾选对话框后入库);
 * 3. 书籍文件 (bookFileRegex) → [FileBook.importLocalFile] + 跳阅读路由;
 * 4. 其余未知文件 → 回调 [onUnknownFile] 由当前窗口确认, 确认后走既有本地书导入
 *    ([FileBook.importLocalFile]), 拒绝则取消; 已识别为 JSON 但业务类型未知的仍报
 *    wrong_format, 不转小说。
 *
 * 与原版差异: 原版结果推 LiveData 由 Fragment 弹窗, 这里直接触发路由/导入宿主。
 * 压缩包分支与原版同口径 (原版 `FileAssociationViewModel.dispatchIntent` 给
 * `ArchiveUtils.deCompress` 传了自己的 `filter = { it.matches(bookFileRegex) }`): 两端包内都只导书。
 */
object FileAssociationDispatch {

    /** 导入请求序号: 解压目录按请求独占, 同名压缩包/连续导入互不覆盖。 */
    private val importSequence = atomic(0L)

    /**
     * 分发文件关联导入; [filePath] 为绝对路径 (或 file:// URL)。
     *
     * @param onUnknownFile 非视频/非 JSON/非书籍的未知文件待用户确认: 收到文件名,
     *   返回 true 按书籍文件走既有本地书导入, false 取消本次导入。
     *
     * # 压缩包里的视频故意不在本层放行
     * 只按改动前的口径取书籍文件。包内视频要不要解压出来再投给播放器**没人拍板过**:
     * 它意味着临时目录里落一盘电影、多个视频依次弹多个播放页、以及 native 侧
     * `NativeImportBook.openReader` 同入口下“阅读页 + 播放页”叠两层这些新行为,
     * 需要单独定 (各端 `deCompress` 本身**不按扩展名过滤**, 过滤就在下面这一行)。
     */
    suspend fun dispatch(
        filePath: String,
        onUnknownFile: suspend (fileName: String) -> Boolean,
        navigate: Boolean = true,
    ): Book? {
        val path = filePath.toLocalFilePath()
        val fileName = path.fileName()
        // 压缩包: 先解压再逐个分发 (对照 app 端 archive 分支)。
        // 解压目录按本次请求独占; 书籍导入前先落长期副本, 处理完只清理本次目录。
        if (fileName.matches(AppPattern.archiveFileRegex)) {
            val workKey = "${systemCurrentTimeMillis()}-${importSequence.incrementAndGet()}"
            val extracted = runCatching {
                ArchiveProviders.get().deCompressIsolated(path, workKey)
            }.getOrElse { e ->
                Toasters.get().toast(e.message ?: "解压失败")
                AppLog.put("解压关联文件失败: $path", e)
                return null
            }
            val workDir = FileUtilsCommon.getPath(
                FileUtilsCommon.getCachePath(),
                ArchiveProviders.get().tempFolderName,
                "import-$workKey",
            )
            var lastBook: Book? = null
            try {
                // 与 app 端调用点自己传的 filter 同口径: 只取书籍文件 (非拍板项不得顺手扩)
                val books = extracted.filter { it.fileName().matches(AppPattern.bookFileRegex) }
                if (books.isEmpty()) {
                    Toasters.get().toast("压缩包内没有可导入的书籍文件")
                    return null
                }
                for (extractedPath in books) {
                    // 解压条目在暂存目录: 先落成应用内长期文件再导入, 后续清理不会删掉新书;
                    // 资格取当前分发改路 coroutine (导入会话/窗口队列), 取消不留孤儿长期副本
                    val durable = TempBookPersistence.persist(
                        extractedPath,
                        extractedPath.fileName(),
                        currentCoroutineContext()[Job],
                    )
                    val book = dispatchFile(durable, onUnknownFile, navigate = false)
                    // 当前包内批次按原合同: 任一失败即停批 (外部独立文件由 dispatchAll 各自继续)
                    if (book == null) break
                    lastBook = book
                }
                if (navigate) lastBook?.let { openBook(it) }
            } finally {
                FileUtilsCommon.delete(workDir)
            }
            return lastBook
        }
        return dispatchFile(path, onUnknownFile, navigate)
    }

    /**
     * 一批本地文件: 逐项导入, 每项开始前检查取消; 普通失败继续, 整批结束只导航最后成功的一本。
     */
    suspend fun dispatchAll(
        filePaths: List<String>,
        onUnknownFile: suspend (fileName: String) -> Boolean,
    ) {
        var lastBook: Book? = null
        filePaths.forEach { filePath ->
            currentCoroutineContext().ensureActive()
            lastBook = dispatch(filePath, onUnknownFile, navigate = false) ?: lastBook
        }
        lastBook?.let { openBook(it) }
    }

    /**
     * 仅判定是否属于“未知文件”（需用户确认），不执行任何导入。
     *
     * 供宿主在协调块外等待用户确认：确认后再重新取得有效访问地址并 [dispatch]，
     * 避免外部文件读协调在等用户回答期间一直占着。
     */
    suspend fun isUnknownFile(filePath: String): Boolean {
        val path = filePath.toLocalFilePath()
        val fileName = path.fileName()
        if (fileName.matches(AppPattern.archiveFileRegex)) return false
        if (VideoDirect.targetFor(path, title = fileName) != null) return false
        if (fileName.matches(AppPattern.bookFileRegex)) return false
        return !runCatching { probeIsJson(path) }.getOrDefault(false)
    }

    /** 单文件分发 (对照原版 dispatch: 先试视频, 再试 JSON, 再试书籍文件, 其余待用户确认)。 */
    private suspend fun dispatchFile(
        path: String,
        onUnknownFile: suspend (fileName: String) -> Boolean,
        navigate: Boolean = true,
    ): Book? {
        val fileName = path.fileName()
        // 视频文件优先判定: 它们既不是 JSON 也不是书, 且动辄上百 MB —— 不能先走 JSON 探字节
        // 再落回“不支持”。判据与 Android/iOS/鸿蒙 入口共用 [VideoDirect] 一份。
        VideoDirect.targetFor(path, title = fileName)?.let { target ->
            // getOrNull: 文件关联可能在 UI 就绪前就把路径投进来 (navigator 未注册)
            AppNavigatorProviders.getOrNull()?.push(AppRoute.VideoPlay(target))
            return null
        }
        // 先试 JSON: 与 app 端 InputStream.isJson() 一样只探首尾 128 字节, 命中才全量读
        // (epub/pdf 可能上百 MB, 不能为了判定就整份读进内存)
        val isJson = runCatching { probeIsJson(path) }
            .onFailure { AppLog.put("尝试导入为JSON文件失败\n${it.message}", it) }
            .getOrDefault(false)
        if (isJson) {
            val json = runCatching { FileSystem.SYSTEM.read(path.toPath()) { readUtf8() } }
                .onFailure { AppLog.put("尝试导入为JSON文件失败\n${it.message}", it) }
                .getOrNull()
            if (json != null) {
                if (dispatchJson(json)) return null
                // 嗅探为 JSON 但业务类型未知: 明确报告格式错误并终止, 严禁继续向下命中文本书籍正则当成小说导入
                Toasters.get().toast(syncGetString("wrong_format"))
                AppLog.put("文件关联导入: 格式不对 (未知 JSON 业务类型) $path")
            }
            // 读过就当处完了: 报错已报 (或读取失败已落日志), 不得再往下走书籍分支
            return null
        }

        if (fileName.matches(AppPattern.bookFileRegex)) {
            return importAsBook(path, navigate)
        }
        // 未知文件: 待当前窗口确认, 确认后走既有本地书导入 (importLocalFile 失败时报
        // "无法打开文件"), 拒绝则取消本次导入 (不静默也不报错)
        return if (onUnknownFile(fileName)) {
            importAsBook(path, navigate)
        } else {
            AppLog.put("文件关联导入: 用户取消导入未知文件 $path")
            null
        }
    }

    /** 既有本地书导入链 (书架正则命中与未知文件确认后共用; 对照原版 dispatchIntent 失败出口)。 */
    private suspend fun importAsBook(path: String, navigate: Boolean = true): Book? {
        val book = runCatching {
            FileBook.importLocalFile(path, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            // 对照原版 FileAssociationViewModel.dispatchIntent 的失败出口文案: 无法打开文件 + 原因
            Toasters.get().toast("无法打开文件\n${e.message ?: e}")
            AppLog.put("导入关联书籍失败: $path", e)
            return null
        }
        if (navigate) openBook(book)
        return book
    }

    /**
     * JSON 文本嗅探类型后交 deep link 宿主导入 (Import*ViewModelShared 的
     * importSource/import 同时接受 URL 与纯 JSON 文本, 这里直接喂文本)。
     *
     * @return true 已识别为受支持的导入类型; false 格式不对, 由调用方继续按书籍文件尝试
     */
    private fun dispatchJson(json: String): Boolean {
        val type = detectJsonType(json) ?: return false
        LegadoDeepLinkHandler.enqueue(
            DeepLinkImportRequest(type.toDeepLinkImportType(), json)
        )
        return true
    }

    /**
     * 只读首尾各 128 字节判断是否 JSON (对照原版 `InputStream.isJson()` 的探测策略:
     * 取头 128 字节 + 尾 128 字节拼起来判 `{...}` / `[...]`)。
     *
     * 读字节以 [FileHandle.read] 的返回值为准 (实际读到的长度, 不假定读满):
     * 文件在取 size 与读取之间被截短时按实读长度解码。
     */
    private fun probeIsJson(path: String): Boolean {
        val handle = FileSystem.SYSTEM.openReadOnly(path.toPath())
        try {
            val size = handle.size()
            val probe = if (size <= 256L) {
                // 小文件直接整读
                val buffer = ByteArray(size.toInt())
                val read = handle.read(0L, buffer, 0, buffer.size)
                if (read <= 0) "" else buffer.decodeToString(0, read)
            } else {
                val head = ByteArray(128)
                val headRead = handle.read(0L, head, 0, head.size)
                val tail = ByteArray(128)
                val tailRead = handle.read(size - 128L, tail, 0, tail.size)
                head.decodeToString(0, headRead.coerceAtLeast(0)).trim() +
                    tail.decodeToString(0, tailRead.coerceAtLeast(0)).trim()
            }
            return probe.isJson()
        } finally {
            handle.close()
        }
    }

    private fun openBook(book: Book) {
        // getOrNull: 文件关联可能在 UI 就绪前把系统传来的文件路径投进来
        AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
    }

    private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')
}

/**
 * 文件路径或 file:// 载荷 → 待导入文本。
 *
 * 导入入口 (Import*ViewModelShared 的 importSource/import) 拿到的可能是 URL、纯 JSON 文本,
 * 也可能是 `file://` 地址 (外部打开/深链/在线导入框粘贴路径)。本函数把地址读成本地文本,
 * 对齐原 app 端 `text.isUri() -> text.toUri().readText(appCtx)` 的兜底语义:
 * - 非 `file:` 开头 → 原样返回, 不进 IO;
 * - `file:` 开头 → 读文本后返回, 读失败抛 [NoStackTraceException] (带路径与原异常信息),
 *   由调用方 onError 推到错误流, 用户能看到是哪个文件读不了。
 *
 * 调用方 (各 VM 的 importSource/import) 均在 IO 调度器上执行, 本函数不切线程。
 */
fun readImportPayload(text: String): String =
    if (!text.startsWith("file:", ignoreCase = true)) text
    else {
        val path = text.toLocalFilePath()
        try {
            BackupFileOps.readText(path)
        } catch (e: Exception) {
            throw NoStackTraceException("文件读取失败: $path (${e.message})")
        }
    }

/**
 * 将文件路径或 file:// URL 转为本地绝对路径。
 * - 支持标准 RFC 8089 file: URI (file:///..., file://localhost/..., file:/...)
 * - file://server/share/... 还原为网络主机路径: 目录分隔符为反斜杠的平台 (Windows) 输出
 *   原生 UNC 形态 —— okio 的 UNC 判定只认反斜杠双前缀, 正斜杠双前缀被折叠成当前盘符
 *   根路径后即指向不存在的位置; 其余平台维持 `//server/share/...` 原样
 * - 补全 URL percent-decoding (如 %20、中文等 UTF-8 编码路径)
 * - Windows 盘符前导斜杠剥离 (如 /C:/... → C:/...)
 */
fun String.toLocalFilePath(): String {
    if (!startsWith("file:", ignoreCase = true)) return this
    val rawAfterScheme = substring(5).percentDecode()
    val path = when {
        rawAfterScheme.startsWith("///") || rawAfterScheme.startsWith("\\\\\\") ->
            rawAfterScheme.substring(2)

        rawAfterScheme.startsWith("//") || rawAfterScheme.startsWith("\\\\") -> {
            val slashIdx = rawAfterScheme.indexOfAny(charArrayOf('/', '\\'), startIndex = 2)
            if (slashIdx < 0) {
                rawAfterScheme.substring(2)
            } else {
                val authority = rawAfterScheme.substring(2, slashIdx)
                val rest = rawAfterScheme.substring(slashIdx)
                if (authority.isEmpty() || authority.equals("localhost", ignoreCase = true)) {
                    rest
                } else if (Path.DIRECTORY_SEPARATOR == "\\") {
                    "\\\\$authority${rest.replace('/', '\\')}"
                } else {
                    "//$authority$rest"
                }
            }
        }

        else -> rawAfterScheme
    }
    return if (path.length >= 3 &&
        (path[0] == '/' || path[0] == '\\') &&
        (path[1] in 'a'..'z' || path[1] in 'A'..'Z') &&
        path[2] == ':' &&
        (path.length == 3 || path[3] == '/' || path[3] == '\\')
    ) {
        path.substring(1)
    } else {
        path
    }
}

/**
 * 百分号解码 (如 `%20`、中文等 UTF-8 编码路径段)。
 *
 * 各处 URI → 本地路径的转换共用这一份 (shared 侧的 [toLocalFilePath], 鸿蒙侧系统投递/选择器
 * 给出的授权体 URI 折回): 残缺的 `%` 序列按原样保留, 不进解码。
 */
internal fun String.percentDecode(): String {
    if (!contains('%')) return this
    val sb = StringBuilder(length)
    val byteBuf = ArrayList<Byte>()
    fun flushBytes() {
        if (byteBuf.isNotEmpty()) {
            sb.append(byteBuf.toByteArray().decodeToString())
            byteBuf.clear()
        }
    }

    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '%' && i + 2 < length) {
            val hi = this[i + 1].digitToIntOrNull(16)
            val lo = this[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                byteBuf.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        flushBytes()
        sb.append(c)
        i++
    }
    flushBytes()
    return sb.toString()
}
