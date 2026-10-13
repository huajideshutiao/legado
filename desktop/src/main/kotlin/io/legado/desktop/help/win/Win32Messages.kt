package io.legado.desktop.help.win

/**
 * 系统设置变更消息号。
 *
 * 系统切换深/浅色、改系统参数等都会向所有顶层窗口广播本条消息 (不携带是深还是浅,
 * 具体值要自己去读)。窗口原生消息桥 (wndchrome) 的编译期白名单里放行了它, 见 wndchrome.c
 * 的 is_hook_msg。
 *
 * JNA 的 WinUser 接口未声明本常量 (只声明了 WM_THEMECHANGED 等), 故按 WinUser.h 取值。
 */
const val WM_SETTINGCHANGE = 0x001A
