package io.legado.app.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
 * 唯一保留的平台差异是底图: Android 走 image_cover_default.9.png (9-patch 拉伸区,
 * Compose 无等价能力), 其他端走 accent 纯色底。
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
            val glyphs = computeCoverTextLayout(
                width = size.width,
                height = size.height,
                name = cleanName,
                author = cleanAuthor,
                drawName = drawName,
                drawAuthor = drawAuthor,
                // 原版 textHeight = descent - ascent + leading, 单行实测高度等价
                textHeightOf = { px ->
                    measurer.measure("测", TextStyle(fontSize = px.toSp())).size.height.toFloat()
                },
            )
            val prepared = glyphs.map { g ->
                val style = TextStyle(
                    fontSize = g.textSize.toSp(),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                PreparedGlyph(
                    glyph = g,
                    stroke = measurer.measure(
                        g.text,
                        style.copy(drawStyle = Stroke(width = g.strokeWidth)),
                    ),
                    fill = measurer.measure(g.text, style),
                )
            }
            onDrawWithContent {
                drawContent()
                prepared.forEach { p ->
                    // 原版 textAlign=CENTER 且 y 为基线; Compose drawText 以左上角为锚, 故回退
                    val topLeft = Offset(
                        p.glyph.x - p.fill.size.width / 2f,
                        p.glyph.y - p.fill.size.height,
                    )
                    drawText(p.stroke, color = Color.White, topLeft = topLeft)
                    drawText(p.fill, color = accent, topLeft = topLeft)
                }
            }
        }
    )
}

private class PreparedGlyph(
    val glyph: CoverGlyph,
    val stroke: TextLayoutResult,
    val fill: TextLayoutResult,
)
