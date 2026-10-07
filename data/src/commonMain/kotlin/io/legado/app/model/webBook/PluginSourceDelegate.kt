package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookListPage
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import kotlin.concurrent.Volatile

/**
 * 插件虚拟源 (漫画 / Aniyomi 视频 / TVBox 站点) 的取数委派契约。
 *
 * 三类插件源以虚拟 [BookSource] 行落地 (稳定 URL 前缀 + header 承载插件请求头), 自动进入
 * 书源管理 / 搜索范围 / 换源; 行内规则字段恒为空, 取数由宿主实现接管。接口只声明 shared
 * 数据类型, 不感知插件侧类型 (Mihon source-api / Aniyomi animesource / catvod spider)。
 *
 * 实现由各端启动序列注册进 [PluginSourceDelegates] (Android WebBookProvidersImpl、
 * 桌面 DesktopCore); 插件为 APK/DEX + JVM jar 形态, native 端无实现, 未注册时代派层
 * 解析不到委派, 取数走原规则链。
 */
interface PluginSourceDelegate {

    /** 该书源是否由本委派处理 (虚拟行身份判定: 类型 + URL 前缀)。 */
    fun handles(bookSource: BookSource): Boolean

    /**
     * 插件源搜索 (对应 WebBook.getBookListAwait, isSearch=true; page 从 1 起)。
     *
     * [filters] 为页面会话筛选实例 (页面创建、随页面销毁, 不持久化); 未传 (无筛选 UI 的调用方)
     * 时用源默认筛选。
     */
    suspend fun getBookListAwait(
        bookSource: BookSource,
        key: String,
        page: Int,
        filters: PluginFilterSession? = null,
    ): BookListPage

    /**
     * 插件源发现取数 (对应 WebBook.getBookListAwait 的 isSearch=false 路径)。
     *
     * [url] 为虚拟源 exploreUrl 中某个发现分类的 url 段 (形如 `popular`/`latest`/`filter`),
     * 由实现自行分派到插件源对应取数面; 无法识别的值应显式报错而非静默返回空。
     *
     * [filters] 为筛选分类页的会话筛选实例 (页面创建、随页面销毁, 不持久化);
     * 筛选分类面必然携带对应契约的会话实例, 缺失即报错 (不静默取无筛选数据)。
     */
    suspend fun getExploreAwait(
        bookSource: BookSource,
        url: String,
        page: Int,
        filters: PluginFilterSession? = null,
    ): BookListPage

    /** 插件源书籍详情 (对应 WebBook.getBookInfoAwait; 字段写回 [book] 并返回)。 */
    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean,
    ): Book

    /** 插件源目录 (对应 WebBook.getChapterListAwait)。 */
    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
    ): Result<List<BookChapter>>

    /** 插件源正文 (对应 WebBook.getContentAwait)。 */
    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
    ): String

    /**
     * 目录失败日志文案 (带来源标识); 返回 null 时由调用方用通用文案。
     * 委派是虚拟源唯一的来源知识持有者, 失败文案在此给出, 取数管线不判源身份。
     */
    fun tocFailMessage(bookSource: BookSource, e: Exception): String? = null

    /**
     * 发现分类 (对应 BookSource.exploreKinds)。
     *
     * 返回 null = 本实现不接管分类 (走源自身 exploreUrl 规则解析); 返回列表 = 直接作为该书源
     * 分类 (站点分类是 spider 运行时数据, 无法写进 exploreUrl 字段)。结果由 exploreKinds()
     * 与源规则解析共用同一份磁盘缓存。
     */
    suspend fun getExploreKinds(bookSource: BookSource): List<ExploreKind>? = null
}

/**
 * 插件源取数委派注册表: 按注册顺序取首个 [PluginSourceDelegate.handles] 命中者。
 *
 * 多处实现并列注册 (各实现身份互斥), 注册顺序只影响命中顺序不影响正确性; 重复注册同一
 * 实例不叠加。未注册任何实现的宿主 (native 端) 解析结果恒 null, 取数走原规则链。
 */
object PluginSourceDelegates {

    @Volatile
    private var impls: List<PluginSourceDelegate> = emptyList()

    /** 宿主启动早期注册 (Android WebBookProvidersImpl / 桌面 DesktopCore)。 */
    fun register(delegate: PluginSourceDelegate) {
        if (impls.any { it === delegate }) return
        impls = impls + delegate
    }

    /** 命中该书源的委派; 未注册或均不命中返回 null。 */
    fun resolve(bookSource: BookSource): PluginSourceDelegate? =
        impls.firstOrNull { it.handles(bookSource) }

    /** 已注册委派 (诊断与测试用)。 */
    fun registered(): List<PluginSourceDelegate> = impls
}
