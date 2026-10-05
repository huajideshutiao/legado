package io.legado.app.model.anime

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.VirtualPluginSourcePrefix
import io.legado.app.utils.KS_JSON
import kotlinx.serialization.encodeToString
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Aniyomi 插件模型 → legado 数据实体映射 (与漫画侧 MangaSourceMapper 同构)。
 *
 * 身份约定 (书架归属与缓存键的稳定性依赖它, 不可随意变更):
 * - 虚拟书源 URL: `tachiyomi://<source.id>` (source.id 为插件源唯一 Long;
 *   前缀单一事实来源为 [VirtualPluginSourcePrefix.TACHIYOMI], 与漫画插件源共用,
 *   两者靠 bookSourceType 区分: 漫画=image / 视频=video);
 * - Book.bookUrl: `tachiyomi://<source.id>/<urlencode(anime.url)>`, 详情/目录/取数
 *   均由它反解出插件侧 anime.url;
 * - 虚拟 BookSource 行 bookSourceType=[BookSourceType.video], Book.type=[BookType.video],
 *   使整条链路复用视频播放管线。
 */
object AnimeSourceMapper {

    const val SOURCE_URL_PREFIX = VirtualPluginSourcePrefix.TACHIYOMI

    fun sourceUrlOf(sourceId: Long): String = "$SOURCE_URL_PREFIX$sourceId"

    fun sourceIdOf(sourceUrl: String): Long? =
        sourceUrl.removePrefix(SOURCE_URL_PREFIX).toLongOrNull()

    fun bookUrlOf(sourceId: Long, animeUrl: String): String =
        "$SOURCE_URL_PREFIX$sourceId/${URLEncoder.encode(animeUrl, "UTF-8")}"

    /** 由 Book.bookUrl 反解插件侧 anime.url; 无法反解返回 null (委派据此快速失败)。 */
    fun animeUrlOf(bookUrl: String, sourceId: Long): String? {
        val prefix = "$SOURCE_URL_PREFIX$sourceId/"
        if (!bookUrl.startsWith(prefix)) return null
        return runCatching { URLDecoder.decode(bookUrl.removePrefix(prefix), "UTF-8") }
            .getOrNull()
    }

    /** 详情刷新字段写回 (WebBook.getBookInfoAwait 语义: canReName=false 时保留用户已有名)。 */
    fun applyTo(book: Book, anime: SAnime, canReName: Boolean) {
        if (canReName || book.name.isBlank()) book.name = anime.title
        if (canReName || book.author.isBlank()) book.author = anime.author.orEmpty()
        book.kind = anime.displayKind
        book.coverUrl = anime.thumbnail_url
        book.intro = anime.description
    }
}

fun SAnime.toSearchBook(originUrl: String, originName: String): SearchBook {
    val bookUrl = toBookUrl(originUrl)
    return SearchBook(
        bookUrl = bookUrl,
        origin = originUrl,
        originName = originName,
        type = BookType.video,
        name = title,
        author = author.orEmpty(),
        kind = displayKind,
        coverUrl = thumbnail_url,
        intro = description,
        tocUrl = bookUrl,
    )
}

fun SAnime.toBook(originUrl: String, originName: String): Book = Book(
    bookUrl = toBookUrl(originUrl),
    tocUrl = toBookUrl(originUrl),
    origin = originUrl,
    originName = originName,
    type = BookType.video,
    name = title,
    author = author.orEmpty(),
    kind = displayKind,
    coverUrl = thumbnail_url,
    intro = description,
)

/**
 * 章节 url 存插件侧原始形态 (上游宿主不改写源侧 url): 请求面由插件自身拼 baseUrl,
 * 回喂绝对化地址会拼出双域名坏链。
 */
fun SEpisode.toBookChapter(
    book: Book,
    index: Int,
): BookChapter = BookChapter(
    bookUrl = book.bookUrl,
    url = url,
    title = name,
    index = index,
    tag = scanlator?.takeIf { it.isNotBlank() },
)

/**
 * Video → 可播内容串 (对接现有视频管线 parseVideoContent)。
 *
 * 请求头经 legado 原生链接参数语法 `url,{"headers":{…}}` 随地址携带 (AnalyzeUrlCore
 * 会拆出并合并进 headerMap); 多视频由委派拼为 `标题::内容` 多行, 管线按多分辨率源接入。
 *
 * mpvArgs/ffmpegStreamArgs/ffmpegVideoArgs 为 mpv/ffmpeg 专属特性, 现有播放管线无注入点, 忽略;
 * subtitleTracks/audioTracks 管线无外部轨道挂载 sink, 遗留豁口。
 */
fun Video.toPlayableContent(): String? {
    val url = videoUrl.takeIf { it.isNotBlank() && it != "null" } ?: return null
    val headers = headers?.takeIf { it.size > 0 } ?: return url
    val option = KS_JSON.encodeToString<Map<String, Map<String, String>>>(
        mapOf("headers" to headers.toMap()),
    )
    return "$url,$option"
}

/** 可播内容展示名 (多视频 `标题::内容` 行的标题段)。 */
fun Video.playableTitle(): String =
    videoTitle.replace("::", "").ifBlank { "默认" }

/** 由虚拟书源 URL + 插件侧 anime.url 构成 Book.bookUrl。 */
private fun SAnime.toBookUrl(originUrl: String): String =
    AnimeSourceMapper.bookUrlOf(
        AnimeSourceMapper.sourceIdOf(originUrl) ?: 0L,
        url,
    )

/** 分类标签: genres + 状态 (与 legado 书源 kind 中文标签习惯一致, 供发现/筛选展示)。 */
private val SAnime.displayKind: String
    get() = listOfNotNull(
        getGenres()?.joinToString(",")?.takeIf { it.isNotBlank() },
        statusTag,
    ).joinToString(",")

private val SAnime.statusTag: String?
    get() = when (status) {
        SAnime.ONGOING -> "连载"
        SAnime.COMPLETED, SAnime.PUBLISHING_FINISHED, SAnime.CANCELLED -> "完结"
        SAnime.ON_HIATUS -> "休刊"
        SAnime.LICENSED -> "版权"
        else -> null
    }
