// GenerateIosIcons.kt
//
// 将 Android VectorDrawable (app/src/main/res/drawable/*.xml) 栅格化为 iOS 图标 PNG。
// 原 Java 版 scripts/ios_icons/VectorToPng.java 的 Kotlin 移植 (转换语义逐行一致)。
//
// 合成规则 (与 Android 自适应图标一致: 先画 background 再画 foreground, 同为 108x108 viewport):
//   AppIcon (默认) = ic_launcher1_b_new + ic_launcher1   (对照 mipmap-anydpi-v26/ic_launcher.xml)
//   Icon1          = ic_launcher1_b     + ic_launcher1   (对照 mipmap-anydpi-v26/launcher1.xml)
//   Icon4          = ic_launcher4_b     + ic_launcher4   (对照 mipmap-anydpi-v26/launcher4.xml)
//   Icon5          = #FAFAFA (md_grey_50) + ic_launcher6 (对照 mipmap-anydpi-v26/launcher5.xml)
//
// 输出 1024x1024 无透明通道 PNG 到 iosApp/ (iOS 交替图标要求 bundle 内无 alpha 图标)。
// 图标值 -> bundle 名的映射与 shared/iosMain IosPlatformCapabilities.changeLauncherIcon 一致。
//
// 运行 (仓库根目录; 需 jsvg 2.0.0 + slf4j-api 2.0.17 于 classpath, 均来自 Gradle 缓存,
// 是 desktop 构建的正式依赖; 有 kotlinc 直接用 kotlinc, 无则用 kotlin-compiler-embeddable):
//   KOTLIN_CACHE=~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin
//   K2JVM=org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
//   DEPS="<jsvg-2.0.0.jar>;<slf4j-api-2.0.17.jar>;<kotlin-stdlib-2.3.20.jar>"
//   COMPILER_CP="<kotlin-compiler-embeddable-2.3.20.jar>;<kotlin-stdlib>;<kotlin-reflect>;<kotlin-script-runtime>;<trove4j>;<kotlinx-coroutines-core-jvm>;<kotlinx-collections-immutable-jvm>;<annotations>"
//   java -cp "$COMPILER_CP" $K2JVM -cp "$DEPS" -d scripts/ios-icons-out scripts/GenerateIosIcons.kt
//   java -cp "$DEPS;scripts/ios-icons-out" GenerateIosIconsKt

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import org.w3c.dom.Element
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

private const val SIZE = 1024
private const val VIEW = 108.0

private data class Combo(
    val name: String,
    val bgDrawable: String?,
    val bgColor: String?,
    val fgDrawable: String,
)

fun main() {
    val res = Paths.get("app", "src", "main", "res")
    val combos = listOf(
        Combo("AppIcon", "drawable/ic_launcher1_b_new.xml", null, "drawable/ic_launcher1.xml"),
        Combo("Icon1", "drawable/ic_launcher1_b.xml", null, "drawable/ic_launcher1.xml"),
        Combo("Icon4", "drawable/ic_launcher4_b.xml", null, "drawable/ic_launcher4.xml"),
        Combo("Icon5", null, "#FAFAFA", "drawable/ic_launcher6.xml"),
    )
    for (combo in combos) {
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.scale(SIZE / VIEW, SIZE / VIEW)
        if (combo.bgColor != null) {
            g.color = parseColor(combo.bgColor)
            g.fillRect(0, 0, VIEW.toInt(), VIEW.toInt())
        } else {
            renderSvg(
                g,
                String(Files.readAllBytes(res.resolve(combo.bgDrawable!!)), StandardCharsets.UTF_8)
            )
        }
        renderSvg(
            g,
            String(Files.readAllBytes(res.resolve(combo.fgDrawable)), StandardCharsets.UTF_8)
        )
        g.dispose()
        // 铺白底压平: iOS 交替图标不允许 alpha 通道
        val flat = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        val fg = flat.createGraphics()
        fg.color = Color.WHITE
        fg.fillRect(0, 0, SIZE, SIZE)
        fg.drawImage(img, 0, 0, null)
        fg.dispose()
        val out = Paths.get("iosApp", "${combo.name}.png")
        ImageIO.write(flat, "png", out.toFile())
        println("wrote $out  (opaque=${isOpaque(flat)})")
    }
}

private fun isOpaque(img: BufferedImage): Boolean {
    for (y in 0 until img.height step 7) {
        for (x in 0 until img.width step 7) {
            if (img.getRGB(x, y).ushr(24) != 0xFF) return false
        }
    }
    return true
}

private fun renderSvg(g: Graphics2D, androidVectorXml: String) {
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

private fun num(s: String, dflt: Double): Double =
    s.toDoubleOrNull() ?: dflt

private fun fmt(v: Double): String =
    if (v == Math.rint(v) && Math.abs(v) < 1e9) String.format(Locale.ROOT, "%.0f", v)
    else String.format(Locale.ROOT, "%.4f", v)

/** #AARRGGBB -> #RRGGBB (SVG 不支持 8 位色值; 本工程渐变/填充均为 FF 不透明) */
private fun normColor(c: String): String {
    val s = c.trim()
    return if (s.startsWith("#") && s.length == 9) "#" + s.substring(3) else s
}

private fun parseColor(c: String): Color = Color(Integer.parseInt(c.removePrefix("#"), 16))

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
