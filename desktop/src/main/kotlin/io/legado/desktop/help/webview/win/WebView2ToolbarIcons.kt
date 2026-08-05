package io.legado.desktop.help.webview.win

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * 工具栏图标: 复用项目 composeResources 矢量 XML (ic_arrow_back/ic_arrow_forward/
 * ic_refresh_black_24dp/ic_more_vert), 渲染为 ARGB BufferedImage 供 ImageList 使用。
 *
 * 调研结论 (2026-08-06): desktop 模块无 Res.* 生成类, 但矢量 XML 在 classpath
 * (composeResources/legado.shared.generated.resources/drawable/), 用 CMP 的
 * [PathParser] 解析 pathData → [PathNode] → java.awt.Path2D → 抗锯齿填充。
 * 纯 JDK 实现, WebView2 线程 (无 AWT EDT 依赖) 可用。
 */
internal object ToolbarIcons {

    /** 资源名 → 解析后的 Path2D (24dp 视口, 解析一次缓存)。 */
    private val pathCache = HashMap<String, Path2D.Float>()

    /** 渲染指定资源为 [size]x[size] ARGB 图 (主题色深灰, 由宿主窗口背景保证可见)。 */
    fun render(name: String, size: Int, color: Int = 0xFF1F2329.toInt()): BufferedImage? {
        val path = pathCache.getOrPut(name) { parse(name) ?: return null }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            // 24dp 矢量 → size 像素 (居中)
            val scale = size / 24f
            val offset = ((size - 24 * scale) / 2).toDouble()
            g.translate(offset, offset)
            g.scale(scale.toDouble(), scale.toDouble())
            g.color = Color(color, true)
            g.fill(path)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun parse(name: String): Path2D.Float? {
        val xml = readResource(name) ?: return null
        val pathData = Regex("android:pathData=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)
            ?: return null
        val nodes = runCatching { PathParser().parsePathString(pathData).toNodes() }
            .onFailure { io.legado.app.constant.AppLog.put("工具栏图标解析失败 $name: ${it.message}") }
            .getOrNull() ?: return null
        return runCatching { toPath2D(nodes) }
            .onFailure { io.legado.app.constant.AppLog.put("工具栏图标转 Path2D 失败 $name: ${it.message}") }
            .getOrNull()
    }

    private fun toPath2D(nodes: List<PathNode>): Path2D.Float {
        val path = Path2D.Float()
        var lastX = 0f
        var lastY = 0f
        for (node in nodes) {
            when (node) {
                is PathNode.MoveTo -> {
                    path.moveTo(node.x, node.y)
                    lastX = node.x; lastY = node.y
                }

                is PathNode.LineTo -> {
                    path.lineTo(node.x, node.y)
                    lastX = node.x; lastY = node.y
                }

                is PathNode.CurveTo -> {
                    path.curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                    lastX = node.x3; lastY = node.y3
                }

                is PathNode.ReflectiveCurveTo -> {
                    // CMP 1.10: ReflectiveCurveTo(x1, y1, x2, y2) = 反射控制点 + 控制点2 + 终点
                    val cx1 = 2 * lastX - node.x1
                    val cy1 = 2 * lastY - node.y1
                    path.curveTo(cx1, cy1, node.x1, node.y1, node.x2, node.y2)
                    lastX = node.x2; lastY = node.y2
                }

                is PathNode.QuadTo -> {
                    path.quadTo(node.x1, node.y1, node.x2, node.y2)
                    lastX = node.x2; lastY = node.y2
                }

                is PathNode.Close -> path.closePath()

                // toNodes() 已把相对指令转绝对, 其余类型 (Arc/Relative*/H/V) 兜底跳过
                else -> Unit
            }
        }
        return path
    }

    private fun readResource(name: String): String? {
        val path = "/composeResources/legado.shared.generated.resources/drawable/$name"
        return runCatching {
            Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }
}

/**
 * 标准 ToolbarWindow32 控件需要的 JNA 接口 (comctl32/uxtheme/gdi32 补充声明,
 * jna-platform 5.17 未覆盖; 模式参考 ToolbarUser32)。
 */
internal object ComCtl32 {

    // comctl32
    internal interface ComCtl : StdCallLibrary {
        fun InitCommonControlsEx(lpInitCtrls: Pointer): Boolean
        fun ImageList_Create(cx: Int, cy: Int, flags: Int, cInitial: Int, cGrow: Int): Pointer
        fun ImageList_Add(himl: Pointer, hbmImage: Pointer, hbmMask: Pointer): Int
        fun ImageList_Destroy(himl: Pointer): Boolean
    }

    // uxtheme
    internal interface UxTheme : StdCallLibrary {
        fun SetWindowTheme(hwnd: WinDef.HWND, pszSubAppName: String?, pszSubIdList: String?): Int
    }

    // gdi32: CreateDIBSection (jna-platform Gdi32 未覆盖 DIB 版)
    internal interface DibGdi32 : StdCallLibrary {
        fun CreateDIBSection(
            hdc: Pointer,
            pbmi: Pointer,
            usage: Int,
            ppvBits: PointerByReference,
            hSection: Pointer,
            offset: Int
        ): Pointer

        fun DeleteObject(obj: Pointer): Boolean
    }

    internal val comctl: ComCtl by lazy {
        com.sun.jna.Native.load("comctl32", ComCtl::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
    internal val uxtheme: UxTheme by lazy {
        com.sun.jna.Native.load("uxtheme", UxTheme::class.java, W32APIOptions.UNICODE_OPTIONS)
    }
    internal val gdi: DibGdi32 by lazy {
        com.sun.jna.Native.load("gdi32", DibGdi32::class.java, W32APIOptions.UNICODE_OPTIONS)
    }

    // 常量
    internal const val ICC_BAR_CLASSES = 0x00000004
    internal const val ILC_COLOR32 = 0x00000020
    internal const val ILC_MASK = 0x00000001
    internal const val BI_RGB = 0
    internal const val DIB_RGB_COLORS = 0
}

/** TBBUTTON (commctrl.h), x64 = 32 字节。iString 为 TB_ADDSTRING 返回的字符串索引。
 * 注意: JNA 5.17 的 Structure 不是泛型类 (泛型已移除), 不能用 Structure<T>; */
@Structure.FieldOrder(
    "iBitmap",
    "idCommand",
    "fsState",
    "fsStyle",
    "bReserved",
    "dwData",
    "iString"
)
internal class TBBUTTON : Structure() {
    @JvmField
    var iBitmap: Int = 0
    @JvmField
    var idCommand: Int = 0
    @JvmField
    var fsState: Byte = 0
    @JvmField
    var fsStyle: Byte = 0
    @JvmField
    var bReserved: ByteArray = ByteArray(6)
    @JvmField
    var dwData: Long = 0
    @JvmField
    var iString: Long = 0
}
