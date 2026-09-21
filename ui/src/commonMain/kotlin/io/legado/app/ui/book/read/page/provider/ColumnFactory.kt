package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.entities.column.BaseColumn

/**
 * 列工厂接口（平台/排版器实现注入）。
 *
 * 列构造依赖排版器自身状态（段评占位符与图片占位符常量、段评计数 map、imgList 嵌入图片队列），
 * 故留给调用方实现，[PaginationEngine] 只通过本接口拿列。
 *
 * imgList 由调用方在每次 addCharsToLineNatural/Middle 前注入并维护（removeFirst 副作用），
 * 实现只在 char 是图片占位符时取出下一项。
 */
interface ColumnFactory {

    /**
     * 创建列（带逻辑段号）。[PaginationEngine] 分页切片时传入当前行的真实段号，
     * 保证段评气泡（ReviewColumn）绑定准确的段落序号与评论数。
     *
     * @param absStartX 列绝对起始 X（含 paddingLeft）
     * @param char 字素簇字符串
     * @param xStart 列相对起始 X（相对 absStartX）
     * @param xEnd 列相对结束 X
     * @param imgList 嵌入图片队列（可为 null）；char 为图片占位符时取出下一项（removeFirst 副作用）
     * @param paragraphIndex 逻辑段号
     * @param drawOffsetX 绘制 X 偏移（px），仅 [io.legado.app.ui.book.read.page.entities.column.TextColumn]
     *   消费（标点挤压裁左半），图片/段评列忽略
     */
    fun createColumn(
        absStartX: Int,
        char: String,
        xStart: Float,
        xEnd: Float,
        imgList: MutableList<ImgData>?,
        paragraphIndex: Int,
        drawOffsetX: Float = 0f,
    ): BaseColumn
}
