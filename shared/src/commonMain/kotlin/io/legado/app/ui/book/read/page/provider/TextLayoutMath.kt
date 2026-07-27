package io.legado.app.ui.book.read.page.provider

/**
 * 排版纯算术面（无 android 依赖，已下沉 shared commonMain）：图片适配尺寸 + 字素簇聚合。
 * TextChapterLayout 调用，跨端共用。
 */

/**
 * 图片按最大宽高等比缩放进可视区内。
 */
fun getFitSize(rawW: Float, rawH: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
    var w = rawW
    var h = rawH
    if (w > maxW) {
        h = h * maxW / w; w = maxW
    }
    if (h > maxH) {
        w = w * maxH / h; h = maxH
    }
    return w to h
}

/**
 * 字素簇聚合结果：words 为逐簇字符串，widths 为逐簇宽度（取簇首字宽）。
 */
class TextSplit(val words: ArrayList<String>, val widths: ArrayList<Float>)

/**
 * 按逐字宽度数组把 [text] 聚成字素簇：宽度>0 为簇首，其后宽度==0 且非零宽控制字符的字符并入同簇
 * （组合字符/emoji 代理对）。零宽控制字符（ZWSP/ZWNJ/ZWJ/WJ）自成簇边界。
 * @param widthsArray 逐字宽度，[start] 为其在数组中的起点偏移。
 */
fun measureTextSplit(text: String, widthsArray: FloatArray, start: Int = 0): TextSplit {
    val length = text.length
    var clusterCount = 0
    for (i in start..<start + length) if (widthsArray[i] > 0) clusterCount++
    val widths = ArrayList<Float>(clusterCount)
    val stringList = ArrayList<String>(clusterCount)
    var i = 0
    while (i < length) {
        val clusterBaseIndex = i++; widths.add(widthsArray[start + clusterBaseIndex])
        while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) i++
        stringList.add(text.substring(clusterBaseIndex, i))
    }
    return TextSplit(stringList, widths)
}

private fun isZeroWidthChar(char: Char): Boolean {
    val code = char.code
    return code == 8203 || code == 8204 || code == 8205 || code == 8288
}

/**
 * 可视区几何公式集（纯算术，下沉 shared commonMain，跨端共用）。
 * 原 ChapterProvider.upLayout/setFallbackLayout 中的几何算式抽取于此，
 * 配置入口（AppConfig/ReadBookConfig/appCtx 等）仍留 androidMain。
 */

/** 计算可视区宽度（双页模式取半宽）。纯算术，跨端共用。 */
fun calcVisibleWidth(viewWidth: Int, paddingLeft: Int, paddingRight: Int, doublePage: Boolean): Int =
    if (doublePage) viewWidth / 2 - paddingLeft - paddingRight
    else viewWidth - paddingLeft - paddingRight

/** 计算可视区高度（留 1dp 画最后一行下划线的算式与原 upLayout 一致）。纯算术。 */
fun calcVisibleHeight(viewHeight: Int, paddingTop: Int, paddingBottom: Int): Int =
    viewHeight - paddingTop - paddingBottom

/** 计算可视区右边界。纯算术。 */
fun calcVisibleRight(viewWidth: Int, paddingRight: Int): Int =
    viewWidth - paddingRight

/** 计算可视区下边界。纯算术。 */
fun calcVisibleBottom(paddingTop: Int, visibleHeight: Int): Int =
    paddingTop + visibleHeight
