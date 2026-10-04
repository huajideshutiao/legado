// Copyright The Mihon Authors. Apache-2.0.
// Document/Element 经 eu.kanade.tachiyomi.util.JsoupExtensions typealias 落在 data 门面 org.jsoup.nodes,
// 描述符与扩展 dex 引用逐字一致
// 1.4 时代扩展对其抽象方法的 org.jsoup FQCN 签名在真 jsoup 引入前不做二进制对齐
package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.Document
import eu.kanade.tachiyomi.util.Element
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

@Deprecated(
    message = "In most cases sources only require a subset of the methods from this class. " +
        "Source developers should make their own implementation according to their needs.",
)
abstract class ParsedHttpSource : HttpSource() {

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }

        val hasNextPage = popularMangaNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun popularMangaSelector(): String

    protected abstract fun popularMangaFromElement(element: Element): SManga

    protected abstract fun popularMangaNextPageSelector(): String?

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }

        val hasNextPage = searchMangaNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun searchMangaSelector(): String

    protected abstract fun searchMangaFromElement(element: Element): SManga

    protected abstract fun searchMangaNextPageSelector(): String?

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }

        val hasNextPage = latestUpdatesNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }

    protected abstract fun latestUpdatesSelector(): String

    protected abstract fun latestUpdatesFromElement(element: Element): SManga

    protected abstract fun latestUpdatesNextPageSelector(): String?

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    protected abstract fun mangaDetailsParse(document: Document): SManga

    override fun chapterListParse(response: Response): List<SChapter> = chapterListParse(response.asJsoup())

    protected abstract fun chapterListParse(document: Document): List<SChapter>

    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    protected abstract fun pageListParse(document: Document): List<Page>

    override fun imageUrlParse(response: Response): String = imageUrlParse(response.asJsoup())

    protected abstract fun imageUrlParse(document: Document): String
}
