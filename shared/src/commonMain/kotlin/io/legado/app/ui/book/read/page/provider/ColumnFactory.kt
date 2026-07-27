package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.entities.column.BaseColumn

/**
 * 列工厂接口（app 端实现注入）。
 *
 * createColumn 的实现依赖 android 端状态（ChapterProvider.reviewChar/srcReplaceChar 常量、
 * reviewCountMap 段评计数、currentParagraphIndex 当前段号、imgList 嵌入图片队列），
 * 不下沉 commonMain。TextLayoutEngine 通过本接口调用，实现「纯算术面下沉 + 平台构造留 app」。
 *
 * imgList 由调用方在每次 addCharsToLineNatural/Middle 前通过实现注入并维护（removeFirst 副作用）。
 * 实现 internal 持有 imgList 引用，createColumn 在 char 是图片占位符时取出下一项。
 */
interface ColumnFactory {

    /**
     * 创建列。判断 char 类型：
     * - 段评占位符 → ReviewColumn（带 currentParagraphIndex 和 reviewCount）
     * - 图片占位符且 imgList 非空 → ImageColumn（从 imgList 取出 src/onclick）
     * - 其他 → TextColumn
     *
     * @param absStartX 列绝对起始 X（含 paddingLeft）
     * @param char 字素簇字符串
     * @param xStart 列相对起始 X（相对 absStartX）
     * @param xEnd 列相对结束 X
     * @param imgList 嵌入图片队列（可为 null）；char 为图片占位符时取出下一项（removeFirst 副作用）
     */
    fun createColumn(
        absStartX: Int,
        char: String,
        xStart: Float,
        xEnd: Float,
        imgList: MutableList<ImgData>?
    ): BaseColumn
}
