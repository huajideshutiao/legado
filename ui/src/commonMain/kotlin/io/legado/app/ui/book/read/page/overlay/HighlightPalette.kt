package io.legado.app.ui.book.read.page.overlay

import androidx.compose.ui.graphics.Color

/**
 * 划线回显与关键词高亮共用的色档表 (集中管理, 回显绘制与管理页选色同源)。
 *
 * 背景色块半透明覆盖在正文上, 只调字底不压字形; 浅色/深色阅读背景下统一取
 * 0x50 档 alpha 的浅色系, 两类底色下均可读。颜色不参与 measure, 只在绘制期覆盖。
 */
object HighlightPalette {

    /** 色档总数 (colorIndex 合法范围 0 until SIZE) */
    const val SIZE = 5

    /** 0 黄 */
    val YELLOW = Color(0x50FFE082)

    /** 1 绿 */
    val GREEN = Color(0x50A5D6A7)

    /** 2 蓝 */
    val BLUE = Color(0x5090CAF9)

    /** 3 粉 */
    val PINK = Color(0x50F48FB1)

    /** 4 紫 */
    val PURPLE = Color(0x50CE93D8)

    /** 下标取色; 越界回落 0 档 (存档数据可能来自旧版本或外部备份) */
    fun colorOf(index: Int): Color = when (index) {
        1 -> GREEN
        2 -> BLUE
        3 -> PINK
        4 -> PURPLE
        else -> YELLOW
    }
}
