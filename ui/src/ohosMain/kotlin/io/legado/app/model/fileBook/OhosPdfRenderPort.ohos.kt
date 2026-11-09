package io.legado.app.model.fileBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.DirUsage
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.file.OhosDirAuthorizations
import io.legado.app.napi.OhosNativeBridge
import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.ScreenInfoProviders
import io.legado.app.utils.inputStream
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable

/**
 * [PdfRenderPort] 鸿蒙实现: 官方 PDFKit (@hms.officeservice.pdfservice, API 12) 后端。
 *
 * 渲染口径: 宽度取已注册 provider 的屏幕宽 (内部含物理尺寸兜底)、高度按页面比例、
 * 白底合成与 JPEG 90 打包在 ArkTS 侧; 页图缓存走 [BookImageStorageProviders],
 * key 带宽度 (读=当前宽, 写=本次实际渲染宽), 换屏自然失效并按新宽自愈。
 * 定位复用 nativeMain [NativeFileBookAccessor.localFilePathOf], 授权口径与
 * OhosAuthorizedFileBookAccessor 一致 (临时许可直通, 其余激活 BookTree 后校验存在)。
 * 桥接: invokePdfSync → ArkTS PdfBridgeHandler (源代次通道, 旧代次请求由通道结算)。
 * 文档所有权: closeDocument 收到真实 ok 回执才清端口状态; SDK 释放失败留痕并保留
 * 状态 (closeFailed), openDocument 在关闭失败时拒绝打开新文档 (不覆盖未释放的旧文档)。
 */
object OhosPdfRenderPort : PdfRenderPort {

    /** 打开文档的页数缓存 (openDocument 成功时更新; 对齐 app 端 `pdfRenderer?.pageCount ?: 0`)。 */
    @Volatile
    private var pageCountCache: Int = 0

    /** 最近一次成功渲染的实际宽度 (缓存写入 key 口径; render 与 write 由同一全操作锁串行)。 */
    @Volatile
    private var renderedWidth: Int = 0

    /** 最近一次 closeDocument 是否失败 (true = 旧文档未真实释放, openDocument 拒绝覆盖)。 */
    @Volatile
    private var closeFailed: Boolean = false

    /** 定位复用单例 (localFilePathOf 无状态; 不按次构造)。 */
    private val locator = NativeFileBookAccessor()

    private val lock = SynchronizedObject()

    /** 渲染目标宽度 (已注册 provider 的屏幕宽; provider 内部含未注入物理尺寸的兜底)。 */
    private val targetWidth: Int
        get() = ScreenInfoProviders.get().screenWidthPx

    override fun openDocument(book: Book): Boolean = synchronized(lock) {
        // 端口契约: 已打开时先关旧文档; 关闭失败 (旧文档未真实释放) 拒绝打开新文档,
        // 不在未释放的旧文档上覆盖开新书
        closeDocument()
        if (closeFailed) return false
        val path = resolvePdfPath(book) ?: return false
        val payload = KS_JSON.encodeToString(PdfOpenPayload(path = path))
        val resp = OhosNativeBridge.invokePdfSync("openDocument", payload) ?: return false
        val result = runCatching {
            KS_JSON.decodeFromString(PdfOpenResponse.serializer(), resp.json)
        }.getOrNull()
        if (result == null || !result.ok || result.pageCount == null) {
            AppLog.put("读取PDF文件失败\n${result?.error ?: resp.json}")
            return false
        }
        pageCountCache = result.pageCount
        true
    }

    override fun closeDocument() {
        val resp = runCatching { OhosNativeBridge.invokePdfSync("closeDocument", "{}") }.getOrNull()
        val result = resp?.let {
            runCatching { KS_JSON.decodeFromString(PdfCloseResponse.serializer(), it.json) }.getOrNull()
        }
        if (result?.ok == true) {
            pageCountCache = 0
            renderedWidth = 0
            closeFailed = false
        } else {
            // SDK 释放失败: 端口状态保留 (文档未被真实关闭), openDocument 据此拒绝覆盖
            closeFailed = true
            AppLog.put("PDF 文档关闭失败\n${result?.error ?: resp?.json ?: "桥未响应"}")
        }
    }

    override fun pageCount(): Int = pageCountCache

    override fun renderPageJpegBytes(index: Int): ByteArray? = synchronized(lock) {
        val width = targetWidth
        val payload = KS_JSON.encodeToString(PdfRenderPayload(index = index, width = width))
        val resp = OhosNativeBridge.invokePdfSync("renderPage", payload) ?: return null
        val result = runCatching {
            KS_JSON.decodeFromString(PdfRenderResponse.serializer(), resp.json)
        }.getOrNull()
        val bytes = resp.bytes
        // 无真实 JPEG 字节 (空/缺失) 不能当成功, 按真实失败反馈
        if (result == null || !result.ok || bytes == null || bytes.isEmpty()) {
            AppLog.put("PDF 页面渲染失败\n第 $index 页: ${result?.error ?: resp.json}")
            return null
        }
        renderedWidth = width
        bytes
    }

    /** 读第 [index] 页缓存 (key 带当前渲染宽度); 命中直接读文件。 */
    override fun readCachedPage(book: Book, index: Int): InputStream? {
        val storage = BookImageStorageProviders.get()
        val cacheKey = pageCacheKey(index)
        val chapter = BookChapter(url = cacheKey, bookUrl = book.bookUrl)
        return storage.getImagePath(book, chapter, cacheKey)?.let { path ->
            File(path).takeIf { it.isFile }?.inputStream()
        }
    }

    /** 写第 [index] 页缓存 (key 取最近一次成功渲染的实际宽度; 骨架在成功渲染后调用)。 */
    override fun writeCachedPage(book: Book, index: Int, bytes: ByteArray) {
        val storage = BookImageStorageProviders.get()
        val cacheKey = "pdf_${index}_${renderedWidth}.jpg"
        val chapter = BookChapter(url = cacheKey, bookUrl = book.bookUrl)
        storage.saveImage(book, chapter, cacheKey, bytes)
    }

    override fun coverFilePath(book: Book): String? =
        book.coverUrl?.takeIf { it.isNotEmpty() }

    /**
     * bookUrl → 可读 POSIX 路径: 定位复用 nativeMain [NativeFileBookAccessor.localFilePathOf]
     * (FileBookOperationPath 协调旁路优先 → LocalBookLocators 可用引用 → bookUrl 剥离);
     * 授权先于存在性校验 (待激活的外部授权路径在激活前不可见): 临时许可直通, 其余激活
     * BookTree 持久授权; 不是存在的常规文件时返回 null, 不造路径。
     */
    private fun resolvePdfPath(book: Book): String? {
        val path = locator.localFilePathOf(book)
        if (!OhosTransientFileAccess.covers(path)) {
            OhosDirAuthorizations.ensureActivatedFor(path, DirUsage.BookTree)
        }
        val file = File(path)
        if (!file.isFile) {
            AppLog.put("读取PDF文件失败\n文件不存在: $path")
            return null
        }
        return path
    }

    /** 页图缓存 key (书内按 [index]+当前渲染宽度派生)。 */
    private fun pageCacheKey(index: Int): String = "pdf_${index}_$targetWidth.jpg"
}

/** openDocument 请求载荷 (Kotlin → ArkTS, 与 PdfBridgeHandler.ets 对齐)。 */
@Serializable
private data class PdfOpenPayload(val path: String)

/** renderPage 请求载荷: 页码 + 输出位图宽 (高按页面比例由 ArkTS 侧计算)。 */
@Serializable
private data class PdfRenderPayload(val index: Int, val width: Int)

/** closeDocument 响应。 */
@Serializable
private data class PdfCloseResponse(
    val ok: Boolean,
    val error: String? = null,
)

/** openDocument 响应: 成功带页数; 失败带可读原因与 PDFKit ParseResult 数值。 */
@Serializable
private data class PdfOpenResponse(
    val ok: Boolean,
    val pageCount: Int? = null,
    val error: String? = null,
    val parseResult: Int? = null,
)

/** renderPage 响应: 成功带实际输出位图尺寸 (JPEG 字节走桥数据面); 失败带原因。 */
@Serializable
private data class PdfRenderResponse(
    val ok: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val error: String? = null,
)
