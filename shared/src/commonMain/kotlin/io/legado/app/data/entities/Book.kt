package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverter
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.TypeConverter
import androidx.room3.TypeConverters
import io.legado.app.api.controller.ReadBookStateProviders
import io.legado.app.constant.BookType
import io.legado.app.data.AppDbProviders
import io.legado.app.help.book.BookHelpChapterLocator
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.addType
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.resolveImagePath
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

// 不使用 import java.time.LocalDate, 用同 package 的 expect class LocalDate
// (commonMain 定义 expect, androidMain/jvmMain actual typealias 到 java.time.LocalDate)

// @TypeConverters 双重注册:
//   1. Book 实体上 (ENTITY 作用域): 处理 Book.readConfig 自身字段类型转换 (JVM target KSP 处理顺序需要)
//   2. AppDatabase 上 (DATABASE 作用域): 处理 BookChapter.ForeignKey 跨实体解析时的 ReadConfig 类型链 (iOS/ohos KSP 需要)
// 缺一不可: 只在 AppDatabase 上会导致 JVM KSP 处理 Book 实体时找不到 converter;
//          只在 Book 上会导致 iOS/ohos KSP 处理 ForeignKey 跨实体时不应用 converter
//
// 3.0.1 KSP 处理器认 ColumnTypeConverters, 鸿蒙 CPF fork (alpha01 API) 的 KSP 处理器认
// TypeConverters, 两者并存双注册 (非鸿蒙构建的旧名注解声明见 nonOhosCompatMain)。
@TypeConverters(Book.Converters::class)
@Serializable
@Entity(
    tableName = "books",
    indices = [Index(value = ["name", "author"], unique = true)]
)
data class Book(
    // 详情页Url(本地书源存储完整文件路径)
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    override var bookUrl: String = "",
    // 目录页Url (toc=table of Contents)
    @ColumnInfo(defaultValue = "")
    override var tocUrl: String = "",
    // 书源URL(默认BookType.local)
    @ColumnInfo(defaultValue = BookType.localTag)
    override var origin: String = BookType.localTag,
    //书源名称 or 本地书籍文件名
    @ColumnInfo(defaultValue = "")
    override var originName: String = "",
    // 书籍名称(书源获取)
    @ColumnInfo(defaultValue = "")
    override var name: String = "",
    // 作者名称(书源获取)
    @ColumnInfo(defaultValue = "")
    override var author: String = "",
    // 分类信息(书源获取)
    override var kind: String? = null,
    // 分类信息(用户修改)
    var customTag: String? = null,
    // 封面Url(书源获取)
    override var coverUrl: String? = null,
    // 封面Url(用户修改)
    var customCoverUrl: String? = null,
    // 简介内容(书源获取)
    override var intro: String? = null,
    // 简介内容(用户修改)
    var customIntro: String? = null,
    // 自定义字符集名称(仅适用于本地书籍)
    var charset: String? = null,
    // 类型,详见BookType
    @ColumnInfo(defaultValue = "0")
    override var type: Int = BookType.text,
    // 自定义分组索引号
    @ColumnInfo(defaultValue = "0")
    var group: Long = 0,
    // 最新章节标题
    override var latestChapterTitle: String? = null,
    // 最新章节标题更新时间
    @ColumnInfo(defaultValue = "0")
    var latestChapterTime: Long = systemCurrentTimeMillis(),
    // 最近一次更新书籍信息的时间
    @ColumnInfo(defaultValue = "0")
    var lastCheckTime: Long = systemCurrentTimeMillis(),
    // 最近一次发现新章节的数量
    @ColumnInfo(defaultValue = "0")
    var lastCheckCount: Int = 0,
    // 书籍目录总数
    @ColumnInfo(defaultValue = "0")
    var totalChapterNum: Int = 0,
    // 当前章节名称
    var durChapterTitle: String? = null,
    // 当前章节索引
    @ColumnInfo(defaultValue = "0")
    var durChapterIndex: Int = 0,
    // 当前阅读的进度(首行字符的索引位置)
    @ColumnInfo(defaultValue = "0")
    var durChapterPos: Int = 0,
    // 最近一次阅读书籍的时间(打开正文的时间)
    @ColumnInfo(defaultValue = "0")
    var durChapterTime: Long = systemCurrentTimeMillis(),
    //字数
    override var wordCount: String? = null,
    // 刷新书架时更新书籍信息
    @ColumnInfo(defaultValue = "1")
    var canUpdate: Boolean = true,
    // 手动排序
    @ColumnInfo(defaultValue = "0")
    var order: Int = 0,
    //书源排序
    @ColumnInfo(defaultValue = "0")
    override var originOrder: Int = 0,
    // 自定义书籍变量信息(用于书源规则检索书籍信息)
    override var variable: String? = null,
    //阅读设置
    var readConfig: ReadConfig? = null,
    //同步时间
    @ColumnInfo(defaultValue = "0")
    var syncTime: Long = 0L
) : BaseBook {

    // 2026-08 定案: 不用原版"仅 bookUrl 相等"语义, 改用 data class 默认全字段 equals。
    // 根因: 书架/搜索命中等状态用 StateFlow 去重 + distinctUntilChanged 过滤, Book.equals
    // 只比 bookUrl 会把"同一本书的进度/章节/最新章更新"判为相同, 发射被吞, 书架永不刷新
    // (实测 Room 失效重查正常, 全被 equals 去重层滤掉)。
    // 依赖"同书"语义的调用点全仓已核实: 集合操作均是字段谓词/bookUrl 显式比较, 无整
    // Book equals 依赖 (核对日期同上)。hashCode 随 data class 自动生成, 与 equals 一致。

    @delegate:Ignore
    override val variableMap: HashMap<String, String> by lazy {
        decodeStringMapOrNull(variable) ?: hashMapOf()
    }

    @Ignore
    @Transient
    override var infoHtml: String? = null

    @Ignore
    @Transient
    override var tocHtml: String? = null

    @Ignore
    @Transient
    var downloadUrls: List<String>? = null

    @Ignore
    @Transient
    private var folderName: String? = null

    @get:Ignore
    val lastChapterIndex get() = totalChapterNum - 1

    /**
     * 展示用封面: 自定义封面优先, 否则书源封面。
     *
     * 手动选图的封面存**图集内部相对引用** (`covers/<字节数>.<ext>`, 见
     * [io.legado.app.ui.book.changecover.CoverStorageService]), 这里统一解析为绝对路径供加载端使用;
     * 网络地址与旧数据绝对路径由 [resolveImagePath] 原样透传。
     * 需要存储原值 (编辑框回显/导出) 的地方用 [getDisplayCoverRef]。
     */
    fun getDisplayCover() = resolveImagePath(getDisplayCoverRef())

    /** 展示用封面的**存储原值** (可能是图集相对引用), 编辑回显/导出用, 不做路径解析。 */
    fun getDisplayCoverRef() = customCoverUrl.takeUnless { it.isNullOrEmpty() } ?: coverUrl

    fun getDisplayIntro() = customIntro.takeUnless { it.isNullOrEmpty() } ?: intro

    val config: ReadConfig
        get() = readConfig ?: ReadConfig().also { readConfig = it }

    fun getStartDate(): LocalDate? {
        if (!config.readSimulating || config.startDate == null) {
            // java.time.LocalDate.now() 在 commonMain 经 expect fun 桥接 (无 companion object)
            return localDateNow()
        }
        return config.startDate
    }

    fun getStartChapter(): Int {
        if (config.readSimulating) return config.startChapter ?: 0
        return this.durChapterIndex
    }
    fun getFolderName(): String {
        folderName?.let {
            return it
        }
        //防止书名过长,只取9位
        folderName = getFolderNameNoCache()
        return folderName!!
    }

    fun toSearchBook() = SearchBook(
        name = name,
        author = author,
        kind = kind,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        type = type,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        coverUrl = coverUrl,
        intro = intro,
        tocUrl = tocUrl,
        originOrder = originOrder,
        variable = variable
    ).apply {
        this.infoHtml = this@Book.infoHtml
        this.tocHtml = this@Book.tocHtml
    }

    fun save() {
        removeType(BookType.notShelf)
        runBlocking {
            if (AppDbProviders.get().bookDao.has(bookUrl)) {
                AppDbProviders.get().bookDao.update(this@Book)
            } else {
                AppDbProviders.get().bookDao.insert(this@Book)
            }
        }
    }

    /**
     * 仅 PATCH 进度字段; 避免阅读/播放界面退出时整行 update 冲掉
     * 后台 updateToc/refreshBookInfo 写入的最新元数据 (name/intro/cover/totalChapterNum 等)。
     */
    fun saveRead() {
        lastCheckCount = 0
        durChapterTime = systemCurrentTimeMillis()
        runBlocking {
            AppDbProviders.get().bookDao.updateProgress(
                bookUrl,
                durChapterIndex,
                durChapterPos,
                durChapterTime,
                durChapterTitle
            )
        }
        ReadTimeRecorder.flushAll()
    }

    fun delete() {
        // ReadBook.book 单例经 provider 解耦, 删除当前阅读书时清空阅读状态
        val readBookProvider = ReadBookStateProviders.getOrNull()
        if (readBookProvider != null && readBookProvider.currentBookUrl == bookUrl) {
            readBookProvider.clearCurrentBook()
        }
        runBlocking { AppDbProviders.get().bookDao.delete(this@Book) }
        addType(BookType.notShelf)
    }

    fun getUseReplaceRule(): Boolean {
        return config.useReplaceRule
            ?: (!isImage && !isEpub && AppConfigProviders.get().replaceEnableDefault)
    }

    fun getUnreadChapterNum(): Int =
        (simulatedTotalChapterNum() - durChapterIndex + if (durChapterPos < 0) -1 else 0)
            .coerceAtLeast(0)

    fun migrateTo(newBook: Book, toc: List<BookChapter>): Book {
        newBook.durChapterIndex = BookHelpChapterLocator
            .getDurChapter(durChapterIndex, durChapterTitle, toc, totalChapterNum)
        newBook.durChapterTitle = toc[newBook.durChapterIndex].getDisplayTitle(
            ContentProcessorProviders.get().getTitleReplaceRules(newBook),
            getUseReplaceRule()
        )
        newBook.durChapterPos = durChapterPos
        newBook.durChapterTime = durChapterTime
        newBook.group = group
        newBook.order = order
        newBook.customCoverUrl = customCoverUrl
        newBook.customIntro = customIntro
        newBook.customTag = customTag
        newBook.canUpdate = canUpdate
        newBook.readConfig = readConfig
        return newBook
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val hTag = 2L
        const val rubyTag = 4L
        const val imgStyleDefault = "DEFAULT"
        const val imgStyleFull = "FULL"
        const val imgStyleText = "TEXT"
        const val imgStyleSingle = "SINGLE"
    }

    @Serializable
    data class ReadConfig(
        var reverseToc: Boolean = false,
        //var pageAnim: Int? = null,
        var reSegment: Boolean = false,
        var imageStyle: String? = null,
        var useReplaceRule: Boolean? = null,// 正文使用净化替换规则
        var delTag: Long = 0L,//去除标签
        var ttsEngine: String? = null,
        var splitLongChapter: Boolean = true,
        var readSimulating: Boolean = false,
        @Serializable(with = LocalDateAsGsonSerializer::class)
        var startDate: LocalDate? = null,
        var startChapter: Int? = null,     // 用户设置的起始章节
        var dailyChapters: Int = 3    // 用户设置的每日更新章节数
    )

    /**
     * 兼容 GSON 旧格式: LocalDate 曾被反射序列化成 {"year":Y,"month":M,"day":D}
     */
    object LocalDateAsGsonSerializer : KSerializer<LocalDate> {
        @Serializable
        private data class Surrogate(val year: Int, val month: Int, val day: Int)

        override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

        override fun serialize(encoder: Encoder, value: LocalDate) {
            // commonMain 不能直接访问 java.time.LocalDate 的 year/monthValue/dayOfMonth 属性
            // (actual typealias 到 Java 类时 getter 不被识别为 expect class val 成员), 改用 expect 扩展函数
            val (year, month, day) = value.toYearMonthDay()
            encoder.encodeSerializableValue(
                Surrogate.serializer(),
                Surrogate(year, month, day)
            )
        }

        override fun deserialize(decoder: Decoder): LocalDate {
            val s = decoder.decodeSerializableValue(Surrogate.serializer())
            // java.time.LocalDate.of(...) 在 commonMain 经 expect fun 桥接 (无 companion object)
            return localDateOf(s.year, s.month, s.day)
        }
    }

    class Converters {

        @ColumnTypeConverter
        @TypeConverter
        fun readConfigToString(config: ReadConfig?): String? {
            if (config == null || config == ReadConfig()) return null
            return readConfigJson.encodeToString(config)
        }

        @ColumnTypeConverter
        @TypeConverter
        fun stringToReadConfig(json: String?): ReadConfig? {
            json ?: return null
            return kotlin.runCatching {
                readConfigJson.decodeFromString<ReadConfig>(json)
            }.getOrNull()
        }

        companion object {
            /**
             * ignoreUnknownKeys: 容忍已删除/未来新增字段(如注释掉的 pageAnim)。
             * 默认值齐全 → 天然容忍缺字段, 读全部 GSON 旧行。
             * encodeDefaults 保持默认 false: 只写非默认字段, 输出紧凑(读兼容即可, 不追字节等同 GSON)。
             */
            private val readConfigJson = Json {
                ignoreUnknownKeys = true
            }
        }
    }
}
