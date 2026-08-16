package io.legado.desktop.ui

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import io.legado.app.constant.AppLog
import java.awt.MouseInfo
import java.awt.Window

/**
 * Linux (X11): 把无边框窗口的拖拽/缩放**交还窗口管理器**。
 *
 * # 为什么
 * 现状是自绘控制栏 + 手写 `window.setLocation` 跟随鼠标, 这条路 WM 完全不知情, 于是丢掉了
 * 贴靠 (拖到屏幕边缘平铺)、拖动时的吸附反馈、`Alt+F7` 键盘移动、以及 WM 自己的拖动平滑。
 * 正解是发 `_NET_WM_MOVERESIZE` ClientMessage —— 相当于 Windows 上返回 `HTCAPTION`:
 * 一次交接之后, 整个拖动/缩放过程由 WM 接管。做法照 GTK4 `gdksurface-x11.c` 的 `wmspec_send_message`。
 *
 * # Wayland
 * OpenJDK 在 Linux 只有 X11 实现, Wayland 会话全部走 XWayland (原生 Wayland 仍是 Wakefield 原型,
 * 未进任何 GA), 所以只处理 X11 一套即可覆盖 GNOME/KDE × X11/XWayland 四象限。
 *
 * # 实现选择
 * 不用 jna-platform 的 `X11` 结构体/联合体封装, 而是自己声明最小接口 + 按 X11 ABI 显式偏移拼
 * ClientMessage: 本项目在 Windows 上开发, 无法真机验证 JNA union 的用法, 按偏移写是确定的。
 * 布局 (LP64): type@0(int) serial@8(ulong) send_event@16(int) display@24(ptr) window@32(XID)
 * message_type@40(Atom) format@48(int) data@56(5×long); XEvent 整体按 24×long = 192 字节。
 *
 * # 纪律
 * 任何一步失败都返回 false, 由调用方退回原有手写拖拽 —— 绝不让 Linux 比现状更差。
 * **未经 Linux 真机验证** (开发机为 Windows), 首次上 Linux 需回归: 拖动/贴靠/平铺/双击最大化。
 */
internal object LinuxWmMoveResize {

    /** `_NET_WM_MOVERESIZE` 的 direction 取值 (EWMH)。 */
    private const val MOVE = 8

    /** ClientMessage 事件类型号 (X.h)。 */
    private const val CLIENT_MESSAGE = 33

    private const val SUBSTRUCTURE_NOTIFY = 1L shl 19
    private const val SUBSTRUCTURE_REDIRECT = 1L shl 20

    private const val XEVENT_SIZE = 192L
    private const val CURRENT_TIME = 0

    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XInternAtom(display: Pointer, name: String, onlyIfExists: Int): NativeLong
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XUngrabPointer(display: Pointer, time: Int): Int
        fun XSendEvent(
            display: Pointer,
            window: NativeLong,
            propagate: Int,
            eventMask: NativeLong,
            event: Pointer,
        ): Int

        fun XFlush(display: Pointer): Int
    }

    private val xlib: Xlib? by lazy {
        if (!Platform.isX11()) null
        else runCatching { Native.load("X11", Xlib::class.java) }.getOrNull()
    }

    /**
     * 请 WM 接管窗口移动 (拖标题栏)。应在拖拽阈值触发时调用一次, 之后不要再自己 setLocation。
     *
     * @return true = 已交给 WM; false = 不可用, 调用方应退回手写拖拽
     */
    fun startMove(window: Window): Boolean = send(window, MOVE)

    private fun send(window: Window, direction: Int): Boolean {
        val lib = xlib ?: return false
        return runCatching {
            val xid = Native.getWindowID(window)   // JNA 官方 API, 无需反射 sun.awt.X11
            if (xid == 0L) return false
            val mouse = MouseInfo.getPointerInfo()?.location ?: return false
            val display = lib.XOpenDisplay(null) ?: return false
            try {
                val atom = lib.XInternAtom(display, "_NET_WM_MOVERESIZE", 0)
                if (atom.toLong() == 0L) return false
                val root = lib.XDefaultRootWindow(display)
                // 必须先放掉隐式指针抓取, 否则 WM 收到消息也拿不到指针 (GTK 同样先 ungrab)
                lib.XUngrabPointer(display, CURRENT_TIME)

                val ev = Memory(XEVENT_SIZE)
                ev.clear()
                ev.setInt(0, CLIENT_MESSAGE)          // type
                ev.setInt(16, 1)                      // send_event = True
                ev.setPointer(24, display)            // display
                ev.setLong(32, xid)                   // window
                ev.setLong(40, atom.toLong())         // message_type
                ev.setInt(48, 32)                     // format = 32
                ev.setLong(56, mouse.x.toLong())      // data.l[0] = x_root
                ev.setLong(64, mouse.y.toLong())      // data.l[1] = y_root
                ev.setLong(72, direction.toLong())    // data.l[2] = direction
                ev.setLong(80, 1L)                    // data.l[3] = button1
                ev.setLong(88, 1L)                    // data.l[4] = source = application

                val mask = NativeLong(SUBSTRUCTURE_REDIRECT or SUBSTRUCTURE_NOTIFY)
                val ok = lib.XSendEvent(display, root, 0, mask, ev) != 0
                lib.XFlush(display)
                ok
            } finally {
                runCatching { lib.XCloseDisplay(display) }
            }
        }.getOrElse {
            AppLog.putDebug("Linux WM 拖拽交接失败, 退回手写拖拽: ${it.message}")
            false
        }
    }
}
