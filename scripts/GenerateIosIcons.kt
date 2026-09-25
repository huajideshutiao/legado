// GenerateIosIcons.kt
//
// 将 Android VectorDrawable (app/src/main/res/drawable/*.xml) 栅格化为 iOS 图标 PNG。
// 矢量→SVG 转换与 jsvg 绘制在 VectorIconCommon.kt (与 GenerateOhosIcons.kt 共用),
// 本文件只管 iOS 的画布尺寸、坐标换算、图层组合与输出路径。
//
// 合成规则 (先画 background 再画 foreground, 同为 108x108 viewport):
//   AppIcon (默认) = ic_launcher1_b_new + ic_launcher1   (对照 mipmap-anydpi-v26/ic_launcher.xml)
//   Icon1          = ic_launcher1_b     + ic_launcher1   (对照 mipmap-anydpi-v26/launcher1.xml)
//   Icon4          = ic_launcher4_b     + ic_launcher4   (对照 mipmap-anydpi-v26/launcher4.xml)
//   Icon5          = #FAFAFA (md_grey_50) + ic_launcher6 (对照 mipmap-anydpi-v26/launcher5.xml)
//
// 可见窗口: 层图按 108 viewport 全幅绘制后整体放大 1.5 倍, 可见窗口 = 层坐标的中央
// 72/108。AOSP AdaptiveIconDrawable 的三条依据 —— EXTRA_INSET_PERCENTAGE = 1/4f,
// DEFAULT_VIEW_PORT_SCALE = 1/(1 + 2*EXTRA_INSET_PERCENTAGE) = 2/3,
// updateLayerBoundsInternal 里 insetWidth = bounds.width()/(DEFAULT_VIEW_PORT_SCALE*2)
// = 0.75 * width (子层 bounds 因而比 viewport 大 1.5 倍)。
// 注意 72/108 与官方文档的 "66x66 safe zone" 不是同一个数: 66x66 是更严格的设计建议
// 安全区 (内容不该超出), 72/108 才是系统遮罩的裁切边界, 本脚本按后者取窗口。
//
// 输出 1024x1024 无透明通道 PNG 到 iosApp/ (iOS 交替图标要求 bundle 内无 alpha 图标)。
// 图标值 -> bundle 名的映射与 shared/iosMain IosPlatformCapabilities.changeLauncherIcon 一致。
//
// 本脚本是备用工具: 图标资源基本不改, 只有换 launcher 图标或改 drawable 里的矢量时
// 才需要手工重跑一次, 没有 Gradle/CI 调用点。重跑后必须把 iosApp/*.png 一并提交。
//
// 运行 (仓库根目录; 需 jsvg 2.0.0 + slf4j-api 2.0.17 于 classpath, 均来自 Gradle 缓存,
// 是 desktop 构建的正式依赖; 有 kotlinc 直接用 kotlinc, 无则用 kotlin-compiler-embeddable):
//   KOTLIN_CACHE=~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin
//   K2JVM=org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
//   DEPS="<jsvg-2.0.0.jar>;<slf4j-api-2.0.17.jar>;<kotlin-stdlib-2.3.20.jar>"
//   COMPILER_CP="<kotlin-compiler-embeddable-2.3.20.jar>;<kotlin-stdlib>;<kotlin-reflect>;<kotlin-script-runtime>;<trove4j>;<kotlinx-coroutines-core-jvm>;<kotlinx-collections-immutable-jvm>;<annotations>"
//   java -cp "$COMPILER_CP" $K2JVM -cp "$DEPS" -d scripts/ios-icons-out \
//        scripts/VectorIconCommon.kt scripts/GenerateIosIcons.kt
//   java -cp "$DEPS;scripts/ios-icons-out" scripts.vectoricon.GenerateIosIconsKt

package scripts.vectoricon

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

private const val SIZE = 1024
private const val VIEW = 108.0

/** 自适应图层可见窗口边长 (层坐标单位): 108 的中央 72/108 */
private const val VISIBLE_VIEW = 72.0

/** 层坐标原点平移到可见窗口左上角的位移 (层坐标单位): (108 - 72) / 2 */
private const val LAYER_INSET = (VIEW - VISIBLE_VIEW) / 2

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
        // 层图 -> 可见窗口: 缩放 SIZE/VISIBLE_VIEW (等价于 1024/108 再放大 1.5 倍),
        // 再把层坐标原点平移到可见窗口左上角 (translate 在 scale 之后书写,
        // 因此作用在层坐标上: 屏幕坐标 = (层坐标 - LAYER_INSET) * SIZE/VISIBLE_VIEW)
        g.scale(SIZE / VISIBLE_VIEW, SIZE / VISIBLE_VIEW)
        g.translate(-LAYER_INSET, -LAYER_INSET)
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
        check(ImageIO.write(flat, "png", out.toFile())) { "no png writer for $out" }
        println("wrote $out")
    }
}

private fun parseColor(c: String): Color = Color(Integer.parseInt(c.removePrefix("#"), 16))
