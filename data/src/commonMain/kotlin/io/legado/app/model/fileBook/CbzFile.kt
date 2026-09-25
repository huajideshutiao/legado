package io.legado.app.model.fileBook

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.FileUtilsCommon
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.File
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readAllAndClose
import io.legado.app.utils.textCharsetCodec
import io.legado.app.utils.toJson
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * CBZ/ZIP 漫画文件解析 (commonMain 四端共用)。
 *
 * 原实现放 jvmAndAndroidMain, 理由是"ios/ohos 无本地 CBZ 导入需求"——该前提已被推翻
 * (iOS/鸿蒙确实要能打开外部投递的 .cbz), 故按原设计意图下沉到 commonMain, 四端共用一份,
 * 而不是在 native 另写一份重复实现。
 *
 * # 平台差异的收敛方式 (全部走既有 provider/门面, 无 expect/actual 分叉)
 * - `android.graphics.Bitmap` / `BitmapFactory` → [BitmapProviders] (app: BitmapFactory;
 *   桌面: Skia; native: UIImage / PixelMap)
 * - `android.os.ParcelFileDescriptor` → [ZipFileWrapperFactory.CreateResult.closeable]
 *   (`AutoCloseable`, 兼容 PFD 与 native 资源)
 * - `BookHelp.cachePath` / `FileBook.getCoverPath` → [BookHelpProviders]
 * - `ZipFileWrappers.create` → [ZipFileWrapperFactoryProviders.get().create]
 * - `java.nio.charset.Charset` / `com.fleeksoft.charset` → [textCharsetCodec] 门面
 * - `java.io.File` → [io.legado.app.utils.File] 门面
 * - `@Synchronized` → [SynchronizedObject] (Kotlin/Native 无 JVM 注解)
 *
 * 跨模块同包名同签名, 各端消费方 (FileBook / LifecycleHelp / ReadMangaActivity / 各端
 * getHandler) import 零改动。
 */
class CbzFile(var book: Book) {

    companion object : BaseFileBook {
        private val lock = SynchronizedObject()

        @Volatile
        private var eFile: CbzFile? = null

        private fun getEFile(book: Book): CbzFile = synchronized(lock) {
            eFile?.takeIf { it.book.bookUrl == book.bookUrl } ?: run {
                eFile?.close()
                CbzFile(book).also { eFile = it }
            }
        }.apply { this.book = book }

        override fun getChapterList(book: Book) = getEFile(book).getChapterList()

        override fun getContent(book: Book, chapter: BookChapter) =
            getEFile(book).getContent(chapter)

        override fun upBookInfo(book: Book) = synchronized(lock) {
            getEFile(book).upBookInfo()
        }

        override fun getImage(book: Book, href: String) =
            getEFile(book).zipFile?.run { getEntry(href)?.let { getInputStream(it) } }

        override fun clear() = synchronized(lock) {
            eFile?.close(); eFile = null
        }
    }

    /**
     * 随 [zipFile] 一起关闭的附加资源 (Android ParcelFileDescriptor / 其他端为 null)。
     *
     * 原字段类型 `ParcelFileDescriptor?` 改为 `AutoCloseable?` 以跨平台
     * (ParcelFileDescriptor 实现 Closeable, 兼容)。
     */
    @Volatile
    private var closeable: AutoCloseable? = null

    @Volatile
    private var zipFile: ZipFileWrapper? = null
        get() = field ?: synchronized(instanceLock) {
            field ?: ZipFileWrapperFactoryProviders.get().create(book)?.let { result ->
                closeable = result.closeable
                result.wrapper.also { field = it }
            }
        }

    private val instanceLock = SynchronizedObject()

    @Volatile
    private var imageEntries: List<ZipEntry>? = null
        get() = field ?: synchronized(instanceLock) {
            field ?: initImageEntries()?.also { field = it }
        }

    private var imageEntriesByChapter: Map<String, List<ZipEntry>>? = null

    private fun getChapters(): Map<String, List<ZipEntry>> {
        imageEntries
        return imageEntriesByChapter ?: emptyMap()
    }

    private fun initImageEntries(): List<ZipEntry>? {
        val cachePath = FileUtilsCommon.getPath(
            BookHelpProviders.get().cachePath, book.getFolderName(), "cbz_images.json"
        )
        val cache = runCatching {
            FileUtilsCommon.readBytes(cachePath)?.decodeToString()
                ?.let { GSON.fromJsonObject<ZipImageCache>(it).getOrNull() }
        }.getOrNull()

        val res = if (cache != null) {
            (zipFile as? RemoteZipWrapper)?.restore(
                cache.eocdOffset, cache.centralOffset, cache.entries
            )
            cache.entries
        } else {

            zipFile?.run {
                runCatching {
                    entries().asSequence().filter {
                        !it.isDirectory && AppPattern.imgFileRegex.matches(it.name)
                    }.sortedWith(compareBy(AlphanumComparator) { it.name }).toList()
                }.onFailure { AppLog.put("读取Cbz图片列表失败\n${it.message}", it) }
                    .getOrNull()
            }?.also { entries ->
                saveCache(cachePath, entries)
            }
        }

        return res?.also {
            imageEntriesByChapter =
                it.groupBy { entry -> entry.name.replace("\\", "/").substringBeforeLast("/", "") }
        }
    }

    private fun saveCache(cachePath: String, entries: List<ZipEntry>) {
        val rzf = zipFile as? RemoteZipWrapper
        val newCache = ZipImageCache(
            entries = entries.map {
                it.copy(entryOffset = rzf?.getEntryOffset(it.name) ?: 0)
            }, eocdOffset = rzf?.eocdOffset ?: 0, centralOffset = rzf?.centralOffset ?: 0
        )
        runCatching {
            FileUtilsCommon.writeBytes(cachePath, GSON.toJson(newCache).encodeToByteArray())
        }
    }

    /**
     * 探测 cbz 内条目名编码; 探测结果必须在当前平台可解码, 否则回落 UTF-8。
     *
     * 兜底必要性: 探测出的名字会写进 `book.charset` 落库, 而 [TextFileCore] 用
     * `textCharsetCodec(book.charset)` 裸调 (无 runCatching), 平台不支持的名字会抛异常;
     * 原 jvm 版用 `runCatching { Charset.forName(it) }` 做同样的兜底。
     */
    private fun getZipCharsetName(): String {
        // 条目名按 ISO-8859-1 逐字节映射存于 String, 探测编码时需先还原原始字节
        val detected = book.charset ?: imageEntries?.asSequence()?.map { it.name }
            ?.filter { it.any { c -> c.code > 127 } }?.take(5)?.joinToString("")
            ?.takeIf { it.isNotEmpty() }
            ?.let { EncodingDetect.getEncode(it.toLatin1Bytes()) }

        return detected?.takeIf { runCatching { textCharsetCodec(it) }.isSuccess } ?: "UTF-8"
    }

    fun close() = synchronized(instanceLock) {
        zipFile?.close()
        runCatching { closeable?.close() }
        zipFile = null
        closeable = null
        imageEntries = null
        imageEntriesByChapter = null
    }

    private fun upBookInfo() {
        val zf = zipFile ?: run { eFile = null; book.intro = "书籍导入异常"; return }
        if (book.coverUrl.isNullOrEmpty()) book.coverUrl = BookHelpProviders.get().getCoverPath(book.bookUrl)
        if (book.name.isBlank()) book.name = book.originName.substringBeforeLast(".")
        if (book.charset.isNullOrEmpty()) book.charset = getZipCharsetName()

        (zf as? RemoteZipWrapper)?.apply {
            imageEntries
            preload()
            book.variable = "cbz:$eocdOffset,$centralOffset,$fileSize"
        }
        book.wordCount = "${imageEntries?.size ?: 0}页"
        extractCover(zf)
        parseComicInfo(zf)
    }

    /**
     * 提取封面: 取首张图片解码压缩为 JPEG 写入 [book.coverUrl]。
     *
     * 原实现用 `android.graphics.BitmapFactory.decodeStream` + `Bitmap.compress`,
     * 下沉后改用 [BitmapProviders] 接口 (app: BitmapFactory; 桌面: Skia; native: 平台解码),
     * 行为一致, 仅多一层 provider 间接。
     */
    private fun extractCover(zf: ZipFileWrapper) {
        val coverUrl = book.coverUrl ?: return
        // coverUrl 是落库存储引用 (桌面端 oldCovers/ 相对引用), 读写文件前先解析为本地路径
        val coverPath = resolveStoredLocalPath(coverUrl)
        if (FileUtilsCommon.exist(coverPath)) return
        imageEntries?.firstOrNull()?.let { entry ->
            runCatching {
                val input = zf.getInputStream(entry) ?: return@runCatching
                try {
                    BitmapProviders.get().decodeStreamAndCompressToJpeg(input, File(coverPath), 90)
                } finally {
                    runCatching { input.close() }
                }
            }
        }
    }

    private fun parseComicInfo(zf: ZipFileWrapper) {
        val entry = zf.getEntry("ComicInfo.xml") ?: return
        runCatching {
            val input = zf.getInputStream(entry) ?: return@runCatching
            val xml = try {
                input.readAllAndClose().decodeToString()
            } finally {
                runCatching { input.close() }
            }
            val doc = Ksoup.parse(xml, parser = Parser.xmlParser())

            fun field(tag: String) =
                doc.selectFirst(tag)?.text()?.trim()?.takeUnless { it.isBlank() }

            val title = field("Title")
            val series = field("Series")
            val volume = field("Volume")
            val number = field("Number")
            (title ?: buildString {
                series?.let(::append)
                volume?.let { append(" Vol.").append(it) }
                number?.let { append(" #").append(it) }
            }.takeIf { it.isNotBlank() })?.let { book.name = it }

            (field("Writer")
                ?: field("Penciller")
                ?: field("Inker")
                ?: field("CoverArtist"))
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("\n")
                ?.takeUnless { it.isBlank() }
                ?.let { book.author = it }

            field("Summary")?.let { book.intro = it }

            val date = listOfNotNull(field("Year"), field("Month"), field("Day"))
                .joinToString("-").takeIf { it.isNotBlank() }
            val kinds = listOfNotNull(
                field("Genre"),
                field("Tags"),
                field("Characters"),
                field("Teams"),
                field("Publisher"),
                field("LanguageISO"),
                date,
            ).flatMap { it.split(",", "，", ";").map(String::trim) }
                .filter { it.isNotBlank() }
                .distinct()
            if (kinds.isNotEmpty()) book.kind = kinds.joinToString(",")
        }.onFailure { AppLog.put("解析ComicInfo.xml失败\n${it.message}", it) }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapters = getChapters()
        if (chapters.isEmpty()) return arrayListOf()
        val charsetName = getZipCharsetName()
        book.totalChapterNum = chapters.size

        val keys = chapters.keys.sortedWith(AlphanumComparator)
        val commonPrefix = keys.firstOrNull()?.substringBeforeLast("/", "")
            ?.takeIf { prefix -> prefix.isNotEmpty() && keys.all { it.startsWith("$prefix/") } }
            ?.let { "$it/" } ?: ""

        return keys.mapIndexedTo(ArrayList()) { i, f ->
            val displayTitle = if (commonPrefix.isNotEmpty() && f.startsWith(commonPrefix)) {
                f.substring(commonPrefix.length)
            } else f

            BookChapter(
                url = f,
                title = if (displayTitle.isNotEmpty()) {
                    // zip 条目名按 ISO-8859-1 字节存入, 需按探测到的字符集还原显示名
                    decodeEntryName(displayTitle, charsetName)
                } else "正文",
                bookUrl = book.bookUrl,
                index = i,
            )
        }
    }

    /** 按 [charsetName] 还原 zip 条目显示名; 字符集不支持时回落原串 (不因解码失败丢章节)。 */
    private fun decodeEntryName(name: String, charsetName: String): String = runCatching {
        val codec = textCharsetCodec(charsetName)
        codec.decode(name.toLatin1Bytes())
    }.getOrDefault(name)

    /**
     * String → ISO-8859-1 字节 (逐字符低 8 位)。
     *
     * zip 条目名在 [LocalZipWrapper] / [NativeZipFileWrapper] 里就是按 ISO-8859-1 解出的
     * (与原版 `Charsets.ISO_8859_1` 一致), 故还原原始字节必须用同口径, 不能用 UTF-8。
     */
    private fun String.toLatin1Bytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }

    private fun getContent(chapter: BookChapter): String? {
        return getChapters()[chapter.url]?.joinToString("") { "<img src=\"${it.name}\"/>" }
    }
}
