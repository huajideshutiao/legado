package io.legado.app.ui.root

import io.legado.app.constant.SourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isRss
import io.legado.app.help.book.isVideo
import io.legado.app.ui.book.searchContent.SearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 应用内唯一类型化路由参数。平台入口不得再平行保存页面参数。 */
@Serializable
sealed interface AppRoute {
    @Serializable
    @SerialName("main")
    data class Main(val tab: MainTab = MainTab.BOOKSHELF) : AppRoute

    @Serializable
    @SerialName("search")
    data class Search(
        val key: String? = null,
        val searchScope: String? = null,
        val submit: Boolean = true,
    ) : AppRoute

    @Serializable
    @SerialName("book_info")
    data class BookInfo(val book: BookRef) : AppRoute

    @Serializable
    @SerialName("reader")
    data class Reader(
        val book: BookRef,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : AppRoute

    @Serializable
    @SerialName("book_source")
    data object BookSourceManage : AppRoute

    @Serializable
    @SerialName("explore")
    data object Explore : AppRoute

    @Serializable
    @SerialName("explore_show")
    data class ExploreShow(
        val source: BookSource,
        val title: String,
        val exploreUrl: String? = null,
    ) : AppRoute

    @Serializable
    @SerialName("my_config")
    data object MyConfig : AppRoute

    @Serializable
    @SerialName("remote_book")
    data object RemoteBook : AppRoute

    @Serializable
    @SerialName("replace_edit")
    data class ReplaceEdit(
        val ruleId: Long = -1L,
        val pattern: String? = null,
        val isRegex: Boolean = false,
        val scope: String? = null,
    ) : AppRoute

    // 三端合并: 书籍阅读类 (音频/视频/漫画/RSS/目录/换源/书签/书评/信息编辑)
    @Serializable
    @SerialName("audio_play")
    data class AudioPlay(
        val book: BookRef,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : AppRoute

    @Serializable
    @SerialName("video_play")
    data class VideoPlay(
        val book: BookRef,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : AppRoute

    @Serializable
    @SerialName("manga_reader")
    data class MangaReader(
        val book: BookRef,
        val chapterIndex: Int? = null,
        val chapterPos: Int? = null,
    ) : AppRoute

    @Serializable
    @SerialName("toc")
    data class Toc(val book: BookRef) : AppRoute

    @Serializable
    @SerialName("read_rss")
    data class ReadRss(val book: BookRef) : AppRoute

    @Serializable
    @SerialName("book_info_edit")
    data class BookInfoEdit(val book: BookRef) : AppRoute

    @Serializable
    @SerialName("change_source")
    data class ChangeSource(val book: BookRef) : AppRoute

    @Serializable
    @SerialName("change_chapter_source")
    data class ChangeChapterSource(
        val book: BookRef,
        val chapterIndex: Int = 0,
        val chapterTitle: String = "",
    ) : AppRoute

    @Serializable
    @SerialName("bookmark")
    data class Bookmark(val book: BookRef? = null) : AppRoute

    @Serializable
    @SerialName("review_post")
    data class ReviewPost(
        val book: BookRef,
        val replyPreview: String? = null,
    ) : AppRoute

    // 段评/书评列表页 (与 ReviewPost 区别: 列表查看 vs 发布)
    @Serializable
    @SerialName("review_list")
    data class ReviewList(val book: BookRef) : AppRoute

    // 三端合并: 书源/规则编辑类 (仅传稳定 ID/URL)
    @Serializable
    @SerialName("book_source_edit")
    data class BookSourceEdit(val sourceUrl: String) : AppRoute

    @Serializable
    @SerialName("book_source_debug")
    data class BookSourceDebug(val sourceUrl: String) : AppRoute

    @Serializable
    @SerialName("replace_rule")
    data object ReplaceRule : AppRoute

    @Serializable
    @SerialName("dict_rule")
    data object DictRule : AppRoute

    @Serializable
    @SerialName("txt_toc_rule")
    data object TxtTocRule : AppRoute

    @Serializable
    @SerialName("source_filter_rule")
    data object SourceFilterRule : AppRoute

    @Serializable
    @SerialName("rule_sub")
    data object RuleSub : AppRoute

    @Serializable
    @SerialName("effective_replaces")
    data object EffectiveReplaces : AppRoute

    // 三端合并: 书架/搜索/导入类
    @Serializable
    @SerialName("bookshelf_manage")
    data class BookshelfManage(val groupId: Long = -1L) : AppRoute

    @Serializable
    @SerialName("search_content")
    data class SearchContent(
        val index: Int = 0,
        val word: String? = null,
        val initialResults: List<SearchResult>? = null,
    ) : AppRoute

    @Serializable
    @SerialName("import_book")
    data class ImportBook(val filePath: String? = null) : AppRoute

    // 三端合并: 配置类 (MY 子项)
    @Serializable
    @SerialName("about")
    data object About : AppRoute

    @Serializable
    @SerialName("read_record")
    data object ReadRecord : AppRoute

    @Serializable
    @SerialName("backup_config")
    data object BackupConfig : AppRoute

    // WebDav 配置 (desktop 独有, 与 BackupConfig 平级)
    @Serializable
    @SerialName("web_dav_config")
    data object WebDavConfig : AppRoute

    @Serializable
    @SerialName("other_config")
    data object OtherConfig : AppRoute

    @Serializable
    @SerialName("theme_config")
    data object ThemeConfig : AppRoute

    @Serializable
    @SerialName("cover_config")
    data object CoverConfig : AppRoute

    @Serializable
    @SerialName("welcome_config")
    data object WelcomeConfig : AppRoute

    @Serializable
    @SerialName("read_config")
    data object ReadConfig : AppRoute

    @Serializable
    @SerialName("read_aloud_config")
    data object ReadAloudConfig : AppRoute

    @Serializable
    @SerialName("padding_config")
    data object PaddingConfig : AppRoute

    @Serializable
    @SerialName("tip_config")
    data object TipConfig : AppRoute

    @Serializable
    @SerialName("more_config")
    data object MoreConfig : AppRoute

    @Serializable
    @SerialName("bg_text_config")
    data object BgTextConfig : AppRoute

    @Serializable
    @SerialName("read_style")
    data object ReadStyle : AppRoute

    // 三端合并: 工具类 (WebView/登录/JS/关联)
    // 字段对应原 app 端 WebViewActivity 的 intent extras:
    // title/sourceName/sourceKey/sourceType (initData 取书源 headerMap 用),
    // saveResult/refetchAfterSuccess (原 sourceVerificationEnable/refetchAfterSuccess, 验证回传用),
    // isLogin (原登录模式: 回传 cookie 到书源)。
    @Serializable
    @SerialName("web_view")
    data class WebView(
        val url: String,
        val title: String = "",
        val sourceName: String = "",
        val sourceKey: String = "",
        val sourceType: Int = SourceType.book,
        val isLogin: Boolean = false,
        val saveResult: Boolean = false,
        val refetchAfterSuccess: Boolean = true,
    ) : AppRoute

    /**
     * 书源登录页。
     *
     * [dataKey] 指向 [io.legado.app.help.SourceLoginContext]（源对象 + book/chapter JS 上下文），
     * 对照原版 `IntentData.nowSource/nowBook/nowChapter`：HttpTTS 等不在 bookSourceDao 的源
     * 只能靠它拿到，登录 JS 的 book/chapter 绑定也只能靠它传。
     * 为空（或进程重建后失效）时退化为按 [sourceUrl] 查库。
     */
    @Serializable
    @SerialName("login")
    data class Login(val sourceUrl: String, val dataKey: String? = null) : AppRoute

    @Serializable
    @SerialName("js_edit")
    data object JsEdit : AppRoute
}

@Serializable
enum class MainTab { HOME, BOOKSHELF, DISCOVERY, MY }

/**
 * 路由只持有可序列化业务快照，不持有 Activity、UIViewController 或平台文件句柄。
 * SearchBook 与 Book 均已位于 commonMain 且可序列化。
 */
@Serializable
sealed interface BookRef {
    val bookUrl: String

    @Serializable
    @SerialName("book")
    data class Stored(val value: Book) : BookRef {
        override val bookUrl: String get() = value.bookUrl
    }

    @Serializable
    @SerialName("search_book")
    data class Search(val value: SearchBook) : BookRef {
        override val bookUrl: String get() = value.bookUrl
    }
}

fun Book.toRouteRef(): BookRef = BookRef.Stored(copy())
fun SearchBook.toRouteRef(): BookRef = BookRef.Search(copy())

// 按 app 端 startActivityForBook 逻辑分流阅读类路由 (Audio/Video/Manga/Rss/Reader)
fun Book.toReadRoute(): AppRoute = toRouteRef().toReadRoute()

// 由 BookRef 构造阅读类路由, 保留 chapterIndex/chapterPos (供 OpenBook/OpenReader 外部启动定位章节)
fun BookRef.toReadRoute(chapterIndex: Int? = null, chapterPos: Int? = null): AppRoute {
    val book = asBook()
    return when {
        book.isAudio -> AppRoute.AudioPlay(this, chapterIndex, chapterPos)
        book.isVideo -> AppRoute.VideoPlay(this, chapterIndex, chapterPos)
        book.isImage -> AppRoute.MangaReader(this, chapterIndex, chapterPos)
        book.isRss -> AppRoute.ReadRss(this)
        else -> AppRoute.Reader(this, chapterIndex, chapterPos)
    }
}

fun BookRef.asBook(): Book = when (this) {
    is BookRef.Stored -> value.copy()
    is BookRef.Search -> value.toBook()
}
