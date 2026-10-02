package io.legado.app.format.epub

import com.fleeksoft.ksoup.Ksoup
import io.legado.app.data.entities.BookChapter

/** Native EPUB chapters must point into the spine consumed by EpubContentReader. */
internal fun EpubBook.chapterList(bookUrl: String): ArrayList<BookChapter> {
    val result = arrayListOf<BookChapter>()
    val spineHrefs = spine.map { it.href }.toSet()
    fun readable(ref: EpubChapter): Boolean = ref.resource?.href in spineHrefs
    fun firstReadable(refs: List<EpubChapter>): EpubChapter? {
        for (ref in refs) {
            if (readable(ref)) return ref
            firstReadable(ref.children)?.let { return it }
        }
        return null
    }
    fun append(title: String, href: String, fragment: String? = null): BookChapter {
        val chapter = BookChapter(bookUrl = bookUrl, title = title, url = href)
        chapter.index = result.size
        chapter.startFragmentId = fragment
        result.lastOrNull()?.apply {
            endFragmentId = fragment
            putVariable("nextUrl", href)
        }
        result.add(chapter)
        return chapter
    }
    fun title(resource: EpubResource): String =
        Ksoup.parse(resource.data.decodeToString()).getElementsByTag("title").firstOrNull()?.text().orEmpty()
    val first = firstReadable(toc)
    if (first == null) {
        spine.forEachIndexed { index, resource ->
            append(title(resource).ifBlank { if (index == 0) "封面" else "无标题章节" }, resource.href)
        }
    } else {
        for (resource in spine) {
            if (resource.href == first.resource?.href) break
            append(title(resource).ifBlank { "--卷首--" }, resource.href)
        }
        fun appendMenu(refs: List<EpubChapter>) {
            for (ref in refs) {
                if (readable(ref)) {
                    append(ref.title, ref.completeHref, ref.fragmentId).isVolume = ref.children.isNotEmpty()
                }
                appendMenu(ref.children)
            }
        }
        appendMenu(toc)
    }
    return result
}
