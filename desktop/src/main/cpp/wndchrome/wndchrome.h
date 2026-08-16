/*
 * legado_wndchrome —— Windows 窗口控制条 native 桥 (纯 C, 扁平导出 + JNA 映射)
 *
 * 设计依据 (调研见 build/research/win32-titlebar/SYNTHESIS.md):
 *  - AWT 自身的 WndProc 就是 SetWindowSubclass 挂的 (OpenJDK ComCtl32Util.cpp), 外部可再挂一层
 *  - Compose 渲染层是独立的 java.awt.Canvas 子 HWND (skiko HardwareLayer), 会吃掉客户区全部
 *    鼠标消息 ⇒ 必须双 HWND 子类化, Canvas 侧对标题栏区返回 HTTRANSPARENT
 *  - 控制条整条由 native 画在 WS_CHILD|WS_EX_LAYERED|WS_EX_TRANSPARENT 子窗口里 (JBR 同构),
 *    鼠标穿透 ⇒ 命中判定全在父窗口 WndProc, Compose 完全不参与
 *  - Win11 实测: 目标形态下 DWM 不再绘制三键 (按钮区 100% 是我们的像素), 故必须自绘;
 *    但 WM_NCHITTEST 返回 HTMAXBUTTON 仍可白拿 Snap Layouts
 *
 * 纪律 (硬性):
 *  1) hot path (WM_NCHITTEST 每次鼠标移动) 绝不回调 JVM, 命中判定全在 C 内完成
 *  2) 兜底返回值必须按消息类型分别给, 严禁统一 return 0
 *     (0 对 WM_NCHITTEST = HTNOWHERE 会让整条标题栏失灵; 对 WM_NCCALCSIZE ≈ 全窗口客户区)
 *  3) 禁改 GWL_STYLE / GWL_EXSTYLE —— DesktopFullscreenController 持有样式快照, 改了会被它还原
 *  4) 所有导出函数必须可重复调用 (幂等), 失败只置错误码不崩
 */
#ifndef LEGADO_WNDCHROME_H
#define LEGADO_WNDCHROME_H

#ifdef __cplusplus
extern "C" {
#endif

/* ---------------- 错误码 (lgchrome_last_error) ---------------- */
#define LGC_OK                 0
#define LGC_ERR_NOT_ATTACHED   1
#define LGC_ERR_BAD_HWND       2
#define LGC_ERR_SUBCLASS       3   /* SetWindowSubclass 失败 */
#define LGC_ERR_CREATE_STRIP   4   /* 控制条子窗口创建失败 (检查进程 manifest supportedOS) */
#define LGC_ERR_GDIPLUS        5   /* GdiplusStartup 失败 */
#define LGC_ERR_DWM            6   /* DwmExtendFrameIntoClientArea 失败 */
#define LGC_ERR_HOOK_FULL      7   /* 追加白名单消息的槽位已满 (见 lgchrome_add_hook_message) */

/* ---------------- 按钮标识 (命中/hover/点击共用) ---------------- */
#define LGC_BTN_NONE     0
#define LGC_BTN_MIN      1
#define LGC_BTN_MAX      2
#define LGC_BTN_CLOSE    3
#define LGC_BTN_THEME    4   /* 深浅色切换 (我们自己的键) */
#define LGC_BTN_MENU     5   /* ⋯ 菜单 (我们自己的键) */

/* ---------------- 上行回调 (低频; JNA Callback, Kotlin 侧必须强引用防 GC) ---------------- */

/*
 * 我们自己的键被点击 (LGC_BTN_THEME / LGC_BTN_MENU)。
 * x/y = 该按钮**左下角相对窗口客户区左上角**的坐标, 物理像素。
 * 选客户区相对坐标而非屏幕坐标: Kotlin 侧只需除以 density 就能直接当 Compose 偏移用,
 * 不必掺和 AWT 的 logical/physical 换算与多显示器 DPI。
 * 系统三键 (MIN/MAX/CLOSE) 由 native 直接发 WM_SYSCOMMAND, 不走此回调。
 */
typedef void (*lgchrome_action_cb)(int button, int x, int y);

/*
 * 几何/状态变化通知 (attach 成功后一次 + WM_DPICHANGED + 最大化/还原 + 主题变化)。
 * dpi          当前窗口 DPI (GetDpiForWindow)
 * captionBtnW  三键区总宽 (物理像素; 取 DWMWA_CAPTION_BUTTON_BOUNDS=5, 失败时按 SM_CXSIZE 推算)
 * maximized    1=已最大化
 */
typedef void (*lgchrome_metrics_cb)(int dpi, int captionBtnW, int maximized);

/*
 * 窗口消息钩子 (S3: 把任务栏那两层 JNA 子类化收进本桥, 三层变一层)。
 *
 * 只对**白名单低频消息**回调 JVM (任务栏缩略图请求 / thumbbar 按钮命令 / 任务栏按钮建立),
 * hot path 一律不回调。
 * 返回 1 = Kotlin 侧已处理 (C 侧直接 return 0 给 Windows); 返回 0 = 未处理, 继续沿子类化链转发。
 */
typedef int (*lgchrome_msg_cb)(unsigned int msg, long long wparam, long long lparam);

/* ---------------- 下行 API (Kotlin → C) ---------------- */

/*
 * 挂载。必须在 AWT 窗口 realize 之后、setVisible(true) 之前调用
 * (首次 WM_SIZE 之前 AWT 的 getInsets() 会用系统 metrics 兜底含标题栏高度, 早挂可把错位窗口期压到最小)。
 *
 * hwndFrame  JFrame 的 HWND (Native.getComponentID(window))
 * hwndCanvas skiko HardwareLayer (java.awt.Canvas) 的 HWND; 传 0 表示暂不挂第二层
 *            (拿不到时标题栏区拖拽会失效, 因为 Canvas 会吃掉 WM_NCHITTEST)
 * 幂等: 已挂载时返回 LGC_OK 且不重复挂。
 */
int lgchrome_attach(void *hwndFrame, void *hwndCanvas);

/* 卸载 (还原两层 subclass, 销毁控制条子窗口, 关闭 GDI+)。无状态时无害。 */
void lgchrome_detach(void);

/*
 * 注册窗口消息钩子 (可传 NULL 清除)。见 lgchrome_msg_cb。
 * 编译期白名单: WM_DWMSENDICONICTHUMBNAIL(0x0323) / WM_DWMSENDICONICLIVEPREVIEWBITMAP(0x0326) /
 * WM_COMMAND(0x0111); 运行期号见 lgchrome_add_hook_message。
 */
void lgchrome_set_message_hook(lgchrome_msg_cb cb);

/*
 * 往白名单追加一个消息号, 供 RegisterWindowMessage 这类**运行期才知道号**的消息用
 * (如 TaskbarButtonCreated: 窗口的任务栏按钮建立/重建时才发, 是重挂 thumbbar 的唯一正确时机)。
 *
 * 只准加**低频**消息: 白名单里的消息每次都会 upcall 到 JVM,
 * hot path (鼠标移动 / 命中测试 / 窗口位置变化) 绝对不许加, 否则必卡死 (见 wndchrome.c 线程纪律)。
 * 幂等 (重复添加同一号不重复占槽); 槽位有限, 满了忽略并置 LGC_ERR_HOOK_FULL。
 */
void lgchrome_add_hook_message(unsigned int msg);

/* 注册回调 (可传 NULL 清除)。应在 attach 之前调用。 */
void lgchrome_set_callbacks(lgchrome_action_cb onAction, lgchrome_metrics_cb onMetrics);

/*
 * 控制条高度 (物理像素)。Kotlin 侧用 40dp * density 换算后传入。
 * 注意: 与 JBR 相反 —— JBR 的 setHeight 收逻辑单位由 native 自乘 scale, 本桥收物理像素不做缩放。
 */
void lgchrome_set_caption_height(int heightPx);

/* 应用名 (UTF-16, 以 0 结尾)。传 NULL 清空。 */
void lgchrome_set_title(const unsigned short *utf16Text);

/*
 * 位图资源: 直接复用 Compose 侧现有图标资源, 避免在 C 里重画导致样式漂移。
 * slot: 0=应用图标, 1=深浅色切换图标 (随主题变化时重新推送即可)
 * pixels: 预乘 alpha 的 BGRA (每像素 4 字节, 行优先, 无 padding); 传 NULL 清除该 slot
 */
void lgchrome_set_bitmap(int slot, const void *bgraPremultiplied, int width, int height);

/*
 * 配色。全部为 0xAARRGGBB。
 * bg            控制条底色 (= Compose 侧 AppTheme.colors.background 或阅读页染色, 同一状态源)
 * fg            前景色 (应用名文字 + 我们自己的键的图标 tint + 系统三键 glyph)
 * dark          1=底色偏深 (决定 hover 叠色方向: 深底提亮, 浅底压暗)
 * inactiveAlpha 窗口失焦时 glyph/文字的 alpha (0..255; Windows 惯例约 0.6 ⇒ 153)
 *
 * hover/pressed 底色由 native 按实测规则从 bg 推导, 不需要 Kotlin 传:
 *   浅底 hover  = bg 每通道 -10, pressed = -20
 *   深底 hover  = bg 每通道 +13, pressed = +26
 *   关闭键 hover 恒 0xFFC42B1C (Win11 实测, 不随主题), pressed 略暗
 */
void lgchrome_set_theme(unsigned int bg, unsigned int fg, int dark, int inactiveAlpha);

/*
 * 全屏挂起。fullscreen=1 时:
 *   - 隐藏控制条子窗口
 *   - WM_NCCALCSIZE 不再改写 (交回 DefWindowProc)
 *   - WM_NCHITTEST 全部放行
 * 必须由 DesktopFullscreenController 在**改窗口样式之前**调用 (它会去 WS_CAPTION|WS_THICKFRAME,
 * 而本桥依赖窗口带 caption 样式), 退出全屏时在还原样式**之后**调 0。
 */
void lgchrome_set_fullscreen(int fullscreen);

/* 最近一次失败的错误码 (LGC_*); 成功调用不清零上次的值, 仅供诊断。 */
int lgchrome_last_error(void);

/* 最近一次 Win32/HRESULT 原始错误值 (GetLastError 或 HRESULT), 供诊断。 */
int lgchrome_last_os_error(void);

#ifdef __cplusplus
}
#endif
#endif /* LEGADO_WNDCHROME_H */
