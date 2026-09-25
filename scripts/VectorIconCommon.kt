// VectorIconCommon.kt
//
// Android VectorDrawable (app/src/main/res/drawable/*.xml) → jsvg 渲染的公共实现。
//
// 由 GenerateIosIcons.kt 与 GenerateOhosIcons.kt 共用: 两个脚本只负责各自的画布尺寸、
// 坐标换算、图层组合与输出路径, 矢量→SVG 转换与 jsvg 绘制全部收在本文件。
//
// 两个脚本都要与本文件一起编译 (见各脚本头部的 javac/kotlinc 命令)。

package scripts.vectoricon

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import org.w3c.dom.Element
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 把 Android vector XML 渲染进 [g]。
 *
 * 调用方负责预先设好画布变换 (缩放/平移) 与渲染 hint; 本函数只做 XML→SVG→绘制。
 * 加载失败即 error 早失败 (不静默跳过, 否则图标会缺一块而脚本照报成功)。
 */
fun renderSvg(g: Graphics2D, androidVectorXml: String) {
    val svg = toSvg(androidVectorXml)
    val loader = SVGLoader()
    val doc = loader.load(
        ByteArrayInputStream(svg.toByteArray(StandardCharsets.UTF_8)), null,
        LoaderContext.createDefault()
    ) ?: error("jsvg failed to load svg")
    doc.render(null, g, null)
}

// ===== Android vector XML -> SVG =====

private fun toSvg(xml: String): String {
    val clean = xml
        .replace(Regex("xmlns:android=\"[^\"]*\""), "")
        .replace(Regex("xmlns:aapt=\"[^\"]*\""), "")
        .replace(Regex("android:([A-Za-z]+)\\s*="), "$1=")
        .replace("<aapt:attr", "<attr")
        .replace("</aapt:attr>", "</attr>")
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(clean.toByteArray(StandardCharsets.UTF_8)))
    val root = doc.documentElement
    val vw = root.getAttribute("viewportWidth").ifEmpty { "108" }
    val vh = root.getAttribute("viewportHeight").ifEmpty { "108" }
    val sb = StringBuilder()
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(vw)
        .append("\" height=\"").append(vh)
        .append("\" viewBox=\"0 0 ").append(vw).append(' ').append(vh).append("\">")
    val defs = mutableListOf<String>()
    val gradCounter = intArrayOf(0)
    for (child in elementChildren(root)) appendNode(sb, child, gradCounter, defs)
    // SVG 允许前向引用 url(#id), 定义统一收在尾部
    for (def in defs) sb.append(def)
    sb.append("</svg>")
    return sb.toString()
}

private fun appendNode(
    sb: StringBuilder,
    el: Element,
    gradCounter: IntArray,
    defs: MutableList<String>
) {
    when (el.tagName) {
        "group" -> {
            sb.append("<g")
            val t = groupTransform(el)
            if (t != null) sb.append(" transform=\"").append(t).append("\"")
            sb.append('>')
            for (c in elementChildren(el)) appendNode(sb, c, gradCounter, defs)
            sb.append("</g>")
        }

        "path" -> {
            sb.append("<path d=\"").append(el.getAttribute("pathData")).append('"')
            var gradId: String? = null
            for (c in elementChildren(el)) {
                if (c.tagName == "attr") {
                    val grad = firstChildTag(c, "gradient")
                    if (grad != null) gradId = appendGradient(defs, grad, gradCounter)
                }
            }
            if (gradId != null) {
                sb.append(" fill=\"url(#").append(gradId).append(")\"")
            } else {
                val fill = el.getAttribute("fillColor")
                if (fill.isNotEmpty()) sb.append(" fill=\"").append(normColor(fill)).append('"')
            }
            if (el.getAttribute("fillType") == "evenOdd") sb.append(" fill-rule=\"evenodd\"")
            val stroke = el.getAttribute("strokeColor")
            if (stroke.isNotEmpty()) {
                sb.append(" stroke=\"").append(normColor(stroke)).append('"')
                val sw = el.getAttribute("strokeWidth")
                if (sw.isNotEmpty()) sb.append(" stroke-width=\"").append(sw).append('"')
                val lj = el.getAttribute("strokeLineJoin")
                if (lj.isNotEmpty()) sb.append(" stroke-linejoin=\"").append(lj).append('"')
                val lc = el.getAttribute("strokeLineCap")
                if (lc.isNotEmpty()) sb.append(" stroke-linecap=\"").append(lc).append('"')
            }
            sb.append("/>")
        }

        else -> { /* 忽略 */
        }
    }
}

private fun appendGradient(defs: MutableList<String>, g: Element, counter: IntArray): String {
    val id = "g" + counter[0]++
    val d = StringBuilder()
    d.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\"")
        .append(" x1=\"").append(g.getAttribute("startX"))
        .append("\" y1=\"").append(g.getAttribute("startY"))
        .append("\" x2=\"").append(g.getAttribute("endX"))
        .append("\" y2=\"").append(g.getAttribute("endY")).append("\">")
    for (item in elementChildren(g)) {
        if (item.tagName == "item") {
            d.append("<stop offset=\"").append(item.getAttribute("offset"))
                .append("\" stop-color=\"").append(normColor(item.getAttribute("color")))
                .append("\"/>")
        }
    }
    d.append("</linearGradient>")
    defs.add(d.toString())
    return id
}

/**
 * Android group 变换顺序 (对照 AOSP VectorDrawableCompat):
 * translate(tx+px, ty+py) * rotate * scale * translate(-px, -py)
 */
private fun groupTransform(el: Element): String? {
    val tx = num(el.getAttribute("translateX"), 0.0)
    val ty = num(el.getAttribute("translateY"), 0.0)
    val sx = num(el.getAttribute("scaleX"), 1.0)
    val sy = num(el.getAttribute("scaleY"), 1.0)
    val rot = num(el.getAttribute("rotation"), 0.0)
    val px = num(el.getAttribute("pivotX"), 0.0)
    val py = num(el.getAttribute("pivotY"), 0.0)
    if (tx == 0.0 && ty == 0.0 && sx == 1.0 && sy == 1.0 && rot == 0.0) return null
    val t = AffineTransform()
    t.translate(tx + px, ty + py)
    t.rotate(Math.toRadians(rot))
    t.scale(sx, sy)
    t.translate(-px, -py)
    return String.format(
        Locale.ROOT, "matrix(%s %s %s %s %s %s)",
        fmt(t.scaleX), fmt(t.shearY), fmt(t.shearX),
        fmt(t.scaleY), fmt(t.translateX), fmt(t.translateY)
    )
}

private fun num(s: String, dflt: Double): Double = s.toDoubleOrNull() ?: dflt

private fun fmt(v: Double): String =
    if (v == Math.rint(v) && Math.abs(v) < 1e9) String.format(Locale.ROOT, "%.0f", v)
    else String.format(Locale.ROOT, "%.4f", v)

/** #AARRGGBB -> #RRGGBB (SVG 不支持 8 位色值; 本工程渐变/填充均为 FF 不透明) */
private fun normColor(c: String): String {
    val s = c.trim()
    return if (s.startsWith("#") && s.length == 9) "#" + s.substring(3) else s
}

private fun elementChildren(el: Element): List<Element> {
    val out = mutableListOf<Element>()
    val nl = el.childNodes
    for (i in 0 until nl.length) {
        val n = nl.item(i)
        if (n.nodeType == org.w3c.dom.Node.ELEMENT_NODE) out.add(n as Element)
    }
    return out
}

private fun firstChildTag(el: Element, tag: String): Element? =
    elementChildren(el).firstOrNull { it.tagName == tag }
