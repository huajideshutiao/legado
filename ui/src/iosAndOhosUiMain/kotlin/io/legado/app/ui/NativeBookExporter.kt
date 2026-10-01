package io.legado.app.ui

import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelpProviders
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.File
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.textCharsetCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** Shared file generation; each platform presents its own save/share UI. */
internal object NativeBookExporter {
    private val appDb get() = AppDbProviders.get()
    private val prefs get() = PreferenceProviders.get()

    suspend fun exportBookFile(
        book: Book,
        exportDir: File,
        usedPaths: MutableSet<String>,
        exportType: Int,
        chaptersOverride: List<BookChapter>? = null,
        nameOverride: String? = null,
    ): File {
        if (chaptersOverride == null && book.isLocal && (book.isImage || (exportType == 1 && book.isEpub))) {
            val original = File(book.bookUrl.removePrefix("file://"))
            if (original.isFile) return original
        }
        val chapters = chaptersOverride ?: appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) error("没有章节")
        val extension = when {
            book.isImage -> "cbz"
            exportType == 1 -> "epub"
            else -> "txt"
        }
        val name = nameOverride ?: book.getExportFileName(extension)
        var file = File(exportDir, name)
        var suffix = 2
        while (!usedPaths.add(file.absolutePath)) {
            file = File(exportDir, "${name.removeSuffix(".$extension")} ($suffix).$extension")
            suffix++
        }
        try {
            if (extension == "cbz") {
                NativeBookArchiveExport.cbz(book, chapters, file)
                return file
            }
            if (extension == "epub") {
                NativeBookArchiveExport.epub(
                    book, chapters, file,
                    prefs.getBoolean(PreferKey.exportUseReplace, true) && book.getUseReplaceRule(),
                )
                return file
            }
            val charset = textCharsetCodec(prefs.getString(PreferKey.exportCharset, "UTF-8"))
            FileSystem.SYSTEM.sink(file.path.toPath()).buffer().use { output ->
                var isFirstWrite = true
                fun writeText(value: String) {
                    val bytes = charset.encode(value)
                    // UTF-16 编码器每次调用都会加 BOM，流式导出只在文件开头保留一次。
                    if (!isFirstWrite && charset.name == "UTF-16") output.write(bytes, 2, bytes.size - 2)
                    else output.write(bytes)
                    isFirstWrite = false
                }
                writeText("${book.name}\n作者：${book.getRealAuthor()}\n简介：${HtmlFormatter.format(book.getDisplayIntro())}")
                val useReplace = prefs.getBoolean(PreferKey.exportUseReplace, true) && book.getUseReplaceRule()
                val includeTitle = !prefs.getBoolean(PreferKey.exportNoChapterName, false)
                chapters.forEach { chapter ->
                    currentCoroutineContext().ensureActive()
                    val content = BookHelpProviders.get().getContent(book, chapter)
                        ?: if (chapter.isVolume) "" else "null"
                    val processed = ContentProcessorProviders.get().getBookContent(
                        book = book,
                        chapter = chapter.copy(isVip = false),
                        content = content,
                        includeTitle = includeTitle,
                        useReplace = useReplace,
                        chineseConvert = false,
                        reSegment = false,
                    )
                    val text = if (includeTitle) {
                        processed.textList.mapIndexed { index, part ->
                            if (index == 0) part.replace("🔒", "") else part
                        }.joinToString("\n")
                    } else processed.toString()
                    writeText("\n\n$text")
                }
            }
            return file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

}
