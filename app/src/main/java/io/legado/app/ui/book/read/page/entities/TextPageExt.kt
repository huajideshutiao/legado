package io.legado.app.ui.book.read.page.entities

/**
 * TextPage.getTextChapter() 扩展函数（app 端实现）。
 *
 * 原 TextPage 成员方法 `fun getTextChapter(): TextChapter` 在数据类下沉 commonMain 时去掉
 * （TextChapter 不下沉 commonMain，故 commonMain 不能返回 TextChapter 类型）。
 * 本扩展函数沿用原实现，调用方代码不变（仍走 `page.getTextChapter()` 扩展函数语法）。
 *
 * 字段 `textChapter` 类型为 `TextChapterRef?`，app 端 TextChapter 实现 TextChapterRef。
 * 原版声明为 `var textChapter = emptyTextChapter`（哨兵兜底、从不为 null），下沉版默认
 * null——这里 null 回落 [TextChapter.emptyTextChapter]，对齐原版 loading 页语义。
 */
fun TextPage.getTextChapter(): TextChapter =
    textChapter as? TextChapter ?: TextChapter.emptyTextChapter
