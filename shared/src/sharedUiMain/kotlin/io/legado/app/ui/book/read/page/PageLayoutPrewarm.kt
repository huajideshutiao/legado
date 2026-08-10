package io.legado.app.ui.book.read.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import io.legado.app.ui.book.read.page.entities.TextPage

/** 常用汉字集量级的 TextMeasurer 内部 LRU 容量（默认仅 8，对千列级页面等于全程失效） */
private const val READER_TEXT_MEASURER_CACHE_SIZE = 1024

/** 阅读页共享测量器（阅读屏根处 CompositionLocalProvider 提供）；未提供时消费点自建，预览/独立使用不受影响 */
val LocalReaderTextMeasurer = staticCompositionLocalOf<TextMeasurer?> { null }

/**
 * 阅读页正文测量器。
 *
 * [rememberTextMeasurer] 默认内部缓存仅 8 条，对一字一列的千列级页面等于全程失效；
 * 这里放大到常用汉字集量级，让翻页窗口内重复 measure 命中内部 LRU
 * （与 [TextLayoutCache] 逐列缓存互为两层）。
 * 优先取 [LocalReaderTextMeasurer]（阅读页统一共享实例），未提供时自建。
 */
@Composable
fun rememberReaderTextMeasurer(): TextMeasurer =
    LocalReaderTextMeasurer.current
        ?: rememberTextMeasurer(cacheSize = READER_TEXT_MEASURER_CACHE_SIZE)

/**
 * 正文 layout 缓存预热：把候选页的逐列 TextLayoutResult 增量构建到
 * [io.legado.app.ui.book.read.page.entities.TextPage.textLayoutCache]
 * （与 [PageContentCanvas] 的取或建挂载同一挂载点），翻页入场页组合时
 * 命中缓存零 measure，消除首帧拖动阻塞（对照原版三个 PageView 常驻、
 * 已 measure/layout，setDirection 只录一次 display list）。
 * 同时负责滑出窗口页的缓存回收（与原版 ReadBook.recycleRecorders 同窗口口径）。
 *
 * 线程：留在主 dispatcher，靠 yield() 每 64 列切片——
 * Compose [TextMeasurer] 没有文档保证的线程安全，跨线程 measure 是隐蔽风险；
 * 切片的目的是既不阻塞首帧拖动、也不造成掉帧。
 *
 * @param pages 候选页列表（可为 null/空页）；重启判定按元素引用相等（见 [PageWindowKey]），
 *   调用方不必 remember 列表。
 */
@Composable
fun PageLayoutPrewarmEffect(
    pages: List<TextPage?>,
    style: ReaderDrawStyle,
    measurer: TextMeasurer,
) {
    val density = LocalDensity.current
    // 已预热页（引用相等语义：TextPage 是 data class，禁用 == / contains）
    val warmed = remember { mutableListOf<TextPage>() }
    DisposableEffect(Unit) {
        onDispose {
            for (i in warmed.indices) warmed[i].releaseLayoutCache()
            warmed.clear()
        }
    }
    LaunchedEffect(PageWindowKey(pages), style, density, measurer) {
        // 先回收滑出窗口的页（对照原版 ReadBook.recycleRecorders 只保留 [cur-1 .. cur+2]）
        warmed.removeAll { old ->
            val gone = pages.none { it === old }
            if (gone) old.releaseLayoutCache()
            gone
        }
        for (page in pages) {
            val textPage = page ?: continue
            if (warmed.none { it === textPage }) warmed.add(textPage)
            val cached = textPage.textLayoutCache
            if (cached is TextLayoutCache && cached.matches(style, density)) continue
            textPage.textLayoutCache =
                TextLayoutCache.buildIncremental(textPage, measurer, style, density)
        }
    }
}

/** 摘除并回收页的 layout 缓存（仅主线程调用：LaunchedEffect 体内/onDispose） */
private fun TextPage.releaseLayoutCache() {
    textLayoutCache?.recycle()
    textLayoutCache = null
}

/**
 * 翻页窗口的 Effect key：逐元素引用相等。
 * 不能直接拿 `List<TextPage?>` 当 key —— TextPage 是 data class，equals 深比较整页行列表，
 * 既慢，又会把"内容相同的新实例"判成同一 key（缓存挂在实例上，换实例必须重启预热）。
 */
private class PageWindowKey(val pages: List<TextPage?>) {
    override fun equals(other: Any?): Boolean {
        if (other !is PageWindowKey || other.pages.size != pages.size) return false
        for (i in pages.indices) {
            if (pages[i] !== other.pages[i]) return false
        }
        return true
    }

    /** 只参与 Effect key 的相等判定，不进哈希容器 */
    override fun hashCode(): Int = pages.size
}
