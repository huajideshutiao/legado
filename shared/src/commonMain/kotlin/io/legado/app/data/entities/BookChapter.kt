package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import io.legado.app.help.RuleBigDataProviders
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookUrl", "url"],
    foreignKeys = [(ForeignKey(
        entity = Book::class,
        parentColumns = ["bookUrl"],
        childColumns = ["bookUrl"],
        onDelete = ForeignKey.CASCADE
    ))]
)    // 删除书籍时自动删除章节
data class BookChapter(
    var url: String = "",               // 章节地址
    override var title: String = "",             // 章节标题
    var isVolume: Boolean = false,      // 是否是卷名
    var bookUrl: String = "",           // 书籍地址
    var index: Int = 0,                 // 章节序号
    var isVip: Boolean = false,         // 是否VIP
    var isPay: Boolean = false,         // 是否已购买
    var resourceUrl: String? = null,    // 音频真实URL
    var tag: String? = null,            // 更新时间或其他章节附加信息
    var wordCount: String? = null,      // 本章节字数
    var start: Long? = null,            // 章节起始位置
    var end: Long? = null,              // 章节终止位置
    var startFragmentId: String? = null,  //EPUB书籍当前章节的fragmentId
    var endFragmentId: String? = null,    //EPUB书籍下一章节的fragmentId
    var variable: String? = null        //变量
) : RuleDataInterface, BookChapterLike {

    @delegate:Transient
    @delegate:Ignore
    override val variableMap: HashMap<String, String> by lazy {
        decodeStringMapOrNull(variable) ?: hashMapOf()
    }

    @Ignore
    var titleMD5: String? = null

    override fun putVariable(key: String, value: String?): Boolean {
        if (super<RuleDataInterface>.putVariable(key, value)) {
            variable = encodeStringMap(variableMap)
        }
        return true
    }

    override fun putBigVariable(key: String, value: String?) {
        RuleBigDataProviders.impl?.putChapterVariable(bookUrl, url, key, value)
    }

    override fun getBigVariable(key: String): String? {
        return RuleBigDataProviders.impl?.getChapterVariable(bookUrl, url, key)
    }

    override fun hashCode() = url.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other is BookChapter) {
            return other.url == url
        }
        return false
    }

    fun primaryStr(): String {
        return bookUrl + url
    }

    private fun ensureTitleMD5Init() {
        if (titleMD5 == null) {
            titleMD5 = MD5Utils.md5Encode16(title)
        }
    }

    fun getFileName(suffix: String = "nb"): String {
        ensureTitleMD5Init()
        return "${index.toString().padStart(5, '0')}-$titleMD5.$suffix"
    }

    @Suppress("unused")
    fun getFontName(): String {
        ensureTitleMD5Init()
        return "${index.toString().padStart(5, '0')}-$titleMD5.ttf"
    }
}
