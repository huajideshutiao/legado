// Copyright The Aniyomi Contributors. Apache-2.0.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // v14 旧 suspend API 为兼容面逐字保留, 调用/覆盖均为有意为之

package eu.kanade.tachiyomi.animesource.online

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

// lib 14(request/parse 辅助方法)与 lib 16/17(Hoster 系、seasonList 系)suspend 契约并集;
// 上游 master 的 rx fetch*/awaitSingle 桥改为 suspend 直连 request/parse (与漫画侧 HttpSource 同构)
abstract class AnimeHttpSource : AnimeCatalogueSource {

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

    // ---- lib 14 suspend 主 API: 默认桥接到下方 request/parse 辅助方法 ----

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        popularAnimeParse(client.newCall(popularAnimeRequest(page)).awaitSuccess())

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        latestUpdatesParse(client.newCall(latestUpdatesRequest(page)).awaitSuccess())

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        searchAnimeParse(client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess())

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val details = if (fetchDetails) getAnimeDetails(anime) else anime
        val episodeList = if (fetchEpisodes) getEpisodeList(anime) else episodes
        return SAnimeEpisodeUpdate(details, episodeList)
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        val details = if (fetchDetails) getAnimeDetails(anime) else anime
        val seasonList = if (fetchSeasons) getSeasonList(anime) else seasons
        return SAnimeSeasonUpdate(details, seasonList)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        videoListParse(client.newCall(videoListRequest(episode)).awaitSuccess())

    // ---- lib 16/17 主 API ----

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> =
        seasonListParse(client.newCall(seasonListRequest(anime)).awaitSuccess())

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
        hosterListParse(client.newCall(hosterListRequest(episode)).awaitSuccess())

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        videoListParse(client.newCall(videoListRequest(hoster)).awaitSuccess(), hoster)

    open suspend fun resolveVideo(video: Video): Video? = video

    open fun createHttpServer(): HttpServer? = null

    open suspend fun getVideoThumbnails(video: Video): ThumbnailInfo? = null

    open suspend fun getImageTile(url: String): Bitmap? {
        return client.newCall(GET(url, headers)).execute().body.byteStream().use {
            BitmapFactory.decodeStream(it)
        }
    }

    // ---- lib 14 弃用语义的 suspend 主方法 (扩展直接 override 的入口) ----

    @Suppress("DEPRECATION")
    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getAnimeEpisodeUpdate"),
    )
    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        animeDetailsParse(client.newCall(animeDetailsRequest(anime)).awaitSuccess())
            .apply { initialized = true }

    @Suppress("DEPRECATION")
    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getAnimeEpisodeUpdate"),
    )
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        episodeListParse(client.newCall(episodeListRequest(anime)).awaitSuccess())

    open suspend fun getVideoUrl(video: Video): String =
        videoUrlParse(client.newCall(videoUrlRequest(video)).awaitSuccess())

    // ---- request/parse 辅助方法面 (弃用语义, 与 aniyomi source-api 一致) ----

    protected open fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    protected open fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException()

    protected open fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    open fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    protected open fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    protected open fun seasonListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    protected open fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    protected open fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected open fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    protected open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    protected open fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    protected open fun videoListRequest(hoster: Hoster): Request = GET(hoster.hosterUrl, headers)

    protected open fun videoListParse(response: Response, hoster: Hoster): List<Video> =
        throw UnsupportedOperationException()

    protected open fun videoUrlRequest(video: Video): Request = GET(video.url, headers)

    protected open fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    open fun List<Hoster>.sortHosters(): List<Hoster> = this

    open fun List<Video>.sortVideos(): List<Video> = this

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
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

    open fun getAnimeUrl(anime: SAnime): String = animeDetailsRequest(anime).url.toString()

    open fun getEpisodeUrl(episode: SEpisode): String = episode.url

    @Deprecated("All modifications should be done when constructing the episode")
    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) {}
}
