package io.legado.app.data.entities

import android.os.Parcelable
import android.text.TextUtils
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.JsonAdapter
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.splitNotBlank
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Suppress("unused")
@Parcelize
@Entity(
    tableName = "book_sources",
    indices = [(Index(value = ["bookSourceUrl"], unique = false))]
)
data class BookSource(
    // 地址，包括 http/https
    @PrimaryKey
    var bookSourceUrl: String = "",
    // 名称
    var bookSourceName: String = "",
    // 分组
    var bookSourceGroup: String? = null,
    // 类型，0 文本，1 音频, 2 图片, 3 文件（指的是类似知轩藏书只提供下载的网站）
    @BookSourceType.Type
    var bookSourceType: Int = 0,
    // 详情页url正则
    var bookUrlPattern: String? = null,
    // 手动排序编号
    @ColumnInfo(defaultValue = "0")
    var customOrder: Int = 0,
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
    override var loginUi: String? = null,
    // 登录检测js
    var loginCheckJs: String? = null,
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
    @JsonAdapter(RuleStringAdapter::class)
    var ruleExplore: String? = null,
    // 搜索url
    var searchUrl: String? = null,
    // 搜索规则
    @JsonAdapter(RuleStringAdapter::class)
    var ruleSearch: String? = null,
    // 书籍信息页规则
    @JsonAdapter(RuleStringAdapter::class)
    var ruleBookInfo: String? = null,
    // 目录页规则
    @JsonAdapter(RuleStringAdapter::class)
    var ruleToc: String? = null,
    // 正文页规则
    @JsonAdapter(RuleStringAdapter::class)
    var ruleContent: String? = null,
    // 段评规则
    @JsonAdapter(RuleStringAdapter::class)
    var ruleReview: String? = null
) : Parcelable, BaseSource {

    @Ignore
    @IgnoredOnParcel
    @Transient
    private var _searchRule: SearchRule? = null

    @Ignore
    @IgnoredOnParcel
    @Transient
    private var _exploreRule: ExploreRule? = null

    @Ignore
    @IgnoredOnParcel
    @Transient
    private var _bookInfoRule: BookInfoRule? = null

    @Ignore
    @IgnoredOnParcel
    @Transient
    private var _tocRule: TocRule? = null

    @Ignore
    @IgnoredOnParcel
    @Transient
    private var _contentRule: ContentRule? = null

    @Ignore
    @IgnoredOnParcel
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
    var searchRule: SearchRule
        get() = _searchRule ?: parseRule(ruleSearch) { SearchRule() }.also { _searchRule = it }
        set(value) {
            ruleSearch = GSON.toJson(value)
            _searchRule = value
        }

    @get:Ignore
    var exploreRule: ExploreRule
        get() = _exploreRule ?: parseRule(ruleExplore) { ExploreRule() }.also { _exploreRule = it }
        set(value) {
            ruleExplore = GSON.toJson(value)
            _exploreRule = value
        }

    @get:Ignore
    var bookInfoRule: BookInfoRule
        get() = _bookInfoRule ?: parseRule(ruleBookInfo) { BookInfoRule() }.also {
            _bookInfoRule = it
        }
        set(value) {
            ruleBookInfo = GSON.toJson(value)
            _bookInfoRule = value
        }

    @get:Ignore
    var tocRule: TocRule
        get() = _tocRule ?: parseRule(ruleToc) { TocRule() }.also { _tocRule = it }
        set(value) {
            ruleToc = GSON.toJson(value)
            _tocRule = value
        }

    @get:Ignore
    var contentRule: ContentRule
        get() = _contentRule ?: parseRule(ruleContent) { ContentRule() }.also { _contentRule = it }
        set(value) {
            ruleContent = GSON.toJson(value)
            _contentRule = value
        }

    @get:Ignore
    var reviewRule: ReviewRule
        get() = _reviewRule ?: parseRule(ruleReview) { ReviewRule() }.also { _reviewRule = it }
        set(value) {
            ruleReview = GSON.toJson(value)
            _reviewRule = value
        }

    private inline fun <reified T> parseRule(json: String?, default: () -> T): T =
        json?.takeIf { it.isNotEmpty() }
            ?.let { GSON.fromJsonObject<T>(it).getOrNull() }
            ?: default()

    fun getDisPlayNameGroup(): String {
        return if (bookSourceGroup.isNullOrBlank()) {
            bookSourceName
        } else {
            String.format("%s (%s)", bookSourceName, bookSourceGroup)
        }
    }

    fun addGroup(groups: String): BookSource {
        bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.toHashSet()?.let {
            it.addAll(groups.splitNotBlank(AppPattern.splitGroupRegex))
            bookSourceGroup = TextUtils.join(",", it)
        }
        if (bookSourceGroup.isNullOrBlank()) bookSourceGroup = groups
        return this
    }

    fun removeGroup(groups: String): BookSource {
        bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.toHashSet()?.let {
            it.removeAll(groups.splitNotBlank(AppPattern.splitGroupRegex).toSet())
            bookSourceGroup = TextUtils.join(",", it)
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
            "// Error: ${e.localizedMessage}" + if (bookSourceComment.isNullOrBlank())
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
