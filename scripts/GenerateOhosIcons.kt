// GenerateOhosIcons.kt
//
// 将 Android VectorDrawable (app/src/main/res/drawable/*.xml) 栅格化为鸿蒙分层图标 PNG。
// 矢量→SVG 转换与 jsvg 绘制在 VectorIconCommon.kt (与 GenerateIosIcons.kt 共用),
// 本文件只管鸿蒙的画布尺寸、坐标换算、图层拆分与输出路径。
//
// 分层图标规范 (OpenHarmony 官方文档 zh-cn/design/ux-design/visual-app-icons.md):
//   "格式 png / 尺寸 288px*288px / 内容 一张前景层+一张背景层"
//   "可见区域：前景层和背景层叠加后, 取中间 2/3 区域显示, 尺寸为 192px*192px"
// 交付件 = 层空间本身 (108 viewport 全幅), 系统取其中央 2/3 作可见区 —— 与 Android
// AdaptiveIconDrawable 同义 (后者可见窗口也是层空间的中央 72/108 = 2/3), 只是 Android
// 由系统做那 1.5 倍放大, 鸿蒙的交付件按 288 直出即可。
//
// 与 GenerateIosIcons.kt 的坐标口径差异 (刻意, 不要互相套用公式):
//   - 本脚本: 缩放 SIZE/VIEW, 输出完整层空间 288x288, 由系统裁中央 2/3
//   - iOS 脚本: 缩放 SIZE/VISIBLE_VIEW 再平移, 直接输出可见窗口 1024x1024
//
// 输出 (AppScope/resources/base/media/):
//   app_icon_background.png = ic_launcher1_b_new           (对照 mipmap-anydpi-v26/ic_launcher.xml 的 background)
//   app_icon_foreground.png = ic_launcher1 叠加在透明底上  (同文件的 foreground)
// 两层分别独立输出: 前景层必须透明底 (系统要与背景层合成), 故不做压平。
//
// ⚠️ entry/src/main/module.json5 的 startWindowIcon 也指向 app_icon_foreground.png,
// 而启动窗口图不做分层图那中央 2/3 裁切, 直接整图显示 —— 本脚本输出的前景层内容只占
// 层空间中央一小块, 用作启动图会显得偏小。要改启动图观感须另出一张内容铺满的图,
// 不要靠改本脚本的缩放。
//
// 本脚本是备用工具: 图标资源基本不改, 只有换 launcher 图标或改 drawable 里的矢量时
// 才需要手工重跑一次, 没有 Gradle/CI 调用点。重跑后必须把
// ohosApp/AppScope/resources/base/media/app_icon_*.png 一并提交。
//
// 运行 (仓库根目录; 依赖与编译方式同 GenerateIosIcons.kt):
//   java -cp "$COMPILER_CP" $K2JVM -cp "$DEPS" -d scripts/ohos-icons-out \
//        scripts/VectorIconCommon.kt scripts/GenerateOhosIcons.kt
//   java -cp "$DEPS;scripts/ohos-icons-out" scripts.vectoricon.GenerateOhosIconsKt

package scripts.vectoricon

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO

private const val SIZE = 288
private const val VIEW = 108.0

fun main() {
    val res = Paths.get("app", "src", "main", "res")
    val outDir = Paths.get("ohosApp", "AppScope", "resources", "base", "media")
    Files.createDirectories(outDir)
    // 两层分开输出: 背景层不透明 (书页底), 前景层透明底 (薯条 + 文字)
    render(res, outDir, "app_icon_background.png", "drawable/ic_launcher1_b_new.xml", null)
    render(res, outDir, "app_icon_foreground.png", null, "drawable/ic_launcher1.xml")
}

/**
 * 渲染单层。
 *
 * 层图按 108 viewport 全幅绘制, 整体缩放 SIZE/VIEW。超出可见窗口 (中央 2/3) 的内容
 * 照常画在画布内, 由系统遮罩裁切。
 */
private fun render(
    res: java.nio.file.Path,
    outDir: java.nio.file.Path,
    fileName: String,
    bgDrawable: String?,
    fgDrawable: String?,
) {
    val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.scale(SIZE / VIEW, SIZE / VIEW)
    if (bgDrawable != null) {
        renderSvg(g, String(Files.readAllBytes(res.resolve(bgDrawable)), StandardCharsets.UTF_8))
    }
    if (fgDrawable != null) {
        renderSvg(g, String(Files.readAllBytes(res.resolve(fgDrawable)), StandardCharsets.UTF_8))
    }
    g.dispose()
    val out = outDir.resolve(fileName)
    check(ImageIO.write(img, "png", out.toFile())) { "no png writer for $out" }
    println("wrote $out")
}
