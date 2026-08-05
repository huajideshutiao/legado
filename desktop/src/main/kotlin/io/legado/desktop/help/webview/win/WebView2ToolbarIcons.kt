package io.legado.desktop.help.webview.win

import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * comctl32 最小支撑 (2026-08-06 精简: 工具栏已改标准 Button 控件, 仅进度条需要
 * ICC_PROGRESS_CLASS; ToolbarWindow32/TBBUTTON/ImageList 相关声明随旧实现移除)。
 */
internal object ComCtl32 {

    internal interface ComCtl : StdCallLibrary {
        fun InitCommonControlsEx(lpInitCtrls: Pointer): Boolean
    }

    internal val comctl: ComCtl by lazy {
        com.sun.jna.Native.load("comctl32", ComCtl::class.java, W32APIOptions.UNICODE_OPTIONS)
    }

    internal const val ICC_PROGRESS_CLASS = 0x00000020
}
