package io.legado.app.data.entities

import io.legado.app.constant.BookType
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable

@Serializable
data class SearchBook(
    override var bookUrl: String = "",
    /** 书源 */
    override var origin: String = "",
    override var originName: String = "",
    /** BookType */
    override var type: Int = BookType.text,
    override var name: String = "",
    override var author: String = "",
    override var kind: String? = null,
    override var coverUrl: String? = null,
    override var intro: String? = null,
    override var wordCount: String? = null,
    override var latestChapterTitle: String? = null,
    /** 目录页Url (toc=table of Contents) */
    override var tocUrl: String = "",
    var time: Long = systemCurrentTimeMillis(),
    override var variable: String? = null,
    override var originOrder: Int = 0,
    var chapterWordCountText: String? = null,
    var chapterWordCount: Int = -1,
    var respondTime: Int = -1
) : BaseBook, Comparable<SearchBook> {

    override var infoHtml: String? = null

    override var tocHtml: String? = null

    override fun equals(other: Any?) = other is SearchBook && other.bookUrl == bookUrl

    override fun hashCode() = bookUrl.hashCode()

    override fun compareTo(other: SearchBook): Int {
        return other.originOrder - this.originOrder
    }

    @delegate:Transient
    override val variableMap: HashMap<String, String> by lazy {
        decodeStringMapOrNull(variable) ?: HashMap()
    }

    @delegate:Transient
    val origins: LinkedHashSet<String> by lazy { linkedSetOf(origin) }

    fun addOrigin(origin: String) {
        origins.add(origin)
    }

    fun getDisplayLastChapterTitle(): String {
        latestChapterTitle?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return "无最新章节"
    }

    fun sameBookTypeLocal(bookType: Int): Boolean {
        return type and BookType.allBookTypeLocal == bookType and BookType.allBookTypeLocal
    }

    fun toBook() = Book(
        name = name,
        author = author,
        kind = kind,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        type = type,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        coverUrl = coverUrl,
        intro = intro,
        tocUrl = tocUrl,
        originOrder = originOrder,
        variable = variable
    ).apply {
        this.infoHtml = this@SearchBook.infoHtml
        this.tocHtml = this@SearchBook.tocHtml
    }
}
