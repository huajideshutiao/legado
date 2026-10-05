package io.legado.app.model.anime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.extension.MangaExtensionManager
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.VideoSourceDelegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 视频插件源取数委派实现 (data 层 VideoSourceDelegate 的宿主实现, 与漫画侧
 * MangaSourceDelegateImpl 同构)。
 *
 * 经 MangaExtensionManager 解析插件视频源实例后, 四路取数调 animesource API
 * (getSearchAnime/getAnimeEpisodeUpdate/getVideoList), 再经 AnimeSourceMapper 映射回
 * Book/BookChapter/可播内容串, 复用现有搜索/详情/目录/视频播放管线。
 * 正文返回 `videoUrl,{"headers":{…}}` 链接参数串或多行 `标题::内容` (多分辨率),
 * 由现有 parseVideoContent 链解析进播放器。
 */
object VideoSourceDelegateImpl : VideoSourceDelegate {

    override fun handles(bookSource: BookSource): Boolean =
        bookSource.bookSourceType == BookSourceType.video &&
            bookSource.bookSourceUrl.startsWith(AnimeSourceMapper.SOURCE_URL_PREFIX)

    override suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
    ): BookListPage {
        val source = resolveSource(bookSource)
        val animesPage = source.getSearchAnime(page, key, AnimeFilterList())
        return animesPage.toBookListPage(bookSource)
    }

    override suspend fun getExploreAwait(
        bookSource: BookSource,
        url: String,
        page: Int,
    ): BookListPage {
        val source = resolveSource(bookSource)
        // 虚拟源 exploreUrl 的分类 url 段 → 插件源对应取数面 (未知值显式报错, 不静默返回空)。
        // supportsLatest=false 的源在 getLatestUpdates 上抛 (上游语义), 此处如实透传。
        val animesPage = when (url) {
            AnimePluginSources.EXPLORE_URL_POPULAR -> source.getPopularAnime(page)
            AnimePluginSources.EXPLORE_URL_LATEST -> source.getLatestUpdates(page)
            else -> throw IllegalStateException("未知的视频插件发现分类: $url")
        }
        return animesPage.toBookListPage(bookSource)
    }

    /** AnimesPage → BookListPage (搜索与发现共用同一映射)。 */
    private fun AnimesPage.toBookListPage(bookSource: BookSource): BookListPage {
        val books = ArrayList<SearchBook>(animes.size)
        for (anime in animes) {
            books.add(
                anime.toSearchBook(bookSource.bookSourceUrl, bookSource.bookSourceName)
            )
        }
        return BookListPage(books, hasNextPage)
    }

    override suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book {
        val source = resolveSource(bookSource)
        val sAnime = book.toSAnime()
            ?: throw IllegalStateException("无法从 bookUrl 解析插件源 anime 地址: ${book.bookUrl}")
        val update = source.getAnimeEpisodeUpdate(
            anime = sAnime,
            episodes = emptyList(),
            fetchDetails = true,
            fetchEpisodes = false,
        )
        AnimeSourceMapper.applyTo(book, update.anime, canReName)
        return book
    }

    override suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>> = runCatching {
        val source = resolveSource(bookSource)
        val sAnime = book.toSAnime()
            ?: throw IllegalStateException("无法从 bookUrl 解析插件源 anime 地址: ${book.bookUrl}")
        val update = source.getAnimeEpisodeUpdate(
            anime = sAnime,
            episodes = emptyList(),
            fetchDetails = false,
            fetchEpisodes = true,
        )
        val chapterList = update.episodes.mapIndexed { index, episode ->
            episode.toBookChapter(book, index)
        }
        // 与规则链同构: updateBook 负责 reverse/index/totalChapterNum 等目录簿记。
        // 目录不预反转, 走 updateBook 默认反转 (仅 TVBox 卷头需正序簿记才预反转, 动画无卷头);
        // reverseToc=true 时保持插件返回顺序, 与小说"目录倒序"语义一致
        BookChapterList.updateBook(book, chapterList)
    }.onFailure {
        if (it is CancellationException) throw it
        currentCoroutineContext().ensureActive()
    }

    override suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String {
        val source = resolveSource(bookSource)
        val httpSource = source as? AnimeHttpSource
            ?: throw IllegalStateException("非在线视频插件源不支持取播: ${bookChapter.title}")
        val episode = SEpisode.create().apply {
            url = bookChapter.url
            name = bookChapter.title
        }
        // 调度与上游 EpisodeLoader.getHostersOnHttp 同款: 反射判定源实现的是 hoster 面
        // (lib16/17) 还是 episode 级 v14 面, 单路直调, 不做异常回退链。
        val videos = if (checkHasHosters(httpSource)) {
            hosterVideos(httpSource, episode)
        } else {
            episodeVideos(httpSource, episode)
        }
        val content = videos.toPlayableContent(httpSource)
        if (content.isNullOrBlank()) {
            throw IllegalStateException("插件源未返回可播视频 ${bookChapter.title}")
        }
        return content
    }

    /** 上游 EpisodeLoader.checkHasHosters 逐字移植: 类层级声明 hoster 面方法 ⇒ lib16/17 源。 */
    private fun checkHasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<in AnimeHttpSource> = source.javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java ||
                current == AnimeSource::class.java
            ) {
                return false
            }
            if (current.declaredMethods.any {
                    it.name in listOf("getHosterList", "hosterListRequest", "hosterListParse")
                }
            ) {
                return true
            }
            current = current.superclass ?: return false
        }
    }

    /** v14 面取数: 真实异常如实上抛 (上游无容错), 源侧排序契约照调。 */
    private suspend fun episodeVideos(source: AnimeHttpSource, episode: SEpisode): List<Video> {
        @Suppress("DEPRECATION") // v14 episode 级契约; 上游弃用但仍是 legacy 扩展的唯一取数面
        val videos = source.getVideoList(episode)
        return source.run { videos.sortVideos() }
    }

    /**
     * lib16/17 面取数: 逐 hoster 独立加载 (上游按 hoster 分态隔离报错, 宿主扁平模型下
     * 等价为单线路失败跳过、其余线路照常入列), 全失败才抛首个真实错误。
     */
    private suspend fun hosterVideos(source: AnimeHttpSource, episode: SEpisode): List<Video> {
        val hosters = source.getHosterList(episode).let { source.run { it.sortHosters() } }
        val videos = ArrayList<Video>(hosters.size)
        var firstError: Exception? = null
        for (hoster in hosters) {
            try {
                videos += hoster.videoList ?: source.getVideoList(hoster)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (videos.isEmpty() && firstError != null) throw firstError
        return source.run { videos.sortVideos() }
    }

    /**
     * 视频列表 → 可播内容串: 先按上游 parseVideoUrls 语义解析 v14 延迟解析项
     * (videoUrl 为 "null" 哨兵的立即 getVideoUrl), 再 preferred 置顶作默认分辨率;
     * 首项走 resolveVideo 惰性解析 (上游播放层职责, 宿主扁平模型前置于首项),
     * 其余仅收已带 videoUrl 的直链项。单项返回裸地址串, 多项返回 `标题::内容` 行
     * (现有 parseVideoContent 同款语义)。
     */
    private suspend fun List<Video>.toPlayableContent(source: AnimeHttpSource): String? {
        val resolved = parseVideoUrls(source)
        val ordered = resolved.filter { it.preferred } + resolved.filterNot { it.preferred }
        val lines = ArrayList<Pair<String, String>>(ordered.size)
        for ((index, video) in ordered.withIndex()) {
            val playable = if (index == 0) resolvePlayable(source, video) else video.toPlayableContent()
            if (!playable.isNullOrBlank()) {
                lines += video.playableTitle() to playable
            }
        }
        if (lines.isEmpty()) return null
        if (lines.size == 1) return lines[0].second
        return lines.joinToString("\n") { (title, content) -> "$title::$content" }
    }

    /** 上游 EpisodeLoader.parseVideoUrls 逐字移植: "null" 哨兵 url 立即经 getVideoUrl 解析。 */
    private suspend fun List<Video>.parseVideoUrls(source: AnimeHttpSource): List<Video> = map { video ->
        if (video.videoUrl != "null") return@map video
        val newVideoUrl = source.getVideoUrl(video)
        video.copy(videoUrl = newVideoUrl)
    }

    /** ResolvableAnimeSource 面: initialized=false 的视频先 resolveVideo (失败回落原视频)。 */
    private suspend fun resolvePlayable(source: AnimeHttpSource, video: Video): String? {
        val resolved = if (!video.initialized) source.resolveVideo(video) ?: video else video
        return resolved.toPlayableContent()
    }

    private suspend fun resolveSource(bookSource: BookSource): AnimeSource =
        AnimeSourceMapper.sourceIdOf(bookSource.bookSourceUrl)
            ?.let { MangaExtensionManager.getAnimeSource(it) }
            ?: throw IllegalStateException(
                "视频插件源未装载: ${bookSource.bookSourceName} (${bookSource.bookSourceUrl})",
            )

    /**
     * Book → 插件侧 SAnime (url 由 bookUrl 反解)。
     * 与漫画侧同契约: lib1.6 getAnimeEpisodeUpdate 入参是"已有 anime" (上游 SAnimeImpl.title
     * 为 lateinit, 扩展允许原样返回或仅部分覆写入参), 宿主已知字段必须预填。
     */
    private fun Book.toSAnime(): SAnime? {
        val sourceId = AnimeSourceMapper.sourceIdOf(origin) ?: return null
        val animeUrl = AnimeSourceMapper.animeUrlOf(bookUrl, sourceId) ?: return null
        return SAnime.create().apply {
            url = animeUrl
            title = name
            author = this@toSAnime.author
            thumbnail_url = coverUrl
            description = intro
        }
    }
}
