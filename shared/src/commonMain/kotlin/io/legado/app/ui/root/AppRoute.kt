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
    @SerialName("explore_show")
    data class ExploreShow(
        val source: BookSource,
        val title: String,
        val exploreUrl: String? = null,
    ) : AppRoute

    /**
     * 发现show 外部入口 (对照 master ExploreShowActivity 冷启动直开):
     * 只带 sourceUrl/exploreUrl/exploreName extra, 书源对象未预取;
     * 查源下沉到路由内 (对照 master initData(intent): IntentData.source ?: DAO 查 sourceUrl),
     * 查源期间页面直接渲染加载态, 书架不进导航栈。
     * 应用内跳转 (已有 BookSource 对象) 走 [ExploreShow], 不查源。
     */
    @Serializable
    @SerialName("explore_show_by_url")
    data class ExploreShowByUrl(
        val sourceUrl: String,
        val title: String? = null,
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
    @SerialName("bookmark")
    data class Bookmark(val book: BookRef? = null) : AppRoute

    // 段评/书评列表页 (发布入口为弹窗形态 ReviewPostDialogHost, 对照原版 ReviewPostActivity)
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
        // 当前书籍 (阅读页全文搜索入口传入; 修复: SearchContent 页面 book 曾依赖
        // IntentData.book 全局槽, 路由跳转未设置 → book 恒 null → 只跳界面不搜索, 2026-08-06)
        val book: BookRef? = null,
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

/**
 * Book → 路由可序列化快照。
 *
 * # 拷贝契约 (2026-08: 去掉内部 copy)
 * `toRouteRef`/`asBook` 不再拷贝, 直接共享调用方对象:
 * - **DB-flow 边界必须显式 `copy()`**: 书架列表/搜索页"书架"区块等来自
 *   `bookDao.observeAll()` flow 的实体, 进入路由前需 `book.copy().toRouteRef()`
 *   (否则路由与 flow 实体别名, DB 流无法正确捕捉修改)。
 * - **瞬态书直接共享**: 搜索结果/发现/深链抓取等一次性对象无别名风险, 零拷贝。
 */
fun Book.toRouteRef(): BookRef = BookRef.Stored(this)
fun SearchBook.toRouteRef(): BookRef = BookRef.Search(this)

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
    is BookRef.Stored -> value
    is BookRef.Search -> value.toBook()
}
