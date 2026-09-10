package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.entities.column.BaseColumn

/**
 * 列工厂接口（平台/排版器实现注入）。
 *
 * 列构造依赖排版器自身状态（段评占位符与图片占位符常量、段评计数 map、imgList 嵌入图片队列），
 * 不下沉 commonMain。[TextLayoutEngine] 与 [PaginationEngine] 通过本接口调用，
 * 实现「纯算术面下沉 + 列构造留调用方」。
 *
 * imgList 由调用方在每次 addCharsToLineNatural/Middle 前注入并维护（removeFirst 副作用），
 * 实现只在 char 是图片占位符时取出下一项。
 */
interface ColumnFactory {

    /**
     * 创建列（无段号）。判断 char 类型：
     * - 段评占位符 → ReviewColumn（段号由实现方自行维护）
     * - 图片占位符且 imgList 非空 → ImageColumn（从 imgList 取出 src/onclick）
     * - 其他 → TextColumn
     *
     * 单相排版通道（[TextLayoutEngine]）走本重载，段号由实现方按段推进；
     * 两阶段管线（[PaginationEngine]）没有「当前段号」状态，一律走带 [paragraphIndex] 的重载。
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

    /**
     * 创建列（带逻辑段号）。[PaginationEngine] 分页切片时传入当前行的真实段号，
     * 保证段评气泡（ReviewColumn）绑定准确的段落序号与评论数。
     *
     * 默认实现丢弃段号退回无段号重载，只对「自己维护段号」的实现（如单相排版通道）成立；
     * 产出 ReviewColumn 又要接 [PaginationEngine] 的实现必须重写本重载。
     */
    fun createColumn(
        absStartX: Int,
        char: String,
        xStart: Float,
        xEnd: Float,
        imgList: MutableList<ImgData>?,
        paragraphIndex: Int,
    ): BaseColumn = createColumn(absStartX, char, xStart, xEnd, imgList)
}
