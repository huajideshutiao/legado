// Copyright The Keiyoushi Contributors. Apache-2.0.
// 1.4(request/parse 辅助方法)与 1.6(suspend get*)两代契约的并集; headers 字段保持 by lazy,
// KeiSource(扩展 APK 内)经反射替换 headers$delegate
package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

abstract class HttpSource : CatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient get() = network.client

    @Suppress("MemberVisibilityCanBePrivate")
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    // ---- 1.6 suspend 主 API: 默认桥接到下方 1.4 request/parse 辅助方法 ----

    override suspend fun getPopularManga(page: Int): MangasPage =
        popularMangaParse(client.newCall(popularMangaRequest(page)).awaitSuccess())

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        latestUpdatesParse(client.newCall(latestUpdatesRequest(page)).awaitSuccess())

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        searchMangaParse(client.newCall(searchMangaRequest(page, query, filters)).awaitSuccess())

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val details = if (fetchDetails) {
            async {
                mangaDetailsParse(client.newCall(mangaDetailsRequest(manga)).awaitSuccess())
                    .apply { initialized = true }
            }
        } else {
            null
        }
        val chapterList = if (fetchChapters) {
            async { chapterListParse(client.newCall(chapterListRequest(manga)).awaitSuccess()) }
        } else {
            null
        }
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        pageListParse(client.newCall(pageListRequest(chapter)).awaitSuccess())

    open suspend fun getImageUrl(page: Page): String =
        imageUrlParse(client.newCall(imageUrlRequest(page)).awaitSuccess())

    // ---- 1.4 辅助方法面 (弃用语义, 与 Mihon source-api 一致) ----

    protected open fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()

    protected open fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    protected open fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    protected open fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

    protected open fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    protected open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    /** 宿主内部读取 per-page 图片请求头 (扩展常覆写 imageRequest 追加防盗链头), 非上游契约面。 */
    fun pageRequestHeaders(page: Page): Headers = imageRequest(page).headers

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (_: URISyntaxException) {
            orig
        }
    }

    open fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()

    open fun getChapterUrl(chapter: SChapter): String = pageListRequest(chapter).url.toString()

    @Deprecated("All modifications should be done when constructing the chapter")
    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}
}
