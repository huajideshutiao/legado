package io.legado.app.data.entities.rule

/**
 * 书籍列表规则
 */
interface BookListRule {
    var bookList: String?
    var name: String?
    var author: String?
    var intro: String?
    var kind: String?
    var lastChapter: String?
    var updateTime: String?
    var bookUrl: String?
    var coverUrl: String?
    var wordCount: String?

    /**是否还有下一页（请求级 JS 规则，result 为整页响应 body）**/
    var hasMoreRule: String?

    /**下一页地址规则 (从当前页响应里取下一页 URL; 未配置则按页码翻页)**/
    var nextPageUrl: String?
}