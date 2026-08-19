package io.legado.desktop.help.win

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * comctl32 最小绑定 (进度条 + ImageList): WebView2 工具栏进度条
 * (ICC_PROGRESS_CLASS) 与任务栏缩略图按钮图标 (ImageList_Create/Add/Destroy)
 * 原在 WebView2ToolbarIcons / DesktopTaskbarMedia 分处声明, 收敛于此。
 * ToolbarWindow32/TBBUTTON 相关声明随 2026-08-06 旧工具栏实现移除
 * (JVM 进程内 comctl32 v6 预加载导致不可用, 见 WebView2Toolbar)。
 */
internal object ComCtl32 {

    internal interface ComCtl : StdCallLibrary {
        fun InitCommonControlsEx(lpInitCtrls: Pointer): Boolean
        fun ImageList_Create(cx: Int, cy: Int, flags: Int, cInitial: Int, cGrow: Int): Pointer?
        fun ImageList_Add(himl: Pointer, hbmImage: WinDef.HBITMAP, hbmMask: WinDef.HBITMAP?): Int
        fun ImageList_Destroy(himl: Pointer): Boolean
    }

    internal val INSTANCE: ComCtl by lazy {
        Native.load("comctl32", ComCtl::class.java, W32APIOptions.UNICODE_OPTIONS)
    }

    /** 进度条控件类 (InitCommonControlsEx 的 dwICC)。 */
    internal const val ICC_PROGRESS_CLASS = 0x00000020
}
