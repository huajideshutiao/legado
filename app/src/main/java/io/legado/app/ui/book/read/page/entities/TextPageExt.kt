package io.legado.app.ui.book.read.page.entities

/**
 * TextPage.getTextChapter() 扩展函数（app 端实现）。
 *
 * 原 TextPage 成员方法 `fun getTextChapter(): TextChapter` 在数据类下沉 commonMain 时去掉
 * （TextChapter 不下沉 commonMain，故 commonMain 不能返回 TextChapter 类型）。
 * 本扩展函数沿用原实现，调用方代码不变（仍走 `page.getTextChapter()` 扩展函数语法）。
 *
 * 字段 `textChapter` 类型为 `TextChapterRef?`，app 端 TextChapter 实现 TextChapterRef，
 * 这里强转回 TextChapter。空字符串页（loadingTextPage）的 textChapter 为 null，
 * 调用方需自行判空。
 */
fun TextPage.getTextChapter(): TextChapter = textChapter as TextChapter
