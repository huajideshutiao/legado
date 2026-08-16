/*
 * legado_wndchrome —— Windows 窗口控制条 native 桥。契约与设计依据见 wndchrome.h。
 *
 * 绘制策略 (与调研建议的偏离, 有意为之):
 *   调研推荐 GDI+ flat API + 预乘 alpha, 顾虑是"GDI 画 32bpp DIB 会把 alpha 清零"。
 *   但本控制条**整条不透明** (底色 = 应用主题色), 所以:
 *     - 图形与文字全用普通 GDI 画, 画完统一把整块 alpha 补成 255, 再 UpdateLayeredWindow(ULW_ALPHA)
 *     - 底色不透明 ⇒ ClearType 反而能正常工作 (subpixel AA 有确定的底色可混)
 *     - 只有图标位图带真 alpha, 那部分逐像素手工混合 + 着色 (见 blit_argb)
 *   净效果: 零 GDI+ 依赖, 不必从 C 手写一大坨 flat API 声明。
 */
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <stdlib.h>
#include <string.h>
#include "wndchrome.h"

#define STRIP_CLASS L"LegadoWndChromeStrip"

/* 线程纪律 (踩过的坑: 拖动时卡死+闪退):
 *   导出的 setter 由 EDT 调, 窗口消息由 AWT-Windows 线程处理。两边都直接画会撞 GDI 对象
 *   (一边 ensure_surface 里 DeleteDC, 另一边正往上画) ⇒ 必崩。
 *   所以: **所有绘制与窗口操作只在拥有窗口的线程上做**, EDT 侧只改状态 + PostMessage。
 *   另: 窗口消息处理里**绝不回调 JVM** —— 拖动时 WM_WINDOWPOSCHANGED 每帧都来,
 *   每帧一次 JNA upcall 会撞 AWT tree lock 死锁。
 *   唯一例外是 lgchrome_set_message_hook 的白名单低频消息 (任务栏缩略图请求 / thumbbar 命令 /
 *   任务栏按钮建立, 后者的号由 lgchrome_add_hook_message 运行期追加);
 *   hot path (WM_NCHITTEST / WM_NCMOUSEMOVE 等) 绝不回调。 */
/*   还有一条更狠的: 子窗口**必须在拥有父窗口的那条线程上创建**。attach 跑在 EDT, 而 frame 的
 *   HWND 由 AWT-Windows 线程创建; 跨线程 CreateWindowEx 会让两条线程的输入队列被挂在一起
 *   (AttachThreadInput 语义) —— EDT 一忙, frame 的输入处理就停摆, 点一下就"未响应"。
 *   所以 attach 只做可跨线程的 SetWindowLongPtr, 建窗口/绘制/销毁全部 PostMessage 回窗口线程。 */
#define LGC_WM_INIT     (WM_APP + 0x40)
#define LGC_WM_REPAINT  (WM_APP + 0x41)
#define LGC_WM_RELAYOUT (WM_APP + 0x42)
#define LGC_WM_TEARDOWN (WM_APP + 0x43)

/* 我们自己的键在非客户区的命中码: HTBORDER 既不触发系统拖拽 (HTCAPTION 会),
 * 也不落客户区 (HTCLIENT 会被 Compose/Canvas 吃掉), 且不会变 resize 光标。 */
#define HT_OWNBTN HTBORDER

/* dp → px (96dpi 基准) */
#define DP(v) MulDiv((v), g_dpi, 96)

typedef struct {
    int w, h;
    unsigned char *px; /* BGRA, 预乘 */
} Bitmap;

typedef struct {
    RECT rc;
    int id;
} BtnSlot;

static HWND g_frame, g_canvas, g_strip;
static int g_captionH = 0;       /* 物理像素 */
static int g_dpi = 96;
static unsigned int g_bg = 0xFF202020, g_fg = 0xFFF8F8F8;
static int g_dark = 1, g_inactiveAlpha = 153;
static WCHAR g_title[256];
static Bitmap g_bmp[2];            /* 0=应用图标 1=深浅色图标 */
static int g_hover = LGC_BTN_NONE, g_pressed = LGC_BTN_NONE;
static int g_inCaption = 0;      /* 指针在控制条内 ⇒ 三键 glyph 整组提亮 (Win11 实测行为) */
static int g_active = 1, g_suspended = 0;
static int g_err = LGC_OK, g_osErr = 0;
static WNDPROC g_frameOld, g_canvasOld;   /* SetWindowLongPtr 链式子类化保存的原 proc */
static CRITICAL_SECTION g_cs;             /* 保护 EDT 写 / 窗口线程读的状态 */
static int g_csReady = 0;
static lgchrome_action_cb g_onAction;
static lgchrome_metrics_cb g_onMetrics;
/* 白名单窗口消息钩子 (S3: 任务栏那两层 JNA 子类化已收进本桥) */
static lgchrome_msg_cb g_onMsg;

/* 运行期追加的白名单消息号 (RegisterWindowMessage 的动态号, 如 TaskbarButtonCreated)。
 * 0 = 空槽; 只放低频消息, 每条都会 upcall 到 JVM。EDT 写 / 窗口线程读, 单个
 * unsigned int 的写入本身原子, 且空槽判定不依赖计数, 故不必上锁。 */
#define LGC_EXTRA_MSG_MAX 8
static unsigned int g_extraMsg[LGC_EXTRA_MSG_MAX];

static BtnSlot g_slots[5];         /* 右起: CLOSE MAX MIN MENU THEME */
static int g_slotCount = 0;
static RECT g_iconRc;

static HDC g_memDc;
static HBITMAP g_memBmp;
static void *g_memPx;
static int g_memW, g_memH;
static HFONT g_font;          /* 应用名 */
static HFONT g_iconFont;      /* 三键/⋯ 的 Segoe MDL2 Assets */
static int g_iconFontOk = 0;
static int g_fontDpi = 0;

/* AdjustWindowRectExForDpi 动态解析 (Win10 1607+); 拿不到退非 DPI 版本 */
typedef BOOL (WINAPI
*PFN_AWREFD)(LPRECT, DWORD, BOOL, DWORD, UINT);
static PFN_AWREFD g_awrefd;

static void fail(int code) {
    g_err = code;
    g_osErr = (int) GetLastError();
}

/* 该消息要不要先给 Kotlin: 编译期三条 + 运行期追加的几条, 全是低频消息。 */
static int is_hook_msg(UINT msg) {
    if (msg == 0x0323 || msg == 0x0326 || msg == WM_COMMAND) return 1;
    for (int i = 0; i < LGC_EXTRA_MSG_MAX; i++) {
        if (g_extraMsg[i] && g_extraMsg[i] == (unsigned int) msg) return 1;
    }
    return 0;
}

/* ---------------- 系统边框 insets (去掉 caption 只留边框) ---------------- */
static RECT sys_insets(void) {
    RECT r = {0, 0, 0, 0};
    if (!g_frame) return r;
    LONG_PTR st = GetWindowLongPtrW(g_frame, GWL_STYLE);
    LONG_PTR ex = GetWindowLongPtrW(g_frame, GWL_EXSTYLE);
    DWORD style = (DWORD)((st & ~WS_CAPTION) | WS_BORDER);
    RECT t = {0, 0, 0, 0};
    if (g_awrefd) g_awrefd(&t, style, FALSE, (DWORD) ex, (UINT) g_dpi);
    else AdjustWindowRectEx(&t, style, FALSE, (DWORD) ex);
    r.left = -t.left;
    r.top = -t.top;
    r.right = t.right;
    r.bottom = t.bottom;
    return r;
}

/* ---------------- 版式 ---------------- */
static void layout(void) {
    g_slotCount = 0;
    SetRectEmpty(&g_iconRc);
    if (!g_frame || g_captionH <= 0) return;
    RECT cr;
    if (!GetClientRect(g_frame, &cr)) return;
    int w = cr.right - cr.left, h = g_captionH;
    int btnW = DP(46);
    static const int order[5] = {LGC_BTN_CLOSE, LGC_BTN_MAX, LGC_BTN_MIN, LGC_BTN_MENU, LGC_BTN_THEME};
    int right = w;
    for (int i = 0; i < 5; i++) {
        if (right - btnW < 0) break;
        BtnSlot *s = &g_slots[g_slotCount++];
        s->id = order[i];
        SetRect(&s->rc, right - btnW, 0, right, h);
        right -= btnW;
    }
    int iconSz = DP(18), pad = DP(12);
    SetRect(&g_iconRc, pad, (h - iconSz) / 2, pad + iconSz, (h + iconSz) / 2);
}

static BtnSlot *hit_slot(int x, int y) {
    POINT p = {x, y};
    for (int i = 0; i < g_slotCount; i++) {
        if (PtInRect(&g_slots[i].rc, p)) return &g_slots[i];
    }
    return NULL;
}

/* ---------------- 颜色 ---------------- */
static unsigned int shade(unsigned int c, int delta) {
    int r = (int) ((c >> 16) & 0xFF) + delta;
    int g = (int) ((c >> 8) & 0xFF) + delta;
    int b = (int) (c & 0xFF) + delta;
    if (r < 0) r = 0;
    if (r > 255) r = 255;
    if (g < 0) g = 0;
    if (g > 255) g = 255;
    if (b < 0) b = 0;
    if (b > 255) b = 255;
    return 0xFF000000u | ((unsigned) r << 16) | ((unsigned) g << 8) | (unsigned) b;
}

/* hover/pressed 底色: 浅底压暗 深底提亮 (Win11 实测 -10 / +13, pressed 加倍);
 * 关闭键 hover 恒 #C42B1C (实测不随主题) */
static unsigned int btn_bg(int id, int state) {
    if (state == 0) return g_bg;
    if (id == LGC_BTN_CLOSE) return state == 1 ? 0xFFC42B1C : 0xFFA82419;
    int d = g_dark ? 13 : -10;
    return shade(g_bg, state == 1 ? d : d * 2);
}

static unsigned int glyph_color(int id, int state) {
    if (id == LGC_BTN_CLOSE && state != 0) return 0xFFFDFAFA;
    unsigned int c = g_fg;
    if (!g_active) {
        /* 失焦: 按 inactiveAlpha 向底色靠 */
        int a = g_inactiveAlpha;
        int r = (int) (((c >> 16) & 0xFF) * a + ((g_bg >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (int) (((c >> 8) & 0xFF) * a + ((g_bg >> 8) & 0xFF) * (255 - a)) / 255;
        int b = (int) ((c & 0xFF) * a + (g_bg & 0xFF) * (255 - a)) / 255;
        return 0xFF000000u | ((unsigned) r << 16) | ((unsigned) g << 8) | (unsigned) b;
    }
    /* 注: 曾按探针的 RGB(146,146,146) 实现"指针未进入 caption 时三键变淡", 是误读 ——
     * 那批采样全在窗口**未激活**状态下取的 (探针纪律禁止抢焦点)。真实 Win11 行为是激活窗口
     * 三键满对比度, 只有失焦才整体变淡 (上面 !g_active 分支已覆盖)。 */
    return c;
}

#define TO_COLORREF(c) RGB((BYTE)(((c) >> 16) & 0xFF), (BYTE)(((c) >> 8) & 0xFF), (BYTE)((c) & 0xFF))

/* ---------------- 绘制 ---------------- */
static void fill_rect(HDC dc, const RECT *r, unsigned int argb) {
    HBRUSH b = CreateSolidBrush(TO_COLORREF(argb));
    FillRect(dc, r, b);
    DeleteObject(b);
}

/* 图标位图逐像素混合进 DIB; tint!=0 时用 tint 的 RGB 替换 (蒙版着色) */
static void blit_argb(const Bitmap *bmp, int dx, int dy, int dw, int dh, unsigned int tint) {
    if (!bmp->px || bmp->w <= 0 || bmp->h <= 0 || dw <= 0 || dh <= 0 || !g_memPx) return;
    unsigned char *dst = (unsigned char *) g_memPx;
    for (int y = 0; y < dh; y++) {
        int ty = dy + y;
        if (ty < 0 || ty >= g_memH) continue;
        int sy = y * bmp->h / dh;
        for (int x = 0; x < dw; x++) {
            int tx = dx + x;
            if (tx < 0 || tx >= g_memW) continue;
            int sx = x * bmp->w / dw;
            const unsigned char *s = bmp->px + ((size_t) sy * bmp->w + sx) * 4;
            unsigned char a = s[3];
            if (!a) continue;
            unsigned char sb = s[0], sg = s[1], sr = s[2];
            if (tint) {
                sb = (unsigned char) (tint & 0xFF);
                sg = (unsigned char) ((tint >> 8) & 0xFF);
                sr = (unsigned char) ((tint >> 16) & 0xFF);
                sb = (unsigned char) (sb * a / 255);
                sg = (unsigned char) (sg * a / 255);
                sr = (unsigned char) (sr * a / 255);
            }
            unsigned char *d = dst + ((size_t) ty * g_memW + tx) * 4;
            d[0] = (unsigned char) (sb + d[0] * (255 - a) / 255);
            d[1] = (unsigned char) (sg + d[1] * (255 - a) / 255);
            d[2] = (unsigned char) (sr + d[2] * (255 - a) / 255);
        }
    }
}

/* 三键 glyph: 几何绘制 (不用字体码点, 免 PUA 字体缺失风险) */
static void draw_glyph(HDC dc, int id, const RECT *rc, unsigned int color, int maximized) {
    if (g_iconFontOk && g_iconFont) {
        /* Segoe MDL2 Assets 码点 (与旧 Compose 实现一致): 最小化/最大化/还原/关闭/更多 */
        const WCHAR *ch = NULL;
        switch (id) {
            case LGC_BTN_MIN:
                ch = L"\uE921";
                break;
            case LGC_BTN_MAX:
                ch = maximized ? L"\uE923" : L"\uE922";
                break;
            case LGC_BTN_CLOSE:
                ch = L"\uE8BB";
                break;
            case LGC_BTN_MENU:
                ch = L"\uE712";
                break;
            default:
                break;
        }
        if (ch) {
            RECT t = *rc;
            HFONT of = (HFONT) SelectObject(dc, g_iconFont);
            SetBkMode(dc, TRANSPARENT);
            SetTextColor(dc, TO_COLORREF(color));
            DrawTextW(dc, ch, 1, &t, DT_SINGLELINE | DT_CENTER | DT_VCENTER | DT_NOPREFIX);
            SelectObject(dc, of);
            return;
        }
    }
    int box = DP(10);
    int cx = (rc->left + rc->right) / 2, cy = (rc->top + rc->bottom) / 2;
    int x0 = cx - box / 2, y0 = cy - box / 2, x1 = x0 + box, y1 = y0 + box;
    int lw = DP(1);
    if (lw < 1) lw = 1;
    COLORREF cr = TO_COLORREF(color);
    HPEN pen = CreatePen(PS_SOLID, lw, cr);
    HPEN old = (HPEN) SelectObject(dc, pen);
    HBRUSH oldB = (HBRUSH) SelectObject(dc, GetStockObject(NULL_BRUSH));
    if (id == LGC_BTN_MIN) {
        MoveToEx(dc, x0, cy, NULL);
        LineTo(dc, x1, cy);
    } else if (id == LGC_BTN_MAX) {
        if (maximized) {
            int o = DP(2);
            Rectangle(dc, x0, y0 + o, x1 - o, y1);
            Rectangle(dc, x0 + o, y0, x1, y1 - o);
        } else {
            Rectangle(dc, x0, y0, x1, y1);
        }
    } else if (id == LGC_BTN_CLOSE) {
        MoveToEx(dc, x0, y0, NULL);
        LineTo(dc, x1, y1);
        MoveToEx(dc, x1 - 1, y0, NULL);
        LineTo(dc, x0 - 1, y1);
    } else if (id == LGC_BTN_MENU) {
        int d = DP(2), gap = DP(4);
        HBRUSH fb = CreateSolidBrush(cr);
        for (int i = -1; i <= 1; i++) {
            RECT dot;
            SetRect(&dot, cx + i * gap - d / 2, cy - d / 2, cx + i * gap + d / 2, cy + d / 2);
            FillRect(dc, &dot, fb);
        }
        DeleteObject(fb);
    }
    SelectObject(dc, oldB);
    SelectObject(dc, old);
    DeleteObject(pen);
}

/* Segoe MDL2 Assets 是否可用 (Win10+ 系统自带; 缺失时退回几何绘制) */
static int WINAPI

has_face_cb(const LOGFONTW *lf, const TEXTMETRICW *tm, DWORD type, LPARAM p) {
    (void) lf;
    (void) tm;
    (void) type;
    *(int *) p = 1;
    return 0;
}

static void ensure_font(void) {
    if (g_font && g_fontDpi == g_dpi) return;
    if (g_font) {
        DeleteObject(g_font);
        g_font = NULL;
    }
    if (g_iconFont) {
        DeleteObject(g_iconFont);
        g_iconFont = NULL;
    }
    {
        /* 用文字画 glyph 而不是几何: GDI 画线没有抗锯齿 (硬边 1px 又细又糙),
         * 画文字有; 且这套码点与旧 Compose 实现一致, 观感对齐系统标题栏。 */
        LOGFONTW q;
        ZeroMemory(&q, sizeof(q));
        wcscpy_s(q.lfFaceName, LF_FACESIZE, L"Segoe MDL2 Assets");
        q.lfCharSet = DEFAULT_CHARSET;
        int found = 0;
        HDC sdc = GetDC(NULL);
        EnumFontFamiliesExW(sdc, &q, has_face_cb, (LPARAM) & found, 0);
        ReleaseDC(NULL, sdc);
        g_iconFontOk = found;
        if (found) {
            LOGFONTW lf;
            ZeroMemory(&lf, sizeof(lf));
            lf.lfHeight = -DP(10);
            lf.lfWeight = FW_NORMAL;
            lf.lfCharSet = DEFAULT_CHARSET;
            lf.lfQuality = CLEARTYPE_QUALITY;
            wcscpy_s(lf.lfFaceName, LF_FACESIZE, L"Segoe MDL2 Assets");
            g_iconFont = CreateFontIndirectW(&lf);
            if (!g_iconFont) g_iconFontOk = 0;
        }
    }
    NONCLIENTMETRICSW m;
    ZeroMemory(&m, sizeof(m));
    m.cbSize = sizeof(m);
    if (SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof(m), &m, 0)) {
        LOGFONTW lf = m.lfCaptionFont;
        lf.lfWeight = FW_NORMAL;          /* 系统 caption 字体默认加粗, 应用名用常规字重 */
        lf.lfHeight = -MulDiv(12, g_dpi, 72);
        g_font = CreateFontIndirectW(&lf);
    }
    g_fontDpi = g_dpi;
}

static void ensure_surface(int w, int h) {
    if (g_memDc && g_memW == w && g_memH == h) return;
    if (g_memBmp) {
        DeleteObject(g_memBmp);
        g_memBmp = NULL;
    }
    if (g_memDc) {
        DeleteDC(g_memDc);
        g_memDc = NULL;
    }
    g_memPx = NULL;
    g_memW = w;
    g_memH = h;
    if (w <= 0 || h <= 0) return;
    BITMAPINFO bi;
    ZeroMemory(&bi, sizeof(bi));
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = w;
    bi.bmiHeader.biHeight = -h;          /* 自顶向下 */
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    HDC screen = GetDC(NULL);
    g_memDc = CreateCompatibleDC(screen);
    g_memBmp = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, &g_memPx, NULL, 0);
    ReleaseDC(NULL, screen);
    if (g_memDc && g_memBmp) SelectObject(g_memDc, g_memBmp);
}

static void repaint(void) {
    if (!g_strip || g_suspended || g_captionH <= 0) return;
    if (g_csReady) EnterCriticalSection(&g_cs);
    RECT cr;
    if (!GetClientRect(g_frame, &cr) || cr.right - cr.left <= 0) {
        if (g_csReady) LeaveCriticalSection(&g_cs);
        return;
    }
    int w = cr.right - cr.left, h = g_captionH;
    ensure_surface(w, h);
    if (!g_memDc || !g_memPx) {
        fail(LGC_ERR_CREATE_STRIP);
        if (g_csReady) LeaveCriticalSection(&g_cs);
        return;
    }

    RECT all = {0, 0, w, h};
    fill_rect(g_memDc, &all, g_bg);

    int maximized = IsZoomed(g_frame) ? 1 : 0;
    ensure_font();

    /* 按钮底色 + glyph / 图标 */
    for (int i = 0; i < g_slotCount; i++) {
        BtnSlot *s = &g_slots[i];
        int state = (g_pressed == s->id) ? 2 : (g_hover == s->id ? 1 : 0);
        if (state) fill_rect(g_memDc, &s->rc, btn_bg(s->id, state));
        unsigned int gc = glyph_color(s->id, state);
        if (s->id == LGC_BTN_THEME) {
            int sz = DP(20);
            blit_argb(&g_bmp[1], (s->rc.left + s->rc.right - sz) / 2,
                    (s->rc.top + s->rc.bottom - sz) / 2, sz, sz, gc);
        } else {
            draw_glyph(g_memDc, s->id, &s->rc, gc, maximized);
        }
    }

    /* 应用图标 + 名称 */
    if (!IsRectEmpty(&g_iconRc)) {
        blit_argb(&g_bmp[0], g_iconRc.left, g_iconRc.top,
                g_iconRc.right - g_iconRc.left, g_iconRc.bottom - g_iconRc.top, 0);
    }
    if (g_title[0]) {
        ensure_font();
        int textLeft = (IsRectEmpty(&g_iconRc) ? DP(12) : g_iconRc.right + DP(8));
        int textRight = (g_slotCount > 0 ? g_slots[g_slotCount - 1].rc.left - DP(4) : w - DP(4));
        if (textRight > textLeft) {
            RECT tr;
            SetRect(&tr, textLeft, 0, textRight, h);
            HFONT of = g_font ? (HFONT) SelectObject(g_memDc, g_font) : NULL;
            SetBkMode(g_memDc, TRANSPARENT);
            SetTextColor(g_memDc, TO_COLORREF(glyph_color(LGC_BTN_NONE, 0)));
            DrawTextW(g_memDc, g_title, -1, &tr,
                    DT_SINGLELINE | DT_VCENTER | DT_LEFT | DT_END_ELLIPSIS | DT_NOPREFIX);
            if (of) SelectObject(g_memDc, of);
        }
    }

    /* GDI 绘制会把 alpha 清零: 统一补成 255 (整条不透明), 之后 ULW_ALPHA 即恒等混合 */
    {
        unsigned char *p = (unsigned char *) g_memPx;
        size_t n = (size_t) w * h;
        for (size_t i = 0; i < n; i++) p[i * 4 + 3] = 255;
    }

    POINT src = {0, 0}, dst = {0, 0};
    SIZE sz = {w, h};
    BLENDFUNCTION bf;
    bf.BlendOp = AC_SRC_OVER;
    bf.BlendFlags = 0;
    bf.SourceConstantAlpha = 255;
    bf.AlphaFormat = AC_SRC_ALPHA;
    UpdateLayeredWindow(g_strip, NULL, &dst, &sz, g_memDc, &src, 0, &bf, ULW_ALPHA);
    if (g_csReady) LeaveCriticalSection(&g_cs);
}

static void reposition(void) {
    if (!g_strip) return;
    if (g_suspended) {
        ShowWindow(g_strip, SW_HIDE);
        return;
    }
    RECT cr;
    if (!GetClientRect(g_frame, &cr)) return;
    layout();
    SetWindowPos(g_strip, HWND_TOP, 0, 0, cr.right - cr.left, g_captionH,
            SWP_NOACTIVATE | SWP_SHOWWINDOW);
    repaint();
}

static void notify_metrics(void) {
    if (!g_onMetrics || !g_frame) return;
    int btnW = 0;
    RECT r;
    if (SUCCEEDED(DwmGetWindowAttribute(g_frame, DWMWA_CAPTION_BUTTON_BOUNDS, &r, sizeof(r)))) {
        btnW = r.right - r.left;
    }
    g_onMetrics(g_dpi, btnW, IsZoomed(g_frame) ? 1 : 0);
}

/* ---------------- 控制条子窗口 (纯显示, 鼠标穿透) ---------------- */
static LRESULT CALLBACK
strip_proc(HWND
h,
UINT m, WPARAM
w,
LPARAM l
) {
if (m == WM_ERASEBKGND) return 1;   /* 全靠 UpdateLayeredWindow, 不擦背景免闪 */
return
DefWindowProcW(h, m, w, l
);
}

static int create_strip(void) {
    static int registered = 0;
    if (!registered) {
        WNDCLASSEXW wc;
        ZeroMemory(&wc, sizeof(wc));
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = strip_proc;
        wc.hInstance = GetModuleHandleW(NULL);
        wc.lpszClassName = STRIP_CLASS;
        wc.hCursor = LoadCursorW(NULL, IDC_ARROW);
        if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            fail(LGC_ERR_CREATE_STRIP);
            return 0;
        }
        registered = 1;
    }
    g_strip = CreateWindowExW(WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOPARENTNOTIFY,
            STRIP_CLASS, L"", WS_CHILD, 0, 0, 0, 0,
            g_frame, NULL, GetModuleHandleW(NULL), NULL);
    if (!g_strip) {
        fail(LGC_ERR_CREATE_STRIP);
        return 0;
    }
    return 1;
}

/* 销毁控制条与绘制资源。DestroyWindow 必须在创建它的线程上调, 故只从窗口线程进入
 * (detach 跨线程时经 LGC_WM_TEARDOWN 转发)。 */
static void release_resources(void) {
    if (g_strip) {
        DestroyWindow(g_strip);
        g_strip = NULL;
    }
    if (g_memBmp) {
        DeleteObject(g_memBmp);
        g_memBmp = NULL;
    }
    if (g_memDc) {
        DeleteDC(g_memDc);
        g_memDc = NULL;
    }
    g_memPx = NULL;
    g_memW = g_memH = 0;
    if (g_font) {
        DeleteObject(g_font);
        g_font = NULL;
    }
    if (g_iconFont) {
        DeleteObject(g_iconFont);
        g_iconFont = NULL;
    }
    g_iconFontOk = 0;
    g_fontDpi = 0;
}

/*
 * 把键盘焦点还给 Compose 的渲染 Canvas。
 *
 * 点/拖控制条发生在**非客户区**, 焦点会离开承载 Compose 场景的 Canvas 子窗口, 之后按键不再
 * 按预期路由 (用户实测: 点过控制条后音频页空格变成"触发焦点按钮"而不是播放/暂停)。
 * 交互结束后主动还焦点即可。SetFocus 必须在窗口属主线程调 —— frame_proc 正是。
 */
static void refocus_canvas(void) {
    if (g_canvas && IsWindow(g_canvas) && GetFocus() != g_canvas) SetFocus(g_canvas);
}

/* ---------------- L1: 主窗口 (JFrame) ---------------- */
static LRESULT CALLBACK
frame_proc(HWND
h,
UINT msg, WPARAM
wp,
LPARAM lp
) {
/* 白名单低频消息先给 Kotlin (任务栏缩略图请求 / thumbbar 按钮命令 / 任务栏按钮建立);
 * 返回 1 = 已处理, 直接答 0 给 Windows, 不再沿链转发。 */
if (g_onMsg &&
is_hook_msg(msg)
) {
if (
g_onMsg(msg,
(long long)wp, (long long)lp)) return 0;
}
switch (msg) {
case WM_NCCALCSIZE:
if (wp && !g_suspended) {
NCCALCSIZE_PARAMS *p = (NCCALCSIZE_PARAMS *) lp;
LONG origTop = p->rgrc[0].top;
CallWindowProcW(g_frameOld, h, msg, wp, lp
);
p->rgrc[0].
top = origTop;            /* 客户区顶到窗口顶端 */
if (
IsZoomed(h)
) {
RECT ins = sys_insets();
p->rgrc[0].top += ins.
top;       /* 最大化时补回顶部边框, 否则露边 */
APPBARDATA ab;
ZeroMemory(&ab, sizeof(ab)); ab.
cbSize = sizeof(ab);
if ((
SHAppBarMessage(ABM_GETSTATE,
&ab) & ABS_AUTOHIDE) != 0) {
p->rgrc[0].bottom -= 1;      /* auto-hide 任务栏留 1px 供唤出 */
}
}
return 0;
}
break;

case WM_NCHITTEST: {
if (g_suspended) break;
POINT pt = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
POINT cp = pt;
ScreenToClient(h,
&cp);
RECT cr;
GetClientRect(h,
&cr);
if (cp.y >= 0 && cp.y < g_captionH && cp.x >= 0 && cp.x < cr.right) {
RECT ins = sys_insets();
int top = ins.top > 0 ? ins.top : DP(4);
if (!
IsZoomed(h)
&& cp.y < top) {    /* 顶边 resize 区 (最大化时无) */
int corner = DP(12);
if (cp.x < corner) return
HTTOPLEFT;
if (cp.x >= cr.right - corner) return
HTTOPRIGHT;
return
HTTOP;
}
BtnSlot *s = hit_slot(cp.x, cp.y);
if (s) {
switch (s->id) {
case LGC_BTN_MIN:   return
HTMINBUTTON;
case LGC_BTN_MAX:   return
HTMAXBUTTON;  /* Win11 Snap Layouts 的唯一开关 */
case LGC_BTN_CLOSE: return
HTCLOSE;
default:            return
HT_OWNBTN;    /* 我们自己的键, 见 HT_OWNBTN 注释 */
}
}
if (PtInRect(&g_iconRc, cp)) return
HTSYSMENU;
return
HTCAPTION;                    /* 空白区: 原生拖拽/双击最大化/贴靠/右键菜单 */
}
break;
}

case WM_NCMOUSEMOVE: {
if (g_suspended) break;
POINT cp = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
ScreenToClient(h,
&cp);
BtnSlot *s = hit_slot(cp.x, cp.y);
int nh = s ? s->id : LGC_BTN_NONE;
int inCap = (cp.y >= 0 && cp.y < g_captionH) ? 1 : 0;
if (nh != g_hover || inCap != g_inCaption) {
g_hover = nh;
g_inCaption = inCap;

repaint();

}
TRACKMOUSEEVENT tme;
tme.
cbSize = sizeof(tme);
tme.
dwFlags = TME_LEAVE | TME_NONCLIENT;
tme.
hwndTrack = h;
tme.
dwHoverTime = 0;
TrackMouseEvent(&tme);
break;
}

case WM_NCMOUSELEAVE:
if (g_hover != LGC_BTN_NONE || g_inCaption) {
g_hover = LGC_BTN_NONE;
g_pressed = LGC_BTN_NONE;
g_inCaption = 0;

repaint();

}
break;

case WM_NCLBUTTONDOWN: {
if (g_suspended) break;
POINT cp = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
ScreenToClient(h,
&cp);
BtnSlot *s = hit_slot(cp.x, cp.y);
/* Compose 的 menu 是独立 Popup, 靠点击落进 Compose 才 dismiss; 而标题栏这 40dp 是 native
 * 地盘, 鼠标消息不进 Compose ⇒ 在标题栏按/拖不会关菜单。所以这里主动通知一次
 * (LGC_BTN_NONE = 请关掉浮层), 但 menu 键本身除外 (它要开菜单)。 */
if (g_onAction && (!s || s->id != LGC_BTN_MENU)) g_onAction(LGC_BTN_NONE, cp.x, cp.y);
if (s && cp.y >= 0 && cp.y < g_captionH) {
/* 必须拦截: 否则系统会用经典样式在我们画的按钮上叠一层按下态 */
g_pressed = s->id;

repaint();

return 0;
}
break;                                   /* HTCAPTION: 放行, 系统启动拖动 (含 Aero Snap) */
}

case WM_NCLBUTTONUP: {
if (g_suspended) break;
int was = g_pressed;
if (was == LGC_BTN_NONE) break;
POINT cp = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
ScreenToClient(h,
&cp);
BtnSlot *s = hit_slot(cp.x, cp.y);
g_pressed = LGC_BTN_NONE;

repaint();

if (s && s->id == was) {
/* 用 WM_SYSCOMMAND 不用 ShowWindow: 系统路径才有 Win11 动画与任务栏联动 */
switch (was) {
case LGC_BTN_MIN:
PostMessageW(h, WM_SYSCOMMAND, SC_MINIMIZE,
0); break;
case LGC_BTN_MAX:
PostMessageW(h, WM_SYSCOMMAND,
        IsZoomed(h)
? SC_RESTORE : SC_MAXIMIZE, 0); break;
case LGC_BTN_CLOSE:
PostMessageW(h, WM_SYSCOMMAND, SC_CLOSE,
0); break;
default:
if (g_onAction)
g_onAction(was, s
->rc.left, s->rc.bottom);
break;
}
}

refocus_canvas();   /* 点过控制条后把键盘焦点还给 Canvas, 否则按键路由不对 */
return 0;
}

/* 只在**尺寸**变化时重排: 子窗口会随父窗口自动移动, 拖动 (纯移动) 不需要任何工作。
 * 这里也绝不回调 JVM (见文件头线程纪律)。 */
case WM_SIZE: {
LRESULT r = CallWindowProcW(g_frameOld, h, msg, wp, lp);   /* 放行: AWT 靠 WM_SIZE 实测自愈 insets */
reposition();

return
r;
}

case WM_DPICHANGED: {
LRESULT r = CallWindowProcW(g_frameOld, h, msg, wp, lp);
g_dpi = (int) LOWORD(wp);
g_fontDpi = 0;

reposition();

return
r;
}

case LGC_WM_INIT:
/* 在窗口自己的线程上建控制条子窗口 (跨线程建会挂住输入队列) */
if (!g_strip && !

create_strip()

) return 0;
SetWindowPos(h, NULL, 0, 0, 0, 0,
SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);

reposition();

return 0;

case LGC_WM_TEARDOWN:

release_resources();

return 0;

case LGC_WM_REPAINT:

repaint();

return 0;

case LGC_WM_RELAYOUT:
SetWindowPos(h, NULL, 0, 0, 0, 0,
SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);

reposition();

return 0;

case WM_EXITSIZEMOVE:

/* HTCAPTION 拖动/边框缩放走系统模态循环, 不给我们 NCLBUTTONUP, 在这里还焦点 */
refocus_canvas();

break;

case WM_ACTIVATE:
case WM_NCACTIVATE: {
int act = (msg == WM_ACTIVATE) ? (LOWORD(wp) != WA_INACTIVE) : (wp != FALSE);
if (act != g_active) {
g_active = act;

repaint();

}
break;
}

case WM_SETTINGCHANGE:
case WM_THEMECHANGED:
g_fontDpi = 0;                            /* 强制重建字体 */
reposition();

break;

case WM_NCDESTROY: {
LRESULT r = CallWindowProcW(g_frameOld, h, msg, wp, lp);

lgchrome_detach();

return
r;
}
default: break;
}
if (!g_frameOld) return
DefWindowProcW(h, msg, wp, lp
);
return
CallWindowProcW(g_frameOld, h, msg, wp, lp
);
}

/* ---------------- L2: skiko Canvas (只让开标题栏区的命中测试) ---------------- */
static LRESULT CALLBACK
canvas_proc(HWND
h,
UINT msg, WPARAM
wp,
LPARAM lp
) {
if (msg == WM_NCHITTEST && !g_suspended && g_frame) {
POINT cp = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
ScreenToClient(g_frame,
&cp);
if (cp.y >= 0 && cp.y < g_captionH) {
/* Canvas 覆盖整个客户区会吃掉标题栏区鼠标消息; 返回 HTTRANSPARENT
 * 让系统把命中测试重定向到父窗口 (JBR 在 JDK 内部改 AwtComponent::WmNcHitTest 同理) */
return
HTTRANSPARENT;
}
}
/* g_canvasOld 为 NULL 时绝不能 CallWindowProcW(NULL, ...): 那会让 Canvas 的所有消息
 * (键盘/鼠标/绘制) 全部丢失。退回 DefWindowProcW 至少保证消息有人处理。 */
if (!g_canvasOld) return
DefWindowProcW(h, msg, wp, lp
);
return
CallWindowProcW(g_canvasOld, h, msg, wp, lp
);
}

/* ---------------- 导出 ---------------- */
void lgchrome_set_callbacks(lgchrome_action_cb onAction, lgchrome_metrics_cb onMetrics) {
    g_onAction = onAction;
    g_onMetrics = onMetrics;
}

void lgchrome_set_message_hook(lgchrome_msg_cb cb) {
    g_onMsg = cb;
}

/* 追加白名单消息号 (幂等)。纪律: 只收低频消息 —— 白名单里的每条消息每次都会 upcall 到 JVM,
 * hot path 进来就是每帧一次 JNA upcall, 必撞 AWT tree lock 死锁。 */
void lgchrome_add_hook_message(unsigned int msg) {
    if (!msg) return;
    for (int i = 0; i < LGC_EXTRA_MSG_MAX; i++) {
        if (g_extraMsg[i] == msg) return;
    }
    for (int i = 0; i < LGC_EXTRA_MSG_MAX; i++) {
        if (!g_extraMsg[i]) {
            g_extraMsg[i] = msg;
            return;
        }
    }
    g_err = LGC_ERR_HOOK_FULL;                    /* 槽位满: 忽略, 只留错误码供诊断 */
}

int lgchrome_attach(void *hwndFrame, void *hwndCanvas) {
    if (g_frame) return LGC_OK;                   /* 幂等 */
    HWND
    f = (HWND)
    hwndFrame;
    if (!f || !IsWindow(f)) {
        fail(LGC_ERR_BAD_HWND);
        return g_err;
    }
    if (!g_csReady) {
        InitializeCriticalSection(&g_cs);
        g_csReady = 1;
    }
    g_frame = f;
    g_canvas = (hwndCanvas && IsWindow((HWND)
    hwndCanvas)) ? (HWND)
    hwndCanvas :
    NULL;
    if (!g_awrefd) {
        HMODULE u = GetModuleHandleW(L"user32.dll");
        if (u) g_awrefd = (PFN_AWREFD) GetProcAddress(u, "AdjustWindowRectExForDpi");
    }
    HMODULE u32 = GetModuleHandleW(L"user32.dll");
    if (u32) {
        typedef UINT (WINAPI
        *PFN_GDFW)(HWND);
        PFN_GDFW gdfw = (PFN_GDFW) GetProcAddress(u32, "GetDpiForWindow");
        if (gdfw) {
            UINT d = gdfw(f);
            if (d) g_dpi = (int) d;
        }
    }
    g_frameOld = (WNDPROC) SetWindowLongPtrW(f, GWLP_WNDPROC, (LONG_PTR) frame_proc);
    if (!g_frameOld) {
        g_frame = NULL;
        fail(LGC_ERR_SUBCLASS);
        return g_err;
    }
    if (g_canvas) {
        SetLastError(0);
        g_canvasOld = (WNDPROC) SetWindowLongPtrW(g_canvas, GWLP_WNDPROC, (LONG_PTR) canvas_proc);
        if (!g_canvasOld) {
            /* 拿不到原 proc 说明挂载失败或状态不明: 立刻退出这一层, 不留半挂状态
             * (半挂 = 我们的 proc 在链上但转发目标为空, Canvas 的键盘/鼠标会整体失灵)。
             * 代价只是标题栏区拖拽失效, 不致命。 */
            g_canvas = NULL;
        }
    }
    /* 建窗口 + 首次重排交给窗口自己的线程 (见文件头线程纪律) */
    PostMessageW(f, LGC_WM_INIT, 0, 0);
    notify_metrics();
    g_err = LGC_OK;
    return LGC_OK;
}

void lgchrome_detach(void) {
    if (g_frame) {
        DWORD owner = GetWindowThreadProcessId(g_frame, NULL);
        if (owner == GetCurrentThreadId()) {
            release_resources();
        } else {
            /* 带超时的同步转发: 窗口线程卡住时也不会把调用方 (EDT) 一起拖死 */
            DWORD_PTR res = 0;
            SendMessageTimeoutW(g_frame, LGC_WM_TEARDOWN, 0, 0,
                    SMTO_ABORTIFHUNG | SMTO_NORMAL, 2000, &res);
        }
    }
    g_strip = NULL;
    if (g_canvas && g_canvasOld) {
        SetWindowLongPtrW(g_canvas, GWLP_WNDPROC, (LONG_PTR) g_canvasOld);
        g_canvasOld = NULL;
    }
    g_canvas = NULL;
    if (g_frame) {
        if (g_frameOld) {
            SetWindowLongPtrW(g_frame, GWLP_WNDPROC, (LONG_PTR) g_frameOld);
            g_frameOld = NULL;
        }
        SetWindowPos(g_frame, NULL, 0, 0, 0, 0,
                SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        g_frame = NULL;
    }
    for (int i = 0; i < 2; i++) {
        if (g_bmp[i].px) {
            free(g_bmp[i].px);
            g_bmp[i].px = NULL;
        }
        g_bmp[i].w = g_bmp[i].h = 0;
    }
    g_hover = g_pressed = LGC_BTN_NONE;
    g_inCaption = 0;
}

void lgchrome_set_caption_height(int heightPx) {
    if (heightPx < 0) heightPx = 0;
    if (heightPx == g_captionH) return;
    g_captionH = heightPx;
    if (g_frame) PostMessageW(g_frame, LGC_WM_RELAYOUT, 0, 0);
}

void lgchrome_set_title(const unsigned short *utf16Text) {
    if (g_csReady) EnterCriticalSection(&g_cs);
    if (!utf16Text) {g_title[0] = 0;}
    else {
        size_t i = 0;
        for (; i < (sizeof(g_title) / sizeof(g_title[0])) - 1 && utf16Text[i]; i++) {
            g_title[i] = (WCHAR) utf16Text[i];
        }
        g_title[i] = 0;
    }
    if (g_csReady) LeaveCriticalSection(&g_cs);
    if (g_frame) PostMessageW(g_frame, LGC_WM_REPAINT, 0, 0);
}

void lgchrome_set_bitmap(int slot, const void *bgraPremultiplied, int width, int height) {
    if (slot < 0 || slot > 1) return;
    if (g_csReady) EnterCriticalSection(&g_cs);
    Bitmap *b = &g_bmp[slot];
    if (b->px) {
        free(b->px);
        b->px = NULL;
    }
    b->w = b->h = 0;
    if (bgraPremultiplied && width > 0 && height > 0) {
        size_t n = (size_t) width * height * 4;
        b->px = (unsigned char *) malloc(n);
        /* 复制一份: Kotlin 侧的 Memory 生命周期与我们无关, 不能长期引用它 */
        if (b->px) {
            memcpy(b->px, bgraPremultiplied, n);
            b->w = width;
            b->h = height;
        }
    }
    if (g_csReady) LeaveCriticalSection(&g_cs);
    if (g_frame) PostMessageW(g_frame, LGC_WM_REPAINT, 0, 0);
}

void lgchrome_set_theme(unsigned int bg, unsigned int fg, int dark, int inactiveAlpha) {
    g_bg = bg | 0xFF000000u;
    g_fg = fg | 0xFF000000u;
    g_dark = dark ? 1 : 0;
    g_inactiveAlpha = (inactiveAlpha < 0) ? 0 : (inactiveAlpha > 255 ? 255 : inactiveAlpha);
    if (g_frame) PostMessageW(g_frame, LGC_WM_REPAINT, 0, 0);
}

void lgchrome_set_fullscreen(int fullscreen) {
    int v = fullscreen ? 1 : 0;
    if (v == g_suspended) return;
    g_suspended = v;
    if (!g_frame) return;
    if (v) {
        if (g_strip) ShowWindow(g_strip, SW_HIDE);
    } else {
        reposition();
    }
    SetWindowPos(g_frame, NULL, 0, 0, 0, 0,
            SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
}

int lgchrome_last_error(void) {
    return g_err;
}

int lgchrome_last_os_error(void) {
    return g_osErr;
}
