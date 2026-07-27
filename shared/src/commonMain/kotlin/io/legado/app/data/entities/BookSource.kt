package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.help.ExploreKindsCacheProviders
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.RulePolymorphicSerializer
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.splitNotBlank
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * F2: BookSource 已下沉到 shared jvmAndAndroidMain, 去掉 JsExtensions 继承。
 *
 * JS 可见的 JsExtensions 面 (ajax/connect/webView/log 等) 由 app 端 [BookSourceJsExt] 包装器补回,
 * 通过 [io.legado.app.help.JsExtProviders] 在 [BaseSource.evalJS] 注入 bindings["java"]/["source"]。
 *
 * 发现规则缓存原走 app 端 ACache.get("explore").getAsString(...), 下沉后改走
 * [ExploreKindsCacheProviders] provider (app 端注册转发到 ACache, 行为不变)。
 *
 * log 不再 override (原 super<JsExtensions>.log), 走 [BaseSource.log] 默认实现
 * (已对齐 JsExtensions.log 行为, 含 SourceDebugLoggers.impl?.log + AppLog.putDebug)。
 */
@Suppress("unused")
@Serializable
@Entity(
    tableName = "book_sources",
    indices = [(Index(value = ["bookSourceUrl"], unique = false))]
)
data class BookSource(
    // 地址，包括 http/https
    @PrimaryKey
    override var bookSourceUrl: String = "",
    // 名称
    override var bookSourceName: String = "",
    // 分组
    var bookSourceGroup: String? = null,
    // 类型，0 文本，1 音频, 2 图片, 3 文件（指的是类似知轩藏书只提供下载的网站）
    var bookSourceType: Int = 0,
    // 详情页url正则
    override var bookUrlPattern: String? = null,
    // 手动排序编号
    @ColumnInfo(defaultValue = "0")
    override var customOrder: Int = 0,
    // 是否启用
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    // 启用发现
    @ColumnInfo(defaultValue = "1")
    var enabledExplore: Boolean = true,
    // 启用段评
    @ColumnInfo(defaultValue = "1")
    var enabledReview: Boolean = true,
    // js库
    override var jsLib: String? = null,
    // 启用okhttp CookieJAr 自动保存每次请求的cookie
    @ColumnInfo(defaultValue = "0")
    override var enabledCookieJar: Boolean? = true,
    // 高危api
    @ColumnInfo(defaultValue = "0")
    override var enableDangerousApi: Boolean? = false,
    // 并发率
    override var concurrentRate: String? = null,
    // 请求头
    override var header: String? = null,
    // 登录地址
    override var loginUrl: String? = null,
    // 登录UI
    // loginUi 的 JSON 值可能是数组/对象, 需原样转字符串 (复刻原 GSON 全局 StringJsonDeserializer)
    @Serializable(with = RawJsonStringSerializer::class)
    override var loginUi: String? = null,
    // 登录检测js
    override var loginCheckJs: String? = null,
    // 封面解密js
    var coverDecodeJs: String? = null,
    // 注释
    var bookSourceComment: String? = null,
    // 自定义变量说明
    var variableComment: String? = null,
    // 最后更新时间，用于排序
    var lastUpdateTime: Long = 0,
    // 响应时间，用于排序
    var respondTime: Long = 180000L,
    // 智能排序的权重
    var weight: Int = 0,
    // 发现url
    var exploreUrl: String? = null,
    // 发现筛选规则
    var exploreScreen: String? = null,
    // 发现样式：位运算魔数
    //   低 3 位 (0x07)：列数。0/1 = 单列（视频时单列网格，非视频时列表），2..6 = N 列网格（7 保留）。
    //   bit 4   (0x10)：视频布局标记，置位表示用视频卡片项 (item_explore_video)。
    // 例：0=列表；2=2 列卡片；0x11=单列视频；0x12=2 列视频。
    @ColumnInfo(defaultValue = "0")
    var exploreStyle: Int = 0,
    // 发现规则
    // rule JSON 值可能是对象, 需原样转字符串
    @Serializable(with = RawJsonStringSerializer::class)
    var ruleExplore: String? = null,
    // 搜索url
    override var searchUrl: String? = null,
    // 搜索规则
    // rule JSON 值可能是对象, 需原样转字符串
    @Serializable(with = RawJsonStringSerializer::class)
    var ruleSearch: String? = null,
    // 书籍信息页规则
    // rule JSON 值可能是对象, 需原样转字符串
    @Serializable(with = RawJsonStringSerializer::class)
    var ruleBookInfo: String? = null,
    // 目录页规则
    // rule JSON 值可能是对象, 需原样转字符串
    @Serializable(with = RawJsonStringSerializer::class)
    var ruleToc: String? = null,
    // 正文页规则
    // rule JSON 值可能是对象, 需原样转字符串
    @Serializable(with = RawJsonStringSerializer::class)
    var ruleContent: String? = null,
    // 段评规则
    // rule JSON 值可能是对象, 需原样转字符串
    @Serializable(with = RawJsonStringSerializer::class)
    override var ruleReview: String? = null
) : BaseSource, IBookSource {

    @Ignore
    @Transient
    private var _searchRule: SearchRule? = null

    @Ignore
    @Transient
    private var _exploreRule: ExploreRule? = null

    @Ignore
    @Transient
    private var _bookInfoRule: BookInfoRule? = null

    @Ignore
    @Transient
    private var _tocRule: TocRule? = null

    @Ignore
    @Transient
    private var _contentRule: ContentRule? = null

    @Ignore
    @Transient
    private var _reviewRule: ReviewRule? = null

    override fun getTag(): String {
        return bookSourceName
    }

    override fun getKey(): String {
        return bookSourceUrl
    }

    override fun getSourceType(): Int {
        return if (bookSourceType == BookSourceType.rss) {
            io.legado.app.constant.SourceType.rss
        } else {
            io.legado.app.constant.SourceType.book
        }
    }

    override fun hashCode(): Int {
        return bookSourceUrl.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return if (other is BookSource) other.bookSourceUrl == bookSourceUrl else false
    }

    @get:Ignore
    override var searchRule: SearchRule
        get() = _searchRule ?: parseRule(ruleSearch, SearchRule.serializer()) { SearchRule() }.also { _searchRule = it }
        set(value) {
            ruleSearch = KS_JSON.encodeToString(SearchRule.serializer(), value)
            _searchRule = value
        }

    @get:Ignore
    override var exploreRule: ExploreRule
        get() = _exploreRule ?: parseRule(ruleExplore, ExploreRule.serializer()) { ExploreRule() }.also { _exploreRule = it }
        set(value) {
            ruleExplore = KS_JSON.encodeToString(ExploreRule.serializer(), value)
            _exploreRule = value
        }

    @get:Ignore
    override var bookInfoRule: BookInfoRule
        get() = _bookInfoRule ?: parseRule(ruleBookInfo, BookInfoRule.serializer()) { BookInfoRule() }.also {
            _bookInfoRule = it
        }
        set(value) {
            ruleBookInfo = KS_JSON.encodeToString(BookInfoRule.serializer(), value)
            _bookInfoRule = value
        }

    @get:Ignore
    override var tocRule: TocRule
        get() = _tocRule ?: parseRule(ruleToc, TocRule.serializer()) { TocRule() }.also { _tocRule = it }
        set(value) {
            ruleToc = KS_JSON.encodeToString(TocRule.serializer(), value)
            _tocRule = value
        }

    @get:Ignore
    override var contentRule: ContentRule
        get() = _contentRule ?: parseRule(ruleContent, ContentRule.serializer()) { ContentRule() }.also { _contentRule = it }
        set(value) {
            ruleContent = KS_JSON.encodeToString(ContentRule.serializer(), value)
            _contentRule = value
        }

    @get:Ignore
    override var reviewRule: ReviewRule
        get() = _reviewRule ?: parseRule(ruleReview, ReviewRule.serializer()) { ReviewRule() }.also { _reviewRule = it }
        set(value) {
            ruleReview = KS_JSON.encodeToString(ReviewRule.serializer(), value)
            _reviewRule = value
        }

    /**
     * 解析 rule JSON 字符串为 rule 对象, 复用 [RulePolymorphicSerializer] 复刻原 Gson JsonDeserializer
     * 双形态反序列化语义 (JsonObject 直接解析 / JsonPrimitive 字符串先 parse 再解析 / 其他返回 null)。
     *
     * KS_JSON 容错降级 (ignoreUnknownKeys + isLenient + coerceInputValues) 对齐原 GSON 宽松策略。
     * 解析失败或返回 null 时回落到 [default], 与原 GSON.fromJsonObject(...).getOrNull() ?: default() 行为一致。
     */
    private fun <T : Any> parseRule(
        json: String?,
        serializer: KSerializer<T>,
        default: () -> T
    ): T {
        val raw = json?.takeIf { it.isNotEmpty() } ?: return default()
        return try {
            KS_JSON.decodeFromString(RulePolymorphicSerializer(serializer), raw) ?: default()
        } catch (_: Exception) {
            default()
        }
    }

    /**
     * 根据书源类型返回书籍类型位掩码 (BookType)。
     *
     * 实现逻辑与原 `BookSourceExtensions.getBookType()` 扩展函数完全一致,
     * 仅提升为成员方法以满足 [IBookSource] 接口契约 (便于 shared 模块在
     * 不依赖 app 端 BookSource 实体类的前提下通过接口调用)。
     */
    override fun getBookType(): Int {
        return when (bookSourceType) {
            BookSourceType.file -> BookType.text or BookType.webFile
            BookSourceType.image -> BookType.image
            BookSourceType.audio -> BookType.audio
            BookSourceType.video -> BookType.video
            BookSourceType.rss -> BookType.rss
            else -> BookType.text
        }
    }

    /**
     * 返回发现规则 JSON 字符串 (来自缓存或 exploreUrl)。
     *
     * 实现逻辑与原 `BookSourceExtensions.exploreKindsJson()` 扩展函数完全一致,
     * 仅提升为成员方法以满足 [IBookSource] 接口契约。
     *
     * 采用 md5 作为 key 可以在分类修改后自动重新计算, 不需要手动刷新。
     * F2: 缓存读取走 [ExploreKindsCacheProviders] (app 端注册转发到 ACache.get("explore"), 行为不变)。
     */
    override fun exploreKindsJson(): String {
        val exploreKindsKey = MD5Utils.md5Encode(bookSourceUrl + exploreUrl)
        return ExploreKindsCacheProviders.impl?.getAsString(exploreKindsKey)?.takeIf { it.isJsonArray() }
            ?: exploreUrl.takeIf { it.isJsonArray() }
            ?: ""
    }

    fun getDisPlayNameGroup(): String {
        return if (bookSourceGroup.isNullOrBlank()) {
            bookSourceName
        } else {
            "$bookSourceName ($bookSourceGroup)"
        }
    }

    fun addGroup(groups: String): BookSource {
        bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.toHashSet()?.let {
            it.addAll(groups.splitNotBlank(AppPattern.splitGroupRegex))
            bookSourceGroup = it.joinToString(",")
        }
        if (bookSourceGroup.isNullOrBlank()) bookSourceGroup = groups
        return this
    }

    fun removeGroup(groups: String): BookSource {
        bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.toHashSet()?.let {
            it.removeAll(groups.splitNotBlank(AppPattern.splitGroupRegex).toSet())
            bookSourceGroup = it.joinToString(",")
        }
        return this
    }

    fun hasGroup(group: String): Boolean {
        bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.toHashSet()?.let {
            return it.indexOf(group) != -1
        }
        return false
    }

    fun removeInvalidGroups() {
        removeGroup(getInvalidGroupNames())
    }

    fun removeErrorComment() {
        bookSourceComment = bookSourceComment
            ?.split("\n\n")
            ?.filterNot {
                it.startsWith("// Error: ")
            }?.joinToString("\n")
    }

    fun addErrorComment(e: Throwable) {
        bookSourceComment =
            "// Error: ${e.message}" + if (bookSourceComment.isNullOrBlank())
                "" else "\n\n${bookSourceComment}"
    }

    fun getCheckKeyword(default: String): String {
        searchRule.checkKeyWord?.let {
            if (it.isNotBlank()) {
                return it
            }
        }
        return default
    }

    fun getInvalidGroupNames(): String {
        return bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.toHashSet()?.filter {
            "失效" in it || it == "校验超时"
        }?.joinToString() ?: ""
    }

    fun equal(source: BookSource): Boolean {
        return equal(bookSourceName, source.bookSourceName)
                && equal(bookSourceUrl, source.bookSourceUrl)
                && equal(bookSourceGroup, source.bookSourceGroup)
                && bookSourceType == source.bookSourceType
                && equal(bookUrlPattern, source.bookUrlPattern)
                && equal(bookSourceComment, source.bookSourceComment)
                && customOrder == source.customOrder
                && enabled == source.enabled
                && enabledExplore == source.enabledExplore
            && enabledReview == source.enabledReview
                && enabledCookieJar == source.enabledCookieJar
                && equal(variableComment, source.variableComment)
                && equal(concurrentRate, source.concurrentRate)
                && equal(jsLib, source.jsLib)
                && equal(header, source.header)
                && equal(loginUrl, source.loginUrl)
                && equal(loginUi, source.loginUi)
                && equal(loginCheckJs, source.loginCheckJs)
                && equal(coverDecodeJs, source.coverDecodeJs)
                && equal(exploreUrl, source.exploreUrl)
            && equal(exploreScreen, source.exploreScreen)
            && exploreStyle == source.exploreStyle
                && equal(searchUrl, source.searchUrl)
            && searchRule == source.searchRule
            && exploreRule == source.exploreRule
            && bookInfoRule == source.bookInfoRule
            && tocRule == source.tocRule
            && contentRule == source.contentRule
            && reviewRule == source.reviewRule
    }

    private fun equal(a: String?, b: String?) = a == b || (a.isNullOrEmpty() && b.isNullOrEmpty())

    companion object {
        /** [exploreStyle] 低 3 位掩码：列数（0/1 单列，2..6 N 列网格） */
        const val EXPLORE_STYLE_COLS_MASK = 0x07

        /** [exploreStyle] 视频布局标志位 */
        const val EXPLORE_STYLE_VIDEO_FLAG = 0x10

        fun exploreStyleIsVideo(style: Int) = style and EXPLORE_STYLE_VIDEO_FLAG != 0
        fun exploreStyleCols(style: Int) = style and EXPLORE_STYLE_COLS_MASK
    }
}
