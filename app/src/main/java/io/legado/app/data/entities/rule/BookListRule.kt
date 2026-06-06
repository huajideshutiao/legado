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
}