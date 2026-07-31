package io.legado.app.model.fileBook

import com.fleeksoft.charset.Charsets
import com.fleeksoft.charset.toByteArray
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.serialization.encodeToString
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser
import java.io.File
import java.nio.charset.Charset

/**
 * CBZ/ZIP 漫画文件解析 (shared jvmAndAndroidMain 下沉版)。
 *
 * 原 app 端 `io.legado.app.model.fileBook.CbzFile` 依赖:
 * - `android.graphics.Bitmap` / `BitmapFactory` → [BitmapProviders] 接口注入
 *   (app: BitmapFactory; desktop: ImageIO)
 * - `android.os.ParcelFileDescriptor` → [ZipFileWrapperFactory.CreateResult.closeable]
 *   (AutoCloseable, 兼容 PFD / 其他平台资源)
 * - `BookHelp.cachePath` → [BookHelpProviders.get().cachePath]
 * - `FileBook.getCoverPath` → [BookHelpProviders.get().getCoverPath]
 * - `ZipFileWrappers.create` → [ZipFileWrapperFactoryProviders.get().create]
 *   (工厂由各平台注册: app 包装原 ZipFileWrappers; desktop 用 LocalZipWrapper)
 *
 * 其余依赖 (ZipFileWrapper / RemoteZipWrapper / ZipEntry / ZipImageCache /
 * AppLog / AppPattern / AlphanumComparator / EncodingDetect / GSON / Ksoup)
 * 已下沉 shared, 直接引用。
 *
 * 放 jvmAndAndroidMain 原因: 依赖 [ZipFileWrapper] / [RemoteZipWrapper] /
 * `java.io.File` / `java.nio.charset.Charset` (JVM 标准库), ios/ohos 无本地
 * CBZ 导入需求, 不下沉到 commonMain。
 *
 * 跨模块同包名同签名, app 端消费方 (FileBook / LifecycleHelp / ReadMangaActivity)
 * import 零改动。
 */
class CbzFile(var book: Book) {

    companion object : BaseFileBook {
        private var eFile: CbzFile? = null

        @Synchronized
        private fun getEFile(book: Book) =
            (eFile?.takeIf { it.book.bookUrl == book.bookUrl } ?: run {
                eFile?.close()
                CbzFile(book).also { eFile = it }
            }).apply { this.book = book }

        override fun getChapterList(book: Book) = getEFile(book).getChapterList()

        override fun getContent(book: Book, chapter: BookChapter) =
            getEFile(book).getContent(chapter)

        @Synchronized
        override fun upBookInfo(book: Book) = getEFile(book).upBookInfo()

        override fun getImage(book: Book, href: String) =
            getEFile(book).zipFile?.run { getEntry(href)?.let { getInputStream(it) } }

        override fun clear() {
            eFile?.close(); eFile = null
        }
    }

    /**
     * 随 [zipFile] 一起关闭的附加资源 (Android ParcelFileDescriptor / desktop 无)。
     *
     * 原字段类型 `ParcelFileDescriptor?` 改为 `AutoCloseable?` 以跨平台
     * (ParcelFileDescriptor 实现 Closeable, 兼容)。
     */
    @Volatile
    private var closeable: AutoCloseable? = null

    @Volatile
    private var zipFile: ZipFileWrapper? = null
        get() = field ?: synchronized(this) {
            field ?: ZipFileWrapperFactoryProviders.get().create(book)?.let { result ->
                closeable = result.closeable
                result.wrapper.also { field = it }
            }
        }

    @Volatile
    private var imageEntries: List<ZipEntry>? = null
        get() = field ?: synchronized(this) {
            field ?: initImageEntries()?.also { field = it }
        }

    private var imageEntriesByChapter: Map<String, List<ZipEntry>>? = null

    private fun getChapters(): Map<String, List<ZipEntry>> {
        imageEntries
        return imageEntriesByChapter ?: emptyMap()
    }

    private fun initImageEntries(): List<ZipEntry>? {
        val cacheFile = File(BookHelpProviders.get().cachePath, "${book.getFolderName()}/cbz_images.json")
        val cache = runCatching {
            if (cacheFile.exists()) {
                cacheFile.inputStream().use {
                    // Phase D: GSON.fromJsonObject<ZipImageCache>(it) InputStream 重载 → 读取为 String 再解析
                    GSON.fromJsonObject<ZipImageCache>(
                        it.readBytes().toString(kotlin.text.Charsets.UTF_8)
                    ).getOrNull()
                }
            } else null
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
                }.onFailure { AppLog.put("读取Cbz图片列表失败\n${it.localizedMessage}", it) }
                    .getOrNull()
            }?.also { entries ->
                saveCache(cacheFile, entries)
            }
        }

        return res?.also {
            imageEntriesByChapter =
                it.groupBy { entry -> entry.name.replace("\\", "/").substringBeforeLast("/", "") }
        }
    }

    private fun saveCache(cacheFile: File, entries: List<ZipEntry>) {
        val rzf = zipFile as? RemoteZipWrapper
        val newCache = ZipImageCache(
            entries = entries.map {
                it.copy(entryOffset = rzf?.getEntryOffset(it.name) ?: 0)
            }, eocdOffset = rzf?.eocdOffset ?: 0, centralOffset = rzf?.centralOffset ?: 0
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.bufferedWriter().use {
                // Phase D: GSON.toJson(newCache, it) Writer 流式 → encodeToString 一次性写入
                it.write(GSON.encodeToString(newCache))
            }
        }
    }

    private fun getZipCharset(): Charset {
        val charsetName = book.charset ?: imageEntries?.asSequence()?.map { it.name }
            ?.filter { it.any { c -> c.code > 127 } }?.take(5)?.joinToString("")
            ?.takeIf { it.isNotEmpty() }
            ?.let { EncodingDetect.getEncode(it.toByteArray(Charsets.ISO_8859_1)) }

        return charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: kotlin.text.Charsets.UTF_8
    }

    fun close() {
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
        if (book.charset.isNullOrEmpty()) book.charset = getZipCharset().name()

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
     * 下沉后改用 [BitmapProviders] 接口 (app: BitmapFactory; desktop: ImageIO),
     * 行为一致, 仅多一层 provider 间接。
     */
    private fun extractCover(zf: ZipFileWrapper) {
        val coverUrl = book.coverUrl ?: return
        if (File(coverUrl).exists()) return
        imageEntries?.firstOrNull()?.let { entry ->
            runCatching {
                zf.getInputStream(entry)?.use { input ->
                    BitmapProviders.get().decodeStreamAndCompressToJpeg(input, File(coverUrl), 90)
                }
            }
        }
    }

    private fun parseComicInfo(zf: ZipFileWrapper) {
        val entry = zf.getEntry("ComicInfo.xml") ?: return
        runCatching {
            zf.getInputStream(entry)?.use { input ->
                val xml = input.bufferedReader().readText()
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
            }
        }.onFailure { AppLog.put("解析ComicInfo.xml失败\n${it.localizedMessage}", it) }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapters = getChapters()
        if (chapters.isEmpty()) return arrayListOf()
        val charset = getZipCharset()
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
                url = f, title = if (displayTitle.isNotEmpty()) try {
                    String(displayTitle.toByteArray(Charsets.ISO_8859_1), charset)
                } catch (_: Exception) {
                    displayTitle
                } else "正文", bookUrl = book.bookUrl, index = i
            )
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        return getChapters()[chapter.url]?.joinToString("") { "<img src=\"${it.name}\"/>" }
    }

    protected fun finalize() {
        close()
    }
}
