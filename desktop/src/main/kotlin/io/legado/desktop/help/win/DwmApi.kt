package io.legado.desktop.help.win

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.legado.app.constant.AppLog
import io.legado.desktop.help.win.DwmApi.setAttributeChecked

/**
 * dwmapi.dll 公共 JNA 绑定 (DWM 窗口属性)。
 *
 * DwmSetWindowAttribute 不在 jna-platform 的 User32 里, 需自行声明
 * (对照 WindowsFileDialogs.kt 的 Shell32Ex 声明方式)。原先三处各写一份:
 * 主窗口标题栏 (ui/WindowTitleBar) / WebView2 窗口标题栏
 * (help/webview/win/WebView2WindowTheme) / 任务栏 DWM 卡片
 * (ui/tray/DesktopTaskbarDwm, 直调版), 收敛于此。
 */
internal object DwmApi {

    /**
     * dwmapi.dll 声明。
     *
     * DesktopTaskbarDwm 原先用 `Function.getFunction(..., ALT_CONVENTION)` 直调,
     * 与 StdCallLibrary 接口同为 stdcall 约定, 行为等价; 统一走接口声明。
     */
    interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            dwAttribute: Int,
            pvAttribute: IntByReference,
            cbAttribute: Int,
        ): Int

        /** 通知 DWM 重发 iconic 缩略图请求 (勿频繁调用)。 */
        fun DwmInvalidateIconicBitmaps(hwnd: WinDef.HWND): Int

        /** 回传 iconic 缩略图位图 (任务栏悬停卡片; 位图须 32bpp, DWM 持拷贝)。 */
        fun DwmSetIconicThumbnail(
            hwnd: WinDef.HWND,
            hbitmap: WinDef.HBITMAP,
            dwSITFlags: Int,
        ): Int

        /** 回传 iconic 实时预览位图 (Peek); pHotSpot 传 null 用默认热点。 */
        fun DwmSetIconicLivePreviewBitmap(
            hwnd: WinDef.HWND,
            hbitmap: WinDef.HBITMAP,
            pHotSpot: Pointer?,
            dwSITFlags: Int,
        ): Int
    }

    /**
     * 加载失败 (非 Windows / dll 缺失) 时为 null, 调用方静默退化。
     *
     * Windows 上加载失败不是预期情形 (主窗口标题栏主题/圆角/任务栏卡片全丢),
     * 要能看到原因; 非 Windows 上本就没有 dwmapi, 不记日志。
     */
    val dwmapi: Dwmapi? by lazy {
        runCatching {
            Native.load("dwmapi", Dwmapi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }.onFailure {
            if (Platform.isWindows()) AppLog.put("dwmapi.dll 加载失败 (DWM 窗口属性全部失效)", it)
        }.getOrNull()
    }

    // DWM 窗口属性常量 (dwmapi.h / Windows SDK; 33/35/36 为 Win11 22000+ 新增)
    const val DWMWA_FORCE_ICONIC_REPRESENTATION = 7
    const val DWMWA_HAS_ICONIC_BITMAP = 10
    const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    /** Win11 22H2+: 无边框窗口圆角偏好 (DWMWCP_ROUND=2 恢复圆角, DONOTROUND=1 方角)。 */
    const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    const val DWMWCP_DONOTROUND = 1
    const val DWMWCP_ROUND = 2

    /** Win11 22000+: 标题栏底色 / 标题文字色 (Win10 不认返回 E_INVALIDARG, 静默忽略)。 */
    const val DWMWA_CAPTION_COLOR = 35
    const val DWMWA_TEXT_COLOR = 36

    /**
     * 单条 DWM 属性写入 (int 值经 IntByReference 映射 int*)。
     *
     * private: 外部一律走 [setAttributeChecked] (丢弃 HRESULT 会让写失败无迹可寻);
     * dwmapi 未加载时返回 null。
     */
    private fun setAttribute(hwnd: WinDef.HWND, attribute: Int, value: Int): Int? {
        val dwm = dwmapi ?: return null
        return dwm.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), Int.SIZE_BYTES)
    }

    /**
     * 属性写入 + HRESULT 检查 (三处调用点原先各写一份检查逻辑, 收敛于此)。
     * 非零 HRESULT 不中断后续属性写入, 只记日志。
     *
     * @param tag 日志前缀, 标明哪个窗口/功能在写
     * @param critical true = 写失败即功能彻底失效 (如任务栏卡片的 iconic 开关), 走 [AppLog.put];
     *   false = 已知平台差异导致的预期失败 (如 Win10 不认 35/36), 只走 [AppLog.putDebug]
     * @return 写入成功且 HRESULT == 0 返回 true; 底层调用失败、非零 HRESULT 或 dwmapi 不可用返回 false
     */
    fun setAttributeChecked(
        hwnd: WinDef.HWND,
        attribute: Int,
        value: Int,
        tag: String,
        critical: Boolean = false,
    ): Boolean {
        val hr = setAttribute(hwnd, attribute, value) ?: return false
        if (hr == 0) return true
        val msg = "$tag: DwmSetWindowAttribute(attr=$attribute) 返回 HRESULT=0x" +
            hr.toUInt().toString(16)
        if (critical) AppLog.put(msg) else AppLog.putDebug(msg)
        return false
    }

    /**
     * ARGB → DWM COLORREF (0x00BBGGRR): 交换 R/B 字节并丢弃 alpha
     * (标题栏染色不支持透明度)。
     */
    fun argbToColorRef(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
