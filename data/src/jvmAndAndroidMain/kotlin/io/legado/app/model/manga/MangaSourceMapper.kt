package io.legado.app.model.manga

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import io.legado.app.constant.BookType
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.VirtualPluginSourcePrefix
import io.legado.app.help.image.PluginImageUrl
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Tachiyomi/Mihon 插件模型 → legado 数据实体映射。
 *
 * 身份约定 (书架归属与缓存键的稳定性依赖它, 不可随意变更):
 * - 虚拟书源 URL: `tachiyomi://<source.id>` (source.id 为插件源唯一 Long;
 *   前缀单一事实来源为 [VirtualPluginSourcePrefix.TACHIYOMI], 与 Aniyomi 视频插件源共用,
 *   两者靠 bookSourceType 区分);
 * - Book.bookUrl: `tachiyomi://<source.id>/<urlencode(manga.url)>`, 详情/目录/正文
 *   取数均由它反解出插件侧 manga.url;
 * - 虚拟 BookSource 行 bookSourceType=[BookSourceType.image], Book.type=[BookType.image],
 *   使整条链路复用漫画阅读器与图片缓存。
 */
object MangaSourceMapper {

    const val SOURCE_URL_PREFIX = VirtualPluginSourcePrefix.TACHIYOMI

    fun sourceUrlOf(sourceId: Long): String = "$SOURCE_URL_PREFIX$sourceId"

    fun sourceIdOf(sourceUrl: String): Long? =
        sourceUrl.removePrefix(SOURCE_URL_PREFIX).toLongOrNull()

    fun bookUrlOf(sourceId: Long, mangaUrl: String): String =
        "$SOURCE_URL_PREFIX$sourceId/${URLEncoder.encode(mangaUrl, "UTF-8")}"

    /** 由 Book.bookUrl 反解插件侧 manga.url; 无法反解返回 null (委派据此快速失败)。 */
    fun mangaUrlOf(bookUrl: String, sourceId: Long): String? {
        val prefix = "$SOURCE_URL_PREFIX$sourceId/"
        if (!bookUrl.startsWith(prefix)) return null
        return runCatching { URLDecoder.decode(bookUrl.removePrefix(prefix), "UTF-8") }
            .getOrNull()
    }

    /**
     * 插件源图片 → 内部协议形态 `tachiyomi-img://<sourceId>/<urlencode(原始URL)>`
     * (见 [PluginImageUrl])。正文与缓存 key 使用该形态, 下载经扩展自身 client
     * (扩展拦截器 — 禁漫图片分割重排等 — 才能生效)。
     */
    fun pluginImageUrlOf(sourceId: Long, imageUrl: String): String =
        PluginImageUrl.PREFIX + sourceId + "/" + URLEncoder.encode(imageUrl, "UTF-8")

    fun SManga.toSearchBook(originUrl: String, originName: String): SearchBook {
        val bookUrl = toBookUrl(originUrl)
        return SearchBook(
            bookUrl = bookUrl,
            origin = originUrl,
            originName = originName,
            type = BookType.image,
            name = title,
            author = author.orEmpty(),
            kind = displayKind,
            coverUrl = thumbnail_url,
            intro = description,
            tocUrl = bookUrl,
        )
    }

    fun SManga.toBook(originUrl: String, originName: String): Book = Book(
        bookUrl = toBookUrl(originUrl),
        tocUrl = toBookUrl(originUrl),
        origin = originUrl,
        originName = originName,
        type = BookType.image,
        name = title,
        author = author.orEmpty(),
        kind = displayKind,
        coverUrl = thumbnail_url,
        intro = description,
    )

    /** 详情刷新字段写回 (WebBook.getBookInfoAwait 语义: canReName=false 时保留用户已有名)。 */
    fun applyTo(book: Book, manga: SManga, canReName: Boolean) {
        if (canReName || book.name.isBlank()) book.name = manga.title
        if (canReName || book.author.isBlank()) book.author = manga.author.orEmpty()
        book.kind = manga.displayKind
        book.coverUrl = manga.thumbnail_url
        book.intro = manga.description
    }

    /** 由虚拟书源 URL + 插件侧 manga.url 构成 Book.bookUrl。 */
    private fun SManga.toBookUrl(originUrl: String): String =
        MangaSourceMapper.bookUrlOf(
            MangaSourceMapper.sourceIdOf(originUrl) ?: 0L,
            url,
        )

    /** 分类标签: genre + 状态 (与 legado 书源 kind 中文标签习惯一致, 供发现/筛选展示)。 */
    private val SManga.displayKind: String
        get() = listOfNotNull(
            genre?.takeIf { it.isNotBlank() },
            statusTag,
        ).joinToString(",")

    private val SManga.statusTag: String?
        get() = when (status) {
            SManga.ONGOING -> "连载"
            SManga.COMPLETED, SManga.PUBLISHING_FINISHED, SManga.CANCELLED -> "完结"
            SManga.ON_HIATUS -> "休刊"
            SManga.LICENSED -> "版权"
            else -> null
        }

    /**
     * 章节 url 存插件侧原始形态 (上游宿主不改写源侧 url): pageListRequest 等请求面
     * 由插件自身拼 baseUrl, 回喂绝对化地址会拼出双域名坏链。
     */
    fun SChapter.toBookChapter(
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
     * Page 列表 → `<img src>` 正文 (漫画阅读器经 ChapterContentParserShared 提取进
     * 图片缓存链)。imageUrl 未即时给出的页面 (延迟解析型插件源) 回退 HttpSource.getImageUrl。
     * 所有 src 统一包装为 [pluginImageUrlOf] 内部协议形态, 下载委托扩展自身 client。
     */
    suspend fun List<Page>.toImgContent(
        sourceId: Long,
        resolveUrl: suspend (Page) -> String?,
    ): String {
        val sb = StringBuilder()
        for (page in this) {
            val src = page.imageUrl?.takeIf { it.isNotBlank() }
                ?: resolveUrl(page)
                ?: continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("<img src=\"").append(pluginImageUrlOf(sourceId, src)).append("\">")
        }
        return sb.toString()
    }
}
