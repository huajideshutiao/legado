package io.legado.app.data.entities

import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule

/**
 * webBook 编排层 BookSource 只读视图接口。
 *
 * 抽出 webBook 6 个文件 (WebBook/BookList/BookInfo/BookChapterList/BookContent/BookReview)
 * 对 BookSource 的所有只读访问点, 让 shared 模块在不依赖 app 端 BookSource 实体类
 * (BookSource 继承 JsExtensions, 工作量极大, 列为 C 类不下沉) 的前提下,
 * 下沉 webBook 编排逻辑。
 *
 * BookSource (app 端) 实现本接口: 字段 var 即 override val, 方法 getBookType()/exploreKindsJson()
 * 由原扩展函数升级为成员方法 (行为不变)。
 *
 * @see BaseSource
 */
interface IBookSource : BaseSource {

    /** 地址，包括 http/https (主键) */
    val bookSourceUrl: String

    /** 名称 */
    val bookSourceName: String

    /** 详情页 url 正则 */
    val bookUrlPattern: String?

    /** 手动排序编号 */
    val customOrder: Int

    /** 登录检测 js */
    val loginCheckJs: String?

    /** 搜索 url */
    val searchUrl: String?

    /** 段评规则 JSON 字符串 (序列化形式, 反序列化经 [reviewRule] 惰性解析) */
    val ruleReview: String?

    /** 搜索规则 (惰性解析, 对应 ruleSearch) */
    val searchRule: SearchRule

    /** 发现规则 (惰性解析, 对应 ruleExplore) */
    val exploreRule: ExploreRule

    /** 详情页规则 (惰性解析, 对应 ruleBookInfo) */
    val bookInfoRule: BookInfoRule

    /** 目录页规则 (惰性解析, 对应 ruleToc) */
    val tocRule: TocRule

    /** 正文页规则 (惰性解析, 对应 ruleContent) */
    val contentRule: ContentRule

    /** 段评规则 (惰性解析, 对应 ruleReview) */
    val reviewRule: ReviewRule

    /**
     * 根据书源类型返回书籍类型位掩码 (BookType)。
     * 对应原 `BookSourceExtensions.getBookType()` 扩展函数, 行为不变;
     * 提升为成员方法以使本接口可在 shared 中暴露。
     */
    fun getBookType(): Int

    /**
     * 返回发现规则 JSON 字符串 (来自缓存或 exploreUrl)。
     * 对应原 `BookSourceExtensions.exploreKindsJson()` 扩展函数, 行为不变;
     * 提升为成员方法以使本接口可在 shared 中暴露。
     */
    fun exploreKindsJson(): String
}
