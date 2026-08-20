package io.legado.app.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.model.CoverGlyph
import io.legado.app.model.computeCoverTextLayout

/**
 * 默认封面上的竖排书名/作者 (1:1 复刻 app 端 CoverImageView.drawNameAuthor)。
 *
 * 布局算法在 commonMain 的 [computeCoverTextLayout], 与 Android 端共用同一份;
 * 这里只用 Compose drawText 消费 glyph —— 先白色描边再 accent 填充, 同原版 drawTextWithStroke。
 * 文字测量在 drawWithCache 的缓存阶段完成, 尺寸不变时不重测。
 *
 * 底图由调用方铺 (用户图集烘焙图或内置 image_cover_default), 本组件只叠字。
 */
@Composable
internal fun CoverNameAuthorOverlay(
    name: String?,
    author: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val appConfig = remember { AppConfigProviders.get() }
    val drawName = appConfig.coverDrawBookName
    val drawAuthor = appConfig.coverDrawBookAuthor
    // 剥书名括号内容, 对齐原版 load() 的 AppPattern.bdRegex 处理
    val cleanName = remember(name) { name?.replace(AppPattern.bdRegex, "")?.trim() }
    val cleanAuthor = remember(author) { author?.replace(AppPattern.bdRegex, "")?.trim() }
    if (!drawName && !drawAuthor) return
    if (cleanName.isNullOrBlank() && cleanAuthor.isNullOrBlank()) return
    val measurer = rememberTextMeasurer()
    Box(
        modifier.drawWithCache {
            // 每字只测一份布局, 描边/填充两趟共用; drawStyle 在绘制时显式指定, 不烘进 measure
            // drawWithCache 的缓存块在每次重组都会失效重跑, 故按尺寸+文案做一层跨条目的共享缓存
            val prepared = CoverGlyphCache.getOrPut(
                CoverGlyphKey(
                    width = size.width,
                    height = size.height,
                    name = cleanName,
                    author = cleanAuthor,
                    drawName = drawName,
                    drawAuthor = drawAuthor,
                    density = density,
                    fontScale = fontScale,
                )
            ) {
                val glyphs = computeCoverTextLayout(
                    width = size.width,
                    height = size.height,
                    name = cleanName,
                    author = cleanAuthor,
                    drawName = drawName,
                    drawAuthor = drawAuthor,
                    // 原版 textHeight = descent - ascent + leading, 单行实测高度等价;
                    // 书名粗体 / 作者常规, 对照原版 namePaint(DEFAULT_BOLD) 与 authorPaint(DEFAULT)
                    textHeightOf = { px ->
                        measurer.measure("测", glyphStyle(px.toSp(), FontWeight.Bold))
                            .size.height.toFloat()
                    },
                    authorTextHeightOf = { px ->
                        measurer.measure("测", glyphStyle(px.toSp(), FontWeight.Normal))
                            .size.height.toFloat()
                    },
                )
                glyphs.map { g ->
                    // 作者列非粗体 (原版 authorPaint.typeface = Typeface.DEFAULT)
                    val weight = if (g.isAuthor) FontWeight.Normal else FontWeight.Bold
                    PreparedGlyph(g, measurer.measure(g.text, glyphStyle(g.textSize.toSp(), weight)))
                }
            }
            onDrawWithContent {
                drawContent()
                prepared.forEach { p ->
                    // 原版 textAlign=CENTER 且 y 为基线; Compose drawText 以左上角为锚,
                    // 故按 firstBaseline (而非整段高度) 回退
                    val topLeft = Offset(
                        p.glyph.x - p.layout.size.width / 2f,
                        p.glyph.y - p.layout.firstBaseline,
                    )
                    // 先白描边再 accent 填充, 同原版 drawTextWithStroke (同一 layout 两趟)。
                    // 两趟都必须显式给 drawStyle: Android 的 AndroidTextPaint.setDrawStyle(null)
                    // 直接 return 不重置, 省略第二趟会残留上一趟的 STROKE, accent 变描边把白边盖掉
                    drawText(
                        p.layout, color = Color.White, topLeft = topLeft,
                        drawStyle = Stroke(width = p.glyph.strokeWidth),
                    )
                    drawText(p.layout, color = accent, topLeft = topLeft, drawStyle = Fill)
                }
            }
        }
    )
}

private fun glyphStyle(fontSize: TextUnit, weight: FontWeight) = TextStyle(
    fontSize = fontSize,
    fontWeight = weight,
    textAlign = TextAlign.Center,
)

private class PreparedGlyph(
    val glyph: CoverGlyph,
    val layout: TextLayoutResult,
)

private data class CoverGlyphKey(
    val width: Float,
    val height: Float,
    val name: String?,
    val author: String?,
    val drawName: Boolean,
    val drawAuthor: Boolean,
    val density: Float,
    val fontScale: Float,
)

/**
 * 已测好的默认封面文字布局, 跨条目共享 (列表回滚复用, 不重测)。
 * 只在绘制线程访问; 超出容量按插入序淘汰最旧的一份。
 */
private object CoverGlyphCache {

    private const val MAX_ENTRIES = 64
    private val entries = LinkedHashMap<CoverGlyphKey, List<PreparedGlyph>>()

    fun getOrPut(key: CoverGlyphKey, build: () -> List<PreparedGlyph>): List<PreparedGlyph> {
        entries[key]?.let { return it }
        val value = build()
        if (entries.size >= MAX_ENTRIES) {
            entries.keys.firstOrNull()?.let(entries::remove)
        }
        entries[key] = value
        return value
    }
}
