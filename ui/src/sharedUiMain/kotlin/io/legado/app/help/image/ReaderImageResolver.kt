package io.legado.app.help.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookImageDownloadShared
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.ReaderImageCache.PLACEHOLDER_SIZE
import io.legado.app.help.image.ReaderImageCache.bind
import io.legado.app.help.image.ReaderImageCache.sizeOf
import io.legado.app.help.image.ReaderImageCache.version
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.read.page.provider.ImageResolver
import io.legado.app.ui.book.read.page.provider.ImageResolverProviders
import io.legado.app.ui.book.read.page.provider.ImageSize
import io.legado.app.utils.readAllAndClose
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 阅读页内嵌图片的位图缓存（对照 app 端 `ImageProvider.bitmapLruCache`）。
 *
 * 排版只经 [sizeOf] 取宽高（先读图片头，读不出才整解码，对照原版 `inJustDecodeBounds`），
 * 位图在绘制时按需加载，存放与字节预算归 [BodyImageStore] 所有（Android 与 web 出图共用同一
 * 份位图 LRU，其余三端是进程内字节 LRU）；设置改动由预算订阅即时裁剪。
 *
 * 单本书作用域：store 的 key 带 bookUrl 前缀，换书时只清本书条目，PDF 这类以页码（`0`/`1`…）
 * 作 src 的书不会跨书撞 key。
 */
object ReaderImageCache {

    /** 取图失败时的占位尺寸（对照原版返回 `errorBitmap` 宽高，让排版仍产出图片行）。 */
    private const val PLACEHOLDER_SIZE = 512

    /**
     * 阅读页内嵌图解码目标长边上限 (I2): 2000px 量级 ≈ 2× 典型页宽 (手机 1080p 物理宽 /
     * 桌面 1440p 窗口), 阅读器无图片放大路径, 采样对显示无感知差异; 解码内存/耗时按 ~1/4 计
     * (4000px 图 → 2048px), 采样因子取 2 的幂。原图字节保留, 未来加放大可再解全图。
     */
    private const val SAMPLE_MAX_DIM = 2048

    private val lock = SynchronizedObject()

    private val sizes = HashMap<String, ImageSize>()
    private val failed = HashSet<String>()

    private var boundBookUrl: String? = null
    private var loader: (suspend (String) -> ByteArray?)? = null

    /** 会话代号: 换书或清空时递增, 旧会话的请求结果一律作废。 */
    private var boundGeneration = 0

    /** [requestAsync] 的加载作用域: 与 [load] 同一加载与预算机制, 只是不等结果。 */
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /**
     * 位图就绪计数：绘制层（`PageContentCanvas`）在 Canvas 里读它建立快照订阅，
     * 新图进字节表后自增触发重绘。
     */
    var version by mutableIntStateOf(0)
        private set

    /**
     * 会话代号快照：换书/清空后自增。渲染位用它做 effect 键，
     * 会话一变就重新同步自己持有的图 (旧代的持有全部作废)。
     */
    var sessionToken by mutableIntStateOf(0)
        private set

    /**
     * 一次取图的身份: 请求开始时的书、字节加载器与会话代号。
     * 取数、解码、单飞、键、发布都受它约束, 换书/清空后的旧任务不能写进新书。
     */
    private class Session(
        val bookUrl: String,
        val loader: suspend (String) -> ByteArray?,
        val generation: Int,
    ) {
        val storePrefix: String get() = "$bookUrl\u0000"
        fun storeKey(src: String): String = "$storePrefix$src"

        /** 单飞键带会话代号: 同书 clear 后的新请求不会 await 旧会话的单飞结果。 */
        fun flightKey(src: String): String = "reader\u0000${generation}\u0000$storePrefix$src"

        /** 渲染持有集合的键: 带书、会话代号与 src, 换书/清空/PDF 页码 0 同 src 不会串图。 */
        fun renderKey(src: String): String = "${generation}\u0000$storePrefix$src"

        fun matches(boundBookUrl: String?, boundGeneration: Int): Boolean =
            bookUrl == boundBookUrl && generation == boundGeneration
    }

    private fun session(): Session? = synchronized(lock) {
        val bookUrl = boundBookUrl ?: return null
        val load = loader ?: return null
        Session(bookUrl, load, boundGeneration)
    }

    /** 调用方持锁: 该身份是否仍是当前会话。 */
    private fun isCurrentLocked(session: Session): Boolean =
        session.matches(boundBookUrl, boundGeneration)

    /**
     * 绑定当前书的字节加载器；换书时清掉本书的尺寸/失败记录与本书位图。
     * 同一本书三章滑窗会各建一个 resolver 重复绑定: 同书不换会话代号, 在途请求继续有效。
     *
     * 旧书条目的清理与换代在同一把锁内完成: 新代请求不可能拿到旧代条目,
     * A→B→A 的交错也不会误删新的 A 条目。
     */
    fun bind(bookUrl: String, loader: suspend (String) -> ByteArray?) {
        synchronized(lock) {
            if (boundBookUrl != bookUrl) {
                val previous = boundBookUrl
                sizes.clear()
                failed.clear()
                boundGeneration++
                sessionToken++
                boundBookUrl = bookUrl
                this.loader = loader
                if (previous != null) bodyImageStore().clearBook("$previous\u0000")
                return
            }
            this.loader = loader
        }
    }

    /**
     * 已进字节表的位图；未加载 / 已淘汰返回 null。
     *
     * 会话取得与正文表读取在同一把锁内: 换书/同书清空插不进两者之间, 不会读到旧书键下的图。
     */
    fun peek(src: String): ImageBitmap? = synchronized(lock) {
        val session = session() ?: return null
        bodyImageStore().peek(session.storeKey(src))
    }

    /**
     * 渲染持有集合用的键 (书 + 会话代号 + src); 未绑定书时返回 null。
     * 渲染位用它在持有集合里存/查自己的引用。
     */
    fun renderKey(src: String): String? = session()?.renderKey(src)

    /** 取图失败（画错误占位，不再重试）。 */
    fun isFailed(src: String): Boolean = synchronized(lock) { failed.contains(src) }

    /**
     * 排版取尺寸：命中尺寸缓存直接返回；否则取字节，先解析图片头，
     * 头解析不出（如 SVG / 少见格式）才整解码取尺寸。
     *
     * 尺寸按捕获到的会话身份存取: 命中读取与发布都在同一把锁内先核该身份仍是当前会话,
     * 清空换代插不进核验与取用之间, 旧排版不会拿新书/新代同 src 的尺寸。
     * 这里解出的图不交给任何显示位, 也不因它挂起等显示。
     */
    suspend fun sizeOf(src: String): ImageSize {
        val session = session() ?: return ImageSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE)
        val key = session.storeKey(src)
        // 身份核验与尺寸/位图读取在同一原子窗口: 清空换代插不进两者之间,
        // 旧需求不会拿到新代刚发布的尺寸或位图
        synchronized(lock) {
            if (!isCurrentLocked(session)) return ImageSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE)
            sizes[key]?.let { return it }
            bodyImageStore().peek(key)?.let { cached ->
                val size = ImageSize(cached.width, cached.height)
                sizes[key] = size
                return size
            }
        }
        val size = resolveSize(session, src) ?: return ImageSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE)
        // 尺寸发布同样受捕获身份约束: 取数期间换代就不发布、也不交给旧需求
        synchronized(lock) {
            if (!isCurrentLocked(session)) return ImageSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE)
            sizes[key] = size
        }
        return size
    }

    private suspend fun resolveSize(session: Session, src: String): ImageSize? {
        val bytes = loadBytes(session, src) ?: return null
        probeImageSize(bytes)?.let { return it }
        val bitmap = decodeImage(bytes) ?: return null
        return ImageSize(bitmap.width, bitmap.height)
    }

    /**
     * 取一张正文图供当前渲染位使用。
     *
     * 命中字节表直接返回；未命中经单飞解码（多个渲染位要同一张图只解一次）。
     * 返回的位图由调用方（当前渲染位）自己持有到离开可见区, 不论它是否在字节表里:
     * 字节表只是复用池, 会被缩容/其他消费者驱逐, 不能当显示引用。
     *
     * 请求身份在开始处固定：换书/清空后旧请求不写表、不通知、也不会把新书的同名图挡住。
     */
    suspend fun load(src: String): ImageBitmap? {
        val session = session() ?: return null
        return load(session, src)
    }

    /**
     * 按指定（已捕获）会话身份取图: 键、单飞、取数、发布全部用同一个身份。
     *
     * 正常取不到（loader 返回 null / 解码全部失败）在当前会话的原子边界内记成失败终态
     * 并通知重绘（显示位转错误图）, 不是“未就绪”的灰块状态。
     * 取消不是失败: 原样上抛。
     */
    private suspend fun load(session: Session, src: String): ImageBitmap? {
        if (src.isBlank()) return null
        val storeKey = session.storeKey(src)
        // 身份核验与缓存命中读取在同一原子窗口: 会话已变就不读 (也不把新代条目拿去用),
        // 清空换代插不进核验与取用之间
        synchronized(lock) {
            if (!isCurrentLocked(session)) return null
            if (failed.contains(src)) return null
            bodyImageStore().peek(storeKey)?.let { return it }
        }
        val bitmap = BookImageLoadDedup.singleFlight(session.flightKey(src)) {
            synchronized(lock) {
                if (!isCurrentLocked(session)) return@singleFlight null
                if (failed.contains(src)) return@singleFlight null
                bodyImageStore().peek(storeKey)?.let { return@singleFlight it }
            }
            val bytes = loadBytes(session, src)
            val decoded = bytes?.let { decodeImage(it) }
            if (decoded == null) {
                markFailed(session, src)
                return@singleFlight null
            }
            // 核验身份与写失败表/写缓存/自增版本在同一个原子边界里完成,
            // bind/clear 插不进来, 旧结果不会复活到已清的表上
            synchronized(lock) {
                if (!isCurrentLocked(session)) return@singleFlight null
                failed.remove(src)
                if (bodyImageStore().put(storeKey, decoded)) version++
            }
            decoded
        }
        if (bitmap == null) return null
        // 公共返回边界: 等待者可能等到胜者的结果后才被换代, 不能把旧代图交给新持有
        synchronized(lock) { return if (isCurrentLocked(session)) bitmap else null }
    }

    /** 取不到图的终态: 只对仍在的会话生效, 并通知显示位转错误图。 */
    private fun markFailed(session: Session, src: String) {
        synchronized(lock) {
            if (!isCurrentLocked(session)) return
            failed.add(src)
            version++
        }
    }

    /**
     * 把当前可见图集合同步到渲染位持有里。
     *
     * 会话身份只捕获一次: wanted 键、实际加载、发布核验用同一个身份,
     * 因此不会出现“旧 key 装新书的图”或“失效 key 留新图”。
     * 集合变化时先解除离开项的引用, 再为仍可见的源取引用（缓存命中也取）。
     */
    suspend fun syncVisible(
        sources: Set<String>,
        into: SnapshotStateMap<String, ImageBitmap>,
    ) = coroutineScope {
        val session = session() ?: return@coroutineScope
        val demands = sources.map { src -> session to src }
        val wanted = demands.mapTo(HashSet()) { (s, src) -> s.renderKey(src) }
        // 释放离开可见区的引用也只能落在同代: 旧代同步不得删新代的持有
        synchronized(lock) {
            if (!isCurrentLocked(session)) return@coroutineScope
            into.keys.retainAll(wanted)
        }
        for ((captured, src) in demands) {
            val key = captured.renderKey(src)
            if (into.containsKey(key)) continue
            launch {
                val bitmap = load(captured, src) ?: return@launch
                // 需求归属核验与写入显示持有必须在同一原子边界内,
                // 否则 clear/bind 能插在两者之间把旧图写进新持有
                synchronized(lock) {
                    if (isCurrentLocked(captured)) into[key] = bitmap
                }
            }
        }
    }

    /**
     * 请求把一张正文图加载进字节表 (原公开入口, 保持 Unit 签名)。
     * 与 [load] 同一个加载/预算机制, 只是不等结果: 结果进表后经 [version] 触发重绘。
     */
    fun requestAsync(src: String) {
        if (src.isBlank()) return
        scope.launch { load(src) }
    }

    /**
     * 加载字节。
     *
     * 调用方被取消 (如滚出可见区) 时必须原样重抛: 正常取消不是“取图失败”,
     * 不能进失败表把该图永久拉黑。真实 IO 失败才返回 null 交给调用方记失败。
     */
    private suspend fun loadBytes(session: Session, src: String): ByteArray? {
        try {
            return session.loader(src)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            AppLog.put("阅读页图片加载失败 $src\n${e.message}", e)
            return null
        }
    }

    private fun decodeImage(bytes: ByteArray): ImageBitmap? {
        // 按目标长边 [SAMPLE_MAX_DIM] 降采样解码 (各端采样点不同, 见 [decodeBytesSampled]),
        // 失败依次回落 SVG 兜底
        // (对齐原版 `decodeBitmap ?: SvgUtils.createBitmap`; 矢量按目标长边渲染) 与全尺寸
        // 解码 (格式兜底, 如桌面 ImageIO 无 reader 的 WEBP 走 skia decodeToImageBitmap)。
        return runCatching { decodeBytesSampled(bytes, SAMPLE_MAX_DIM) }.getOrNull()
            ?: runCatching { decodeSvgFallback(bytes, SAMPLE_MAX_DIM) }.getOrNull()
            ?: runCatching { bytes.decodeToImageBitmap() }.getOrNull()
    }

    /** 清本书的正文图与尺寸/失败记录; 同时作废在途请求。清理与换代同锁完成。 */
    fun clear() {
        synchronized(lock) {
            sizes.clear()
            failed.clear()
            boundGeneration++
            sessionToken++
            boundBookUrl?.let { bodyImageStore().clearBook("$it\u0000") }
        }
    }
}

/**
 * 只读图片头取宽高（PNG / JPEG / GIF / BMP / WEBP / SVG 常见写法），对应原版 `BitmapFactory.Options
 * .inJustDecodeBounds = true`——排版拿到宽高即可算行高，不必整解码占内存。
 * 无法识别返回 null，由调用方回落整解码（SVG 经 [decodeSvgFallback] 渲染后取位图尺寸）。
 */
internal fun probeImageSize(bytes: ByteArray): ImageSize? {
    fun be16(at: Int) = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
    fun be32(at: Int) = (be16(at) shl 16) or be16(at + 2)
    fun le16(at: Int) = ((bytes[at + 1].toInt() and 0xff) shl 8) or (bytes[at].toInt() and 0xff)
    fun le32(at: Int) = (le16(at + 2) shl 16) or le16(at)

    if (bytes.size < 16) return null
    // PNG: 8 字节签名 + IHDR(宽高各 4 字节大端)
    if (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
    ) {
        return if (bytes.size >= 24) ImageSize(be32(16), be32(20)) else null
    }
    // GIF: "GIF8" + 逻辑屏幕宽高(小端)
    if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte()
    ) {
        return ImageSize(le16(6), le16(8))
    }
    // BMP: "BM" + DIB header 宽高(小端, 高可能为负表示自顶向下)
    if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() && bytes.size >= 26) {
        return ImageSize(le32(18), kotlin.math.abs(le32(22)))
    }
    // WEBP: "RIFF"...."WEBP" + VP8 / VP8L / VP8X 三种块
    if (bytes.size >= 30 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte()
    ) {
        return when {
            bytes[15] == 'X'.code.toByte() ->
                if (bytes.size < 31) null
                else ImageSize((le32(24) and 0xffffff) + 1, (le32(27) and 0xffffff) + 1)
            bytes[15] == 'L'.code.toByte() -> {
                val bits = le32(21)
                ImageSize((bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
            }
            bytes[15] == ' '.code.toByte() -> ImageSize(le16(26) and 0x3fff, le16(28) and 0x3fff)
            else -> null
        }
    }
    // JPEG: 逐段跳到 SOFn(0xC0..0xCF, 排除 C4/C8/CC), 段内偏移 5/7 处为高/宽(大端)
    if (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
        var offset = 2
        while (offset + 9 < bytes.size) {
            if (bytes[offset] != 0xff.toByte()) {
                offset++
                continue
            }
            val marker = bytes[offset + 1].toInt() and 0xff
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) {
                offset += 2
                continue
            }
            val segmentLength = be16(offset + 2)
            if (segmentLength < 2) return null
            if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return ImageSize(be16(offset + 7), be16(offset + 5))
            }
            offset += 2 + segmentLength
        }
        return null
    }
    // SVG: 解析 <svg> 标签的 width/height / viewBox 拿固有宽高 (矢量无固定像素尺寸,
    // 宽高比供排版算行高; 实际渲染尺寸由 decodeSvgFallback 按目标长边等比缩放, 比例一致)
    probeSvgSize(bytes)?.let { return it }
    return null
}

/**
 * 只读 SVG 头取固有宽高（对应原版 `SvgUtils.getSize`: documentWidth/Height → viewBox 兜底）。
 *
 * 解析 `<svg` 标签内 width/height（数值+单位，百分比/auto 视为未指定）或 viewBox 后两值；
 * 解析不出返回 null（调用方回落整解码经 [decodeSvgFallback] 渲染后取位图尺寸）。
 * 仅做字符串扫描，不依赖平台 SVG 解析器，四端共用。
 */
private fun probeSvgSize(bytes: ByteArray): ImageSize? {
    // 只取文件头部 (XML 声明/注释/DOCTYPE 之后、<svg> 标签的属性不会超出前几 KB)
    val head = bytes.copyOf(minOf(bytes.size, 4096)).decodeToString()
    val tagStart = head.indexOf("<svg")
    if (tagStart < 0) return null
    val tagEnd = head.indexOf('>', tagStart)
    if (tagEnd < 0) return null
    val tag = head.substring(tagStart, tagEnd)

    // 取属性值 (双/单引号均可, 属性名不含正则特殊字符)
    fun attr(name: String): String? =
        Regex("""$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)?.trim()

    // 取数值前缀 (忽略 px/pt/em 等单位, 比例用途下可忽略单位换算误差)
    fun number(v: String?): Float? =
        v?.let { Regex("""^(\d+(?:\.\d+)?)""").find(it.trim())?.groupValues?.get(1)?.toFloatOrNull() }

    val rawW = attr("width")
    val rawH = attr("height")
    // 百分比/auto 视为未指定 (对应原版 SvgUtils.getSize: documentWidth 对百分比返回 0 再回落 viewBox)
    fun absolute(v: String?): Float? =
        v?.takeIf { !it.endsWith('%') && !it.equals("auto", ignoreCase = true) }?.let(::number)
    val absW = absolute(rawW)
    val absH = absolute(rawH)
    if (absW != null && absH != null && absW > 0 && absH > 0) {
        return ImageSize(absW.toInt().coerceAtLeast(1), absH.toInt().coerceAtLeast(1))
    }

    // viewBox="minX minY w h" (空白/逗号分隔)
    val vb = attr("viewBox")?.split(Regex("""[\s,]+""")) ?: emptyList()
    if (vb.size >= 4) {
        val w = vb[2].toFloatOrNull()
        val h = vb[3].toFloatOrNull()
        if (w != null && h != null && w > 0 && h > 0) {
            return ImageSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        }
    }
    return null
}

/**
 * 共享阅读器的 [ImageResolver] 实现（sharedUiMain，四端共用）。
 *
 * 取图顺序对照 app 端 `ImageProvider.cacheImage`：
 * 1. 本地书（PDF / EPUB / CBZ，含 WebDav 下载件）：[FileBook.getImage] 直接取流
 *    （桌面 PDF 的页面渲染与图片缓存写入在 PDF 渲染后端 DesktopPdfRenderPort 内部完成）
 * 2. 网络书：[BookImageDownloadShared.loadImageBytes]（磁盘缓存命中读缓存文件；未命中下载 →
 *    插件源分流 → 解密 → 校验 → 落盘），与章节图片批量下载同一条编排
 *
 * 解码走 [decodeBytesSampled] (I2 图片加载深度优化): 按 [SAMPLE_MAX_DIM] 长边上限解码前采样
 * (jvm ImageIO setSourceSubsampling / android inSampleSize / iOS Skia 后缩放 / ohos 全尺寸),
 * 采样失败依次回落 [decodeSvgFallback] (SVG 矢量按目标长边渲染, 对齐原版
 * `decodeBitmap ?: SvgUtils.createBitmap`) 与 compose resources `decodeToImageBitmap`
 * 全尺寸解码 (格式兜底如桌面 WEBP)。尺寸探测 [probeImageSize] 支持 SVG 头 (width/height/viewBox),
 * 排版拿固有宽高比算行高, 无需整解码。字节来源不变 (磁盘缓存的原始字节, 采样解码不回写缓存,
 * 未来加放大可再解全图)。
 */
class ReaderImageResolver(
    private val book: Book,
    private val chapter: BookChapter,
    private val bookSource: BookSource?,
) : ImageResolver {

    init {
        ReaderImageCache.bind(book.bookUrl) { src -> loadBytes(src) }
    }

    override suspend fun getImageSize(src: String): ImageSize = ReaderImageCache.sizeOf(src)

    private suspend fun loadBytes(src: String): ByteArray? = withContext(IoDispatcher) {
        if (src.isBlank()) return@withContext null
        if (book.isLocal) {
            return@withContext runCatching {
                FileBook.getImage(book, src)?.readAllAndClose()
            }.getOrNull()
        }
        BookImageDownloadShared.loadImageBytes(bookSource, book, src, chapter)
    }
}

/** 宿主启动时注册共享图片解析器（desktop `Main.kt` / Android `MainActivity`）并安装图片缓存预算订阅。 */
fun registerReaderImageResolver() {
    ImageResolverProviders.register { book, chapter, bookSource ->
        ReaderImageResolver(book, chapter, bookSource)
    }
    observeBitmapCacheBudget()
}
