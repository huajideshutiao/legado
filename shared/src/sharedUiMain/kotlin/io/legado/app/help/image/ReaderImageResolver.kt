package io.legado.app.help.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.read.page.provider.ImageResolver
import io.legado.app.ui.book.read.page.provider.ImageResolverProviders
import io.legado.app.ui.book.read.page.provider.ImageSize
import io.legado.app.utils.InputStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * 阅读页内嵌图片的位图缓存（对照 app 端 `ImageProvider.bitmapLruCache`）。
 *
 * 排版只经 [sizeOf] 取宽高（先读图片头，读不出才整解码，对照原版 `inJustDecodeBounds`），
 * 位图在绘制时按需加载并留在 LRU 里，上限沿用 `AppConfig.bitmapCacheSize`（MB，默认 50）。
 *
 * 单本书作用域：[bind] 传入新的 bookUrl 时清空，避免 PDF 这类以页码（`0`/`1`…）作 src
 * 的书跨书撞 key。
 */
object ReaderImageCache {

    /** 取图失败时的占位尺寸（对照原版返回 `errorBitmap` 宽高，让排版仍产出图片行）。 */
    private const val PLACEHOLDER_SIZE = 512

    private val lock = SynchronizedObject()

    /** 手写 LRU：命中时先 remove 再 put 把条目挪到队尾，超预算从队首淘汰。 */
    private val bitmaps = LinkedHashMap<String, ImageBitmap>()
    private var cachedBytes = 0L
    private val sizes = HashMap<String, ImageSize>()
    private val failed = HashSet<String>()
    private val inFlight = HashSet<String>()

    private var boundBookUrl: String? = null
    private var loader: (suspend (String) -> ByteArray?)? = null

    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /**
     * 位图就绪计数：绘制层（`PageContentCanvas`）在 Canvas 里读它建立快照订阅，
     * 异步加载完成后自增触发该页重绘。
     */
    var version by mutableIntStateOf(0)
        private set

    private val maxBytes: Long
        get() = runCatching { AppConfigProviders.get().bitmapCacheSize }
            .getOrDefault(50).coerceIn(1, 1024) * 1024L * 1024L

    /**
     * 绑定当前书的字节加载器；换书时清空缓存。
     * 同一本书三章滑窗会各建一个 resolver 重复绑定，取图路径只由 book + src 决定，覆盖无副作用。
     */
    fun bind(bookUrl: String, loader: suspend (String) -> ByteArray?) {
        synchronized(lock) {
            if (boundBookUrl != bookUrl) {
                bitmaps.clear()
                sizes.clear()
                failed.clear()
                cachedBytes = 0
                boundBookUrl = bookUrl
            }
            this.loader = loader
        }
    }

    /** 已就绪位图；未加载 / 已淘汰返回 null。 */
    fun peek(src: String): ImageBitmap? = synchronized(lock) {
        bitmaps.remove(src)?.also { bitmaps[src] = it }
    }

    /** 取图失败（画错误占位，不再重试）。 */
    fun isFailed(src: String): Boolean = synchronized(lock) { failed.contains(src) }

    /**
     * 排版取尺寸：命中尺寸缓存直接返回；否则取字节，先解析图片头，
     * 头解析不出（如 SVG / 少见格式）才整解码并把位图留在 LRU。
     * 取不到图返回 [PLACEHOLDER_SIZE] 方块（对照原版 errorBitmap 尺寸）。
     */
    suspend fun sizeOf(src: String): ImageSize {
        synchronized(lock) { sizes[src] }?.let { return it }
        val size = resolveSize(src) ?: ImageSize(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE)
        synchronized(lock) { sizes[src] = size }
        return size
    }

    private suspend fun resolveSize(src: String): ImageSize? {
        peek(src)?.let { return ImageSize(it.width, it.height) }
        val bytes = loadBytes(src) ?: return null
        probeImageSize(bytes)?.let { return it }
        val bitmap = decode(src, bytes) ?: return null
        return ImageSize(bitmap.width, bitmap.height)
    }

    /** 绘制层发现位图未就绪时异步加载，完成后自增 [version] 触发重绘。 */
    fun requestAsync(src: String) {
        synchronized(lock) {
            if (bitmaps.containsKey(src) || failed.contains(src) || inFlight.contains(src)) return
            inFlight.add(src)
        }
        scope.launch {
            try {
                val bytes = loadBytes(src)
                if (bytes == null) {
                    synchronized(lock) { failed.add(src) }
                } else {
                    decode(src, bytes)
                }
            } finally {
                synchronized(lock) { inFlight.remove(src) }
            }
        }
    }

    private suspend fun loadBytes(src: String): ByteArray? {
        val load = synchronized(lock) { loader } ?: return null
        return runCatching { load(src) }.onFailure {
            AppLog.put("阅读页图片加载失败 $src\n${it.message}", it)
        }.getOrNull()
    }

    private fun decode(src: String, bytes: ByteArray): ImageBitmap? {
        val bitmap = runCatching { bytes.decodeToImageBitmap() }.getOrNull()
        synchronized(lock) {
            if (bitmap == null) {
                failed.add(src)
            } else {
                failed.remove(src)
                put(src, bitmap)
            }
        }
        if (bitmap != null) version++
        return bitmap
    }

    /** 调用方持锁。 */
    private fun put(src: String, bitmap: ImageBitmap) {
        bitmaps.remove(src)?.let { cachedBytes -= it.byteSize() }
        bitmaps[src] = bitmap
        cachedBytes += bitmap.byteSize()
        val limit = maxBytes
        val iterator = bitmaps.entries.iterator()
        // 单张图超预算时保留自身（原版 ensureLruCacheSize 同样扩容而非丢弃当前图）
        while (cachedBytes > limit && bitmaps.size > 1 && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == src) continue
            cachedBytes -= entry.value.byteSize()
            iterator.remove()
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L

    fun clear() {
        synchronized(lock) {
            bitmaps.clear()
            sizes.clear()
            failed.clear()
            cachedBytes = 0
        }
    }
}

/**
 * 只读图片头取宽高（PNG / JPEG / GIF / BMP / WEBP），对应原版 `BitmapFactory.Options
 * .inJustDecodeBounds = true`——排版拿到宽高即可算行高，不必整解码占内存。
 * 无法识别返回 null，由调用方回落整解码。
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
    return null
}

/**
 * 共享阅读器的 [ImageResolver] 实现（sharedUiMain，四端共用）。
 *
 * 取图顺序对照 app 端 `ImageProvider.cacheImage`：
 * 1. 本地书（PDF / EPUB / CBZ，含 WebDav 下载件）：[FileBook.getImage] 直接取流
 *    （桌面 PDF 的页面渲染与图片缓存写入在 `DesktopPdfFile.getImage` 内部完成）
 * 2. 网络书：先查图片磁盘缓存（[BookImageStorageProviders]），未命中用 [AnalyzeUrlCore]
 *    带书源 header/cookie/JS 下载并写入缓存
 *
 * 解码统一走 compose resources 的 `decodeToImageBitmap`（JPEG/PNG/BMP/WEBP，四端一致）。
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
        val storage = runCatching { BookImageStorageProviders.get() }.getOrNull()
        // 磁盘缓存命中：经平台图片加载器读文件字节（Android 端该实现为 stub，退化为重新下载）
        storage?.let { s ->
            runCatching { s.getImagePath(book, chapter, src) }.getOrNull()?.let { path ->
                ImageBitmapLoader().loadBytes(path, book, bookSource)
                    ?.let { return@withContext it }
            }
        }
        val downloaded = runCatching {
            AnalyzeUrlCore(
                rawUrl = src,
                source = bookSource,
                coroutineContext = currentCoroutineContext(),
            ).getByteArrayAwait()
        }.getOrNull() ?: return@withContext null
        storage?.let { runCatching { it.saveImage(book, chapter, src, downloaded) } }
        downloaded
    }
}

/** 读全流并关闭（commonMain 的 [InputStream] 门面没有 kotlin.io 的 readBytes 扩展）。 */
private fun InputStream.readAllAndClose(): ByteArray {
    try {
        var buffer = ByteArray(64 * 1024)
        var size = 0
        while (true) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            val read = read(buffer, size, buffer.size - size)
            if (read <= 0) break
            size += read
        }
        return buffer.copyOf(size)
    } finally {
        runCatching { close() }
    }
}

/** 宿主启动时注册共享图片解析器（desktop `Main.kt` / Android `MainActivity`）。 */
fun registerReaderImageResolver() {
    ImageResolverProviders.register { book, chapter, bookSource ->
        ReaderImageResolver(book, chapter, bookSource)
    }
}
