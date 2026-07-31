package io.legado.app.model

/**
 * 默认封面上竖排书名/作者的布局计算 (1:1 下沉 app 端 CoverImageView.recordNameAuthor)。
 *
 * 只做纯计算, 不碰绘制 API: 平台提供字体高度度量 (textHeightOf), 拿到 [CoverGlyph] 列表后
 * 各自用 Canvas.drawText / DrawScope.drawText 消费。算法因此四端共用一份。
 */
data class CoverGlyph(
    val text: String,
    val x: Float,
    val y: Float,
    val textSize: Float,
    val strokeWidth: Float,
    /** true = 作者段, false = 书名段; 供两支 paint 不同的平台 (Android BOLD/DEFAULT) 分流绘制。 */
    val isAuthor: Boolean = false,
)

/**
 * @param textHeightOf 给定字号返回书名行高 (原版 = fontMetrics.descent - ascent + leading)
 * @param authorTextHeightOf 作者行高; 默认同 [textHeightOf], Android 端需传 authorPaint 的度量
 */
fun computeCoverTextLayout(
    width: Float,
    height: Float,
    name: String?,
    author: String?,
    drawName: Boolean,
    drawAuthor: Boolean,
    textHeightOf: (textSize: Float) -> Float,
    authorTextHeightOf: (textSize: Float) -> Float = textHeightOf,
): List<CoverGlyph> {
    if (width <= 0f || height <= 0f) return emptyList()
    val nameArr = if (drawName) name?.toCharStrings() else null
    val authorArr = if (drawAuthor) author?.toCharStrings() else null
    if (nameArr.isNullOrEmpty() && authorArr.isNullOrEmpty()) return emptyList()

    val out = ArrayList<CoverGlyph>()
    val topMargin = height * 0.05f
    val bottomMargin = height * 0.95f

    nameArr?.let { nameList ->
        var textSize = width / 6
        var lineHeight = textHeightOf(textSize)
        var colX = width * 0.1f + textSize / 2
        var curY = topMargin + lineHeight
        var colNum = 1
        for (i in nameList.indices) {
            val isLastChar = i == nameList.size - 1
            val isLastSlotInColumn = curY + lineHeight > bottomMargin
            if (colNum == 3 && isLastSlotInColumn && !isLastChar) {
                out += CoverGlyph("…", colX, curY, textSize, textSize / 5)
                break
            }
            out += CoverGlyph(nameList[i], colX, curY, textSize, textSize / 5)
            if (isLastChar) continue
            if (isLastSlotInColumn) {
                colNum++
                colX += textSize
                // 换列后字号收窄到 width/10 (原版同款)
                textSize = width / 10
                lineHeight = textHeightOf(textSize)
                val remaining = nameList.size - i - 1
                val neededHeight = remaining * lineHeight
                curY = if (neededHeight < (bottomMargin - topMargin)) {
                    (height - neededHeight) / 2 + lineHeight
                } else {
                    topMargin + lineHeight
                }
            } else {
                curY += lineHeight
            }
        }
    }

    authorArr?.let { authorList ->
        val textSize = width / 10
        val lineHeight = authorTextHeightOf(textSize)
        val colX = width * 0.85f
        val neededHeight = authorList.size * lineHeight
        var curY = maxOf(height * 0.95f - neededHeight, height * 0.2f)
        for (char in authorList) {
            curY = maxOf(curY, topMargin + lineHeight)
            if (curY > height * 0.98f) break
            out += CoverGlyph(char, colX, curY, textSize, textSize / 5, isAuthor = true)
            curY += lineHeight
        }
    }
    return out
}

/** 按码点切分 (对照 app 端 CharSequence.toStringArray, 代理对不代理拆散) */
private fun String.toCharStrings(): List<String> {
    val out = ArrayList<String>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        val isSurrogatePair = c.code in 0xD800..0xDBFF &&
            i + 1 < length && this[i + 1].code in 0xDC00..0xDFFF
        if (isSurrogatePair) {
            out += substring(i, i + 2)
            i += 2
        } else {
            out += c.toString()
            i++
        }
    }
    return out
}
