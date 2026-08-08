/*
 * smtc_bridge.c — Windows SMTC 纯 C 桥 (MinGW 可编译, 无 MSVC/Windows SDK 依赖)
 *
 * 路线: 官方 Win32 手动集成路径 ISystemMediaTransportControlsInterop::GetForWindow
 * (mpv/souvlaki 范式), 不激活 MediaPlayer。JVM 侧经 JNA 调扁平 C 函数,
 * 播放控制命令经回调函数指针回到 JVM。
 *
 * # 线程模型 (硬性要求)
 * SMTC 对象活在本桥自建的工作线程里, 该线程 RoInitialize(STA) 并跑消息泵。
 * STA 不泵消息就无法响应进来的跨进程调用 (缩略图流打开/按钮事件),
 * 调用方 (explorer Taskbar.View) 拿到失败 HRESULT 后抛出 → 0xc000027b。
 * 所以 lgsmtc_update 只把状态写进 g_pending, 再 PostThreadMessage 让工作线程去应用,
 * 任何 WinRT 对象都不跨线程调用。
 *
 * # DisplayUpdater 契约 (踩坑记录)
 * ClearAll() 会把 Type 重置为 Unknown, 而 Type=Unknown 时 MusicProperties 的写入不生效:
 * 消费方读到的是 Title 空 + MediaProperties.PlaybackType = null 的半初始化卡,
 * C++/WinRT 消费方对空 IReference 调 .Value() 即抛 → Taskbar.View 悬停崩溃。
 * 顺序必须是 ClearAll → put_Type → 写属性 → Update, 且每次改元数据都要重走一遍。
 */
#include <windows.h>

#include <objbase.h>
#include <unknwn.h>
#include <string.h>
#include <wchar.h>

/* ---- 接口 IID (windows-rs / MinGW 头文件权威验证) ---- */
static const IID IID_ISMTC = {0x99fa3ff4, 0x1742, 0x42a6, {0x90, 0x2e, 0x08, 0x7d, 0x41, 0xf9, 0x65, 0xec}};
static const IID IID_ISMTC2 = {0xea98d2f6, 0x7f3c, 0x4af2, {0xa5, 0x86, 0x72, 0x88, 0x98, 0x08, 0xef, 0xb1}};
static const IID IID_INTEROP = {0xddb0472d, 0xc911, 0x4a1f, {0x86, 0xd9, 0xdc, 0x3d, 0x71, 0xa9, 0x5f, 0x5a}};
static const IID IID_IAGILE = {0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
static const IID IID_URI_FACTORY = {0x44a9796f, 0x723e, 0x4fdf, {0xa2, 0x18, 0x03, 0x3e, 0x75, 0xb0, 0xc0, 0x84}};
static const IID IID_RASR_STATICS = {0x857309dc, 0x3fbf, 0x4e7d, {0x98, 0x6f, 0xef, 0x3b, 0x1a, 0x07, 0xa9, 0x64}};
static const IID IID_BTN_ARGS = {0xb7f47116, 0xa56f, 0x4dc8, {0x9e, 0x11, 0x92, 0x03, 0x1f, 0x4a, 0x87, 0xc2}};
static const IID IID_POS_ARGS = {0xb4493f88, 0xeb28, 0x4961, {0x9c, 0x14, 0x33, 0x5e, 0x44, 0xf3, 0xe1, 0x25}};
/* TypedEventHandler 参数化 IID (windows.media.h DEFINE_GUID 权威):
 * ButtonPressed = 0557E996-7B23-5BAE-AA81-EA0D671143A4
 * PlaybackPositionChangeRequested = 44E34F15-BDC0-50A7-ACE4-39E91FB753F1 */
static const IID IID_TYPEDEVT_BTN = {0x0557e996, 0x7b23, 0x5bae, {0xaa, 0x81, 0xea, 0x0d, 0x67, 0x11, 0x43, 0xa4}};
static const IID IID_TYPEDEVT_POS = {0x44e34f15, 0xbdc0, 0x50a7, {0xac, 0xe4, 0x39, 0xe9, 0x1f, 0xb7, 0x53, 0xf1}};
static const IID IID_TL_PROPS = {0x5125316a, 0xc3a2, 0x475b, {0x85, 0x07, 0x93, 0x53, 0x4d, 0xc8, 0x8f, 0x15}};

/* ---- 枚举 (WinRT) ---- */
#define PLAYBACK_CLOSED 0
#define PLAYBACK_STOPPED 2
#define PLAYBACK_PLAYING 3
#define PLAYBACK_PAUSED 4
#define TYPE_MUSIC 1

/* 回传命令 (JVM 侧约定): 播放控制用系统 Button 枚举直传; seek 单独命令 */
#define LG_CMD_SEEK 5

/* 封面推送 (Uri → RandomAccessStreamReference → put_Thumbnail)。
 * 跨进程流由消费方打开, 依赖本桥 STA 的消息泵能被服务 —— 线程模型修好后才可开。 */
/* 封面缩略图 (原版行为: 媒体卡显示封面)。
 * A/B 实证已确认悬停闪退根因是"会话绑到无任务栏按钮的隐藏窗口", 与封面无关,
 * 故恢复启用。CreateFromUri 交给消费方的是延迟流, 下载在 explorer 侧发生;
 * 失败只表现为无缩略图, 不影响会话本身。 */
#define ENABLE_COVER 1

/* ---- combase.dll 动态加载 (避免链接依赖) ---- */
typedef HRESULT (WINAPI
*PFN_ROINITIALIZE)(int);
typedef HRESULT (WINAPI
*PFN_ROUNINITIALIZE)(void);
typedef HRESULT (WINAPI
*PFN_ROGETACTIVATIONFACTORY)(void*, const IID*, void**);
/* RoActivateInstance(HSTRING, IInspectable**) —— 只有两个参数。
 * 旧实现按三参数声明 (多塞了一个 IID*), 于是把返回的对象指针写进只读的静态 GUID
 * 里 → 访问冲突; 这就是此前"连 Windows.Foundation.Uri 都段错误, 判定系统级不可用"的真因。 */
typedef HRESULT (WINAPI
*PFN_ROACTIVATEINSTANCE)(void*, void**);
typedef HRESULT (WINAPI
*PFN_WINDOWSCREATESTRING)(const wchar_t*, unsigned int, void**);
typedef HRESULT (WINAPI
*PFN_WINDOWSDELETESTRING)(void*);

static PFN_ROINITIALIZE pfnRoInitialize;
static PFN_ROUNINITIALIZE pfnRoUninitialize;
static PFN_ROGETACTIVATIONFACTORY pfnRoGetActivationFactory;
static PFN_ROACTIVATEINSTANCE pfnRoActivateInstance;
static PFN_WINDOWSCREATESTRING pfnWindowsCreateString;
static PFN_WINDOWSDELETESTRING pfnWindowsDeleteString;

static int load_combase(void) {
    if (pfnRoInitialize) return 1;
    HMODULE m = LoadLibraryW(L"combase.dll");
    if (!m) return 0;
    pfnRoInitialize = (PFN_ROINITIALIZE) GetProcAddress(m, "RoInitialize");
    pfnRoUninitialize = (PFN_ROUNINITIALIZE) GetProcAddress(m, "RoUninitialize");
    pfnRoGetActivationFactory = (PFN_ROGETACTIVATIONFACTORY) GetProcAddress(m, "RoGetActivationFactory");
    pfnRoActivateInstance = (PFN_ROACTIVATEINSTANCE) GetProcAddress(m, "RoActivateInstance");
    pfnWindowsCreateString = (PFN_WINDOWSCREATESTRING) GetProcAddress(m, "WindowsCreateString");
    pfnWindowsDeleteString = (PFN_WINDOWSDELETESTRING) GetProcAddress(m, "WindowsDeleteString");
    return pfnRoInitialize && pfnRoGetActivationFactory && pfnWindowsCreateString ? 1 : 0;
}

/* ---- HSTRING 助手 ---- */
static void *hstr(const wchar_t *s) {
    void *out = NULL;
    if (pfnWindowsCreateString && pfnWindowsCreateString(s, (unsigned int) wcslen(s), &out) == 0)
        return out;
    return NULL;
}

static void hstr_free(void *h) {
    if (h && pfnWindowsDeleteString) pfnWindowsDeleteString(h);
}

/* ---- vtable 结构 (WinRT 接口继承 IInspectable: 槽 0-5 固定, 业务方法从 6 起) ---- */

/* ISystemMediaTransportControls: 30 业务方法, 槽 6..35 */
typedef struct SMTCVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_PlaybackStatus)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *put_PlaybackStatus)(void*, int);
    HRESULT (STDMETHODCALLTYPE *get_DisplayUpdater)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *get_SoundLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_IsEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsPlayEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsPlayEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsStopEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsStopEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsPauseEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsPauseEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsRecordEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsRecordEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsFastForwardEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsFastForwardEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsRewindEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsRewindEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsPreviousEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsPreviousEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsNextEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsNextEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsChannelUpEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsChannelUpEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_IsChannelDownEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_IsChannelDownEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *add_ButtonPressed)(void*, void*, unsigned long long*);
    HRESULT (STDMETHODCALLTYPE *remove_ButtonPressed)(void*, unsigned long long);
    HRESULT (STDMETHODCALLTYPE *add_PropertyChanged)(void*, void*, unsigned long long*);
    HRESULT (STDMETHODCALLTYPE *remove_PropertyChanged)(void*, unsigned long long);
} SMTCVtbl;

/* ISystemMediaTransportControls2: 6/7 AutoRepeatMode, 8/9 Shuffle, 10/11 PlaybackRate,
 * 12 UpdateTimelineProperties, 13/14 PlaybackPositionChangeRequested,
 * 15/16 PlaybackRateChangeRequested (windows.media.h 验证)。 */
typedef struct SMTC2Vtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_AutoRepeatMode)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *put_AutoRepeatMode)(void*, int);
    HRESULT (STDMETHODCALLTYPE *get_ShuffleEnabled)(void*, BOOL*);
    HRESULT (STDMETHODCALLTYPE *put_ShuffleEnabled)(void*, BOOL);
    HRESULT (STDMETHODCALLTYPE *get_PlaybackRate)(void*, double*);
    HRESULT (STDMETHODCALLTYPE *put_PlaybackRate)(void*, double);
    HRESULT (STDMETHODCALLTYPE *UpdateTimelineProperties)(void*, void*);
    HRESULT (STDMETHODCALLTYPE *add_PlaybackPositionChangeRequested)(void*, void*, unsigned long long*);
    HRESULT (STDMETHODCALLTYPE *remove_PlaybackPositionChangeRequested)(void*, unsigned long long);
    HRESULT (STDMETHODCALLTYPE *add_PlaybackRateChangeRequested)(void*, void*, unsigned long long*);
    HRESULT (STDMETHODCALLTYPE *remove_PlaybackRateChangeRequested)(void*, unsigned long long);
} SMTC2Vtbl;

/* ISystemMediaTransportControlsDisplayUpdater: 槽 6..17 */
typedef struct DisplayUpdaterVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_Type)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *put_Type)(void*, int);
    HRESULT (STDMETHODCALLTYPE *get_AppMediaId)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *put_AppMediaId)(void*, void*);
    HRESULT (STDMETHODCALLTYPE *get_Thumbnail)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *put_Thumbnail)(void*, void*);
    HRESULT (STDMETHODCALLTYPE *get_MusicProperties)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *get_VideoProperties)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *get_ImageProperties)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *CopyFromFileAsync)(void*, void*, void*);
    HRESULT (STDMETHODCALLTYPE *ClearAll)(void*);
    HRESULT (STDMETHODCALLTYPE *Update)(void*);
} DisplayUpdaterVtbl;

/* IMusicDisplayProperties: 槽 6..11 */
typedef struct MusicPropsVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_Title)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *put_Title)(void*, void*);
    HRESULT (STDMETHODCALLTYPE *get_AlbumArtist)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *put_AlbumArtist)(void*, void*);
    HRESULT (STDMETHODCALLTYPE *get_Artist)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *put_Artist)(void*, void*);
} MusicPropsVtbl;

/* ISystemMediaTransportControlsTimelineProperties: 槽 6..15 */
typedef struct TimelinePropsVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_StartTime)(void*, long long*);
    HRESULT (STDMETHODCALLTYPE *put_StartTime)(void*, long long);
    HRESULT (STDMETHODCALLTYPE *get_EndTime)(void*, long long*);
    HRESULT (STDMETHODCALLTYPE *put_EndTime)(void*, long long);
    HRESULT (STDMETHODCALLTYPE *get_MinSeekTime)(void*, long long*);
    HRESULT (STDMETHODCALLTYPE *put_MinSeekTime)(void*, long long);
    HRESULT (STDMETHODCALLTYPE *get_MaxSeekTime)(void*, long long*);
    HRESULT (STDMETHODCALLTYPE *put_MaxSeekTime)(void*, long long);
    HRESULT (STDMETHODCALLTYPE *get_Position)(void*, long long*);
    HRESULT (STDMETHODCALLTYPE *put_Position)(void*, long long);
} TimelinePropsVtbl;

/* ISystemMediaTransportControlsInterop: GetForWindow = 槽 6 */
typedef struct InteropVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *GetForWindow)(void*, HWND, const IID*, void**);
} InteropVtbl;

/* IUriRuntimeClassFactory: CreateUri = 槽 6 */
typedef struct UriFactoryVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *CreateUri)(void*, void*, void**);
} UriFactoryVtbl;

/* IRandomAccessStreamReferenceStatics: CreateFromUri = 槽 7 */
typedef struct RasrStaticsVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get_FromUri)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *CreateFromUri)(void*, void*, void**);
} RasrStaticsVtbl;

/* 事件 args (IInspectable 派生): 槽 6 = 值 getter */
typedef struct ArgsVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(void*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(void*);
    ULONG (STDMETHODCALLTYPE *Release)(void*);
    HRESULT (STDMETHODCALLTYPE *GetIids)(void*, unsigned long*, IID**);
    HRESULT (STDMETHODCALLTYPE *GetRuntimeClassName)(void*, void**);
    HRESULT (STDMETHODCALLTYPE *GetTrustLevel)(void*, int*);
    HRESULT (STDMETHODCALLTYPE *get)(void*, void*);
} ArgsVtbl;

/* 具体类型 vtable 调用。旧实现用 varargs 函数指针宏, x64 下与系统 COM stub 的
 * 参数解释不兼容 → 调用即段错误, 故一律走具体签名。 */
#define VT(obj, vtblname) (*(vtblname##Vtbl**)(obj))

/* ---- 事件回调对象 ----
 * WinRT delegate (TypedEventHandler) 派生自 IUnknown, 不是 IInspectable:
 * vtable 只有 4 槽, Invoke 在槽 3。旧实现按 IInspectable 布局写了 7 槽,
 * 系统调 Invoke(槽3) 实际命中 GetIids, 把 0 写进 sender/args 指向的系统对象里。
 * 同理 QI 必须拒绝 IInspectable —— 认领了系统就会按 7 槽布局调用。 */
typedef struct Handler {
    const struct HandlerVtbl *vtbl;
    LONG refs;
    void *cb;
    const IID *iid;      /* 本 handler 只认领这个 delegate IID */
    int is_position;
} Handler;

typedef struct HandlerVtbl {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(Handler*, const IID*, void**);
    ULONG (STDMETHODCALLTYPE *AddRef)(Handler*);
    ULONG (STDMETHODCALLTYPE *Release)(Handler*);
    HRESULT (STDMETHODCALLTYPE *Invoke)(Handler*, void*, void*);
} HandlerVtbl;

/* JVM 回调 (经 JNA Callback 注册): cmd 用系统 Button 枚举直传, seek 用 LG_CMD_SEEK + arg=ms */
typedef void (WINAPI
*lgsmtc_cmd_cb)(
int cmd,
long long arg
);

static HRESULT STDMETHODCALLTYPE
h_QueryInterface(Handler
* self,
const IID *riid,
void **ppv
) {
if (!ppv) return
E_POINTER;
if (
IsEqualIID(riid,
&IID_IUnknown) ||
IsEqualIID(riid,
&IID_IAGILE) ||
IsEqualIID(riid, self
->iid)) {
*
ppv = self;
InterlockedIncrement(&self->refs);
return
S_OK;
}
*
ppv = NULL;
return
E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE
h_AddRef(Handler
* self) {
return (ULONG)InterlockedIncrement(&self->refs);
}
static ULONG STDMETHODCALLTYPE
h_Release(Handler
* self) {
/* 对象静态分配, 不释放内存; 只维持引用计数语义 */
return (ULONG)InterlockedDecrement(&self->refs);
}
static HRESULT STDMETHODCALLTYPE
h_Invoke(Handler
* self,
void *sender,
void *args
) {
(void)
sender;
lgsmtc_cmd_cb cb = (lgsmtc_cmd_cb) self->cb;
if (!cb || !args) return
S_OK;
void *itf = NULL;
if (self->is_position) {
if (VT(args, Args)->
QueryInterface(args,
&IID_POS_ARGS, &itf) == 0 && itf) {
long long pos100ns = 0;
HRESULT hr = VT(itf, Args)->get(itf, &pos100ns);
VT(itf, Args)->
Release(itf);
if (hr == 0) cb(LG_CMD_SEEK, pos100ns / 10000);  /* 100ns -> ms */
}
} else {
if (VT(args, Args)->
QueryInterface(args,
&IID_BTN_ARGS, &itf) == 0 && itf) {
int button = 0;
HRESULT hr = VT(itf, Args)->get(itf, &button);
VT(itf, Args)->
Release(itf);
if (hr == 0)
cb(button,
0);   /* 系统 Button 枚举直传 */
}
}
return
S_OK;
}

static const HandlerVtbl g_handler_vtbl = {
        h_QueryInterface, h_AddRef, h_Release, h_Invoke,
};

/* ---- 状态 ----
 * COM 对象只在工作线程访问; g_pending 由锁保护, 跨线程只传值。 */
typedef struct SmtcSnapshot {
    wchar_t title[512];
    wchar_t artist[512];
    wchar_t album[512];
    wchar_t cover[1024];
    int playing;
    int paused;
    int prevnext;
    long long pos_ms;
    long long dur_ms;
    double rate;
} SmtcSnapshot;

static void *g_smtc;
static void *g_smtc2;
static void *g_display;
static void *g_timeline;
static void *g_uri_factory;
static void *g_rasr_statics;
static Handler g_btn_handler;
static Handler g_pos_handler;
static unsigned long long g_btn_token;
static unsigned long long g_pos_token;
static HWND g_host_hwnd;          /* GetForWindow 绑定的窗口 */
static HWND g_own_hwnd;           /* 未传入主窗口时自建的兜底窗口 */
static int g_inited;

static HANDLE g_thread;
static DWORD g_tid;
static HANDLE g_ready;
static int g_init_rc = -1;
static long g_last_hr = 0;         /* GetForWindow 等最近一次失败的真实 HRESULT (诊断用) */
static CRITICAL_SECTION g_lock;
static int g_lock_ready;
static LONG g_apply_posted;

static SmtcSnapshot g_pending;
static SmtcSnapshot g_applied;
static int g_has_applied;
static DWORD g_last_timeline_tick;

#define LGM_APPLY (WM_APP + 11)
#define LGM_QUIT  (WM_APP + 12)
#define SMTC_WINDOW_CLASS L"LegadoSmtcWindow"

static void copy_ws(wchar_t *dst, size_t cap, const wchar_t *src) {
    if (!src) {
        dst[0] = L'\0';
        return;
    }
    size_t n = wcslen(src);
    if (n >= cap) n = cap - 1;
    memcpy(dst, src, n * sizeof(wchar_t));
    dst[n] = L'\0';
}

/* ---- 封面 (Uri → RandomAccessStreamReference → put_Thumbnail) ---- */
static void push_cover(const wchar_t *cover) {
    if (!ENABLE_COVER) return;
    if (!cover || !*cover || !g_display) return;
    /* CreateUri 只接受绝对 URI; 本地路径 (C:\...) 会失败, 直接跳过不浪费一次跨进程调用 */
    if (_wcsnicmp(cover, L"http://", 7) != 0 && _wcsnicmp(cover, L"https://", 8) != 0 &&
            _wcsnicmp(cover, L"file://", 7) != 0) {
        return;
    }
    if (!g_uri_factory) {
        void *h = hstr(L"Windows.Foundation.Uri");
        if (h) {
            void *f = NULL;
            if (pfnRoGetActivationFactory(h, &IID_URI_FACTORY, &f) == 0 && f) g_uri_factory = f;
            hstr_free(h);
        }
    }
    if (!g_uri_factory) return;
    if (!g_rasr_statics) {
        void *h = hstr(L"Windows.Storage.Streams.RandomAccessStreamReference");
        if (h) {
            void *f = NULL;
            if (pfnRoGetActivationFactory(h, &IID_RASR_STATICS, &f) == 0 && f) g_rasr_statics = f;
            hstr_free(h);
        }
    }
    if (!g_rasr_statics) return;
    void *h_url = hstr(cover);
    if (!h_url) return;
    void *uri = NULL;
    HRESULT hr = VT(g_uri_factory, UriFactory)->CreateUri(g_uri_factory, h_url, &uri);
    hstr_free(h_url);
    if (hr != 0 || !uri) return;
    void *stream = NULL;
    hr = VT(g_rasr_statics, RasrStatics)->CreateFromUri(g_rasr_statics, uri, &stream);
    VT(uri, UriFactory)->Release(uri);
    if (hr != 0 || !stream) return;
    VT(g_display, DisplayUpdater)->put_Thumbnail(g_display, stream);
    VT(stream, RasrStatics)->Release(stream);
}

/* ---- 元数据提交 (ClearAll → put_Type → 写属性 → Update, 见文件头契约说明) ---- */
static void push_metadata(const SmtcSnapshot *s) {
    if (!g_display) return;
    VT(g_display, DisplayUpdater)->ClearAll(g_display);
    VT(g_display, DisplayUpdater)->put_Type(g_display, TYPE_MUSIC);
    void *mp = NULL;
    if (VT(g_display, DisplayUpdater)->get_MusicProperties(g_display, &mp) == 0 && mp) {
        void *h;
        if ((h = hstr(s->title)) != NULL) {
            VT(mp, MusicProps)->put_Title(mp, h);
            hstr_free(h);
        }
        if ((h = hstr(s->artist)) != NULL) {
            VT(mp, MusicProps)->put_Artist(mp, h);
            hstr_free(h);
        }
        if ((h = hstr(s->album)) != NULL) {
            VT(mp, MusicProps)->put_AlbumArtist(mp, h);
            hstr_free(h);
        }
        VT(mp, MusicProps)->Release(mp);
    }
    push_cover(s->cover);   /* ClearAll 也清掉缩略图, 每次重推 */
    VT(g_display, DisplayUpdater)->Update(g_display);
}

/* ---- 进度 (节流 ~5s; 切章/时长变化立即推, 官方建议频率) ---- */
static void push_timeline(long long pos_ms, long long dur_ms, int force) {
    if (!g_smtc2 || !g_timeline) return;
    if (dur_ms <= 0) return;
    DWORD now = GetTickCount();
    if (!force && g_has_applied && g_applied.dur_ms == dur_ms &&
            now - g_last_timeline_tick < 5000) {
        return;
    }
    long long end = dur_ms * 10000;
    long long pos = (pos_ms < 0 ? 0 : (pos_ms > dur_ms ? dur_ms : pos_ms)) * 10000;
    VT(g_timeline, TimelineProps)->put_StartTime(g_timeline, 0);
    VT(g_timeline, TimelineProps)->put_EndTime(g_timeline, end);
    VT(g_timeline, TimelineProps)->put_MinSeekTime(g_timeline, 0);
    VT(g_timeline, TimelineProps)->put_MaxSeekTime(g_timeline, end);
    VT(g_timeline, TimelineProps)->put_Position(g_timeline, pos);
    VT(g_smtc2, SMTC2)->UpdateTimelineProperties(g_smtc2, g_timeline);
    g_last_timeline_tick = now;
}

/* ---- 应用待推状态 (只在工作线程执行) ---- */
static void apply_pending(void) {
    SmtcSnapshot s;
    EnterCriticalSection(&g_lock);
    s = g_pending;
    LeaveCriticalSection(&g_lock);
    if (!g_smtc) return;

    /* 没有元数据就不发布会话: 空标题 + Type 未定的卡片正是消费方渲染时抛异常的形态 */
    if (!s.title[0] && !s.artist[0]) {
        VT(g_smtc, SMTC)->put_PlaybackStatus(g_smtc, PLAYBACK_CLOSED);
        VT(g_smtc, SMTC)->put_IsEnabled(g_smtc, FALSE);
        g_has_applied = 0;
        return;
    }

    int meta_changed = !g_has_applied ||
            wcscmp(s.title, g_applied.title) != 0 ||
            wcscmp(s.artist, g_applied.artist) != 0 ||
            wcscmp(s.album, g_applied.album) != 0 ||
            wcscmp(s.cover, g_applied.cover) != 0;
    int state_changed = !g_has_applied ||
            s.playing != g_applied.playing ||
            s.paused != g_applied.paused ||
            s.prevnext != g_applied.prevnext;

    /* 元数据先落地再开启会话: 避免消费方看到"已启用但还没内容"的中间态 */
    if (meta_changed) push_metadata(&s);

    if (state_changed) {
        int status = s.playing ? PLAYBACK_PLAYING : (s.paused ? PLAYBACK_PAUSED : PLAYBACK_STOPPED);
        VT(g_smtc, SMTC)->put_PlaybackStatus(g_smtc, status);
        VT(g_smtc, SMTC)->put_IsPlayEnabled(g_smtc, s.playing ? FALSE : TRUE);
        VT(g_smtc, SMTC)->put_IsPauseEnabled(g_smtc, s.playing ? TRUE : FALSE);
        VT(g_smtc, SMTC)->put_IsStopEnabled(g_smtc, TRUE);
        VT(g_smtc, SMTC)->put_IsPreviousEnabled(g_smtc, s.prevnext ? TRUE : FALSE);
        VT(g_smtc, SMTC)->put_IsNextEnabled(g_smtc, s.prevnext ? TRUE : FALSE);
        VT(g_smtc, SMTC)->put_IsEnabled(g_smtc, TRUE);
    }
    if (g_smtc2 && s.rate > 0 && (!g_has_applied || s.rate != g_applied.rate)) {
        VT(g_smtc2, SMTC2)->put_PlaybackRate(g_smtc2, s.rate);
    }
    push_timeline(s.pos_ms, s.dur_ms, meta_changed);

    g_applied = s;
    g_has_applied = 1;
}

/* ---- 工作线程: 建会话 + 跑消息泵 + 收尾释放 ---- */
static int setup_session(void) {
    if (!load_combase()) return -1;
    HRESULT hr = pfnRoInitialize(0 /*RO_INIT_SINGLETHREADED*/);
    if (hr != 0 && hr != 1 /*S_FALSE*/) return (int) hr;

    /* 没传主窗口时自建兜底窗口 (会话需要一个 HWND 做归属) */
    if (!g_host_hwnd) {
        WNDCLASSW wc = {0};
        wc.lpfnWndProc = DefWindowProcW;
        wc.hInstance = GetModuleHandleW(NULL);
        wc.lpszClassName = SMTC_WINDOW_CLASS;
        RegisterClassW(&wc);
        /* 隐形归属窗口: WS_POPUP 无边框 + 0 尺寸 + 屏外,
           WS_EX_TOOLWINDOW 不进任务栏/Alt-Tab, WS_EX_NOACTIVATE 不抢焦点。
           CreateWindowExW 已实体化窗口, GetForWindow 只需句柄合法, 无需 ShowWindow。 */
        g_own_hwnd = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                SMTC_WINDOW_CLASS, L"legado-smtc",
                WS_POPUP,
                -32000, -32000, 0, 0,
                NULL, NULL, wc.hInstance, NULL);
        if (!g_own_hwnd) return -2;
        g_host_hwnd = g_own_hwnd;
    }

    void *hstr_cls = hstr(L"Windows.Media.SystemMediaTransportControls");
    if (!hstr_cls) return -3;
    void *factory = NULL;
    hr = pfnRoGetActivationFactory(hstr_cls, &IID_INTEROP, &factory);
    hstr_free(hstr_cls);
    if (hr != 0 || !factory) return -4;

    void *smtc = NULL;
    hr = VT(factory, Interop)->GetForWindow(factory, g_host_hwnd, &IID_ISMTC, &smtc);
    VT(factory, Interop)->Release(factory);
    if (hr != 0 || !smtc) {
        g_last_hr = (long) hr;
        return -5;
    }
    g_smtc = smtc;

    void *s2 = NULL;
    if (VT(smtc, SMTC)->QueryInterface(smtc, &IID_ISMTC2, &s2) == 0 && s2) g_smtc2 = s2;

    void *du = NULL;
    if (VT(smtc, SMTC)->get_DisplayUpdater(smtc, &du) == 0 && du) {
        g_display = du;
        /* 起始态: 清空并声明 Music (Type 必须在 ClearAll 之后设, 否则留在 Unknown) */
        VT(du, DisplayUpdater)->ClearAll(du);
        VT(du, DisplayUpdater)->put_Type(du, TYPE_MUSIC);
        VT(du, DisplayUpdater)->Update(du);
    }

    g_btn_handler.vtbl = &g_handler_vtbl;
    g_btn_handler.refs = 1;
    g_btn_handler.iid = &IID_TYPEDEVT_BTN;
    g_btn_handler.is_position = 0;
    VT(smtc, SMTC)->add_ButtonPressed(smtc, &g_btn_handler, &g_btn_token);

    if (g_smtc2) {
        g_pos_handler.vtbl = &g_handler_vtbl;
        g_pos_handler.refs = 1;
        g_pos_handler.iid = &IID_TYPEDEVT_POS;
        g_pos_handler.is_position = 1;
        VT(g_smtc2, SMTC2)->add_PlaybackPositionChangeRequested(
                g_smtc2, &g_pos_handler, &g_pos_token);
    }

    void *h_tl = hstr(L"Windows.Media.SystemMediaTransportControlsTimelineProperties");
    if (h_tl) {
        void *insp = NULL;
        if (pfnRoActivateInstance(h_tl, &insp) == 0 && insp) {
            void *tl = NULL;
            if (VT(insp, TimelineProps)->QueryInterface(insp, &IID_TL_PROPS, &tl) == 0 && tl) {
                g_timeline = tl;
            }
            VT(insp, TimelineProps)->Release(insp);
        }
        hstr_free(h_tl);
    }

    /* 还没有内容前不要发布会话 (空卡 = 消费方崩溃形态) */
    VT(smtc, SMTC)->put_PlaybackStatus(smtc, PLAYBACK_CLOSED);
    VT(smtc, SMTC)->put_IsEnabled(smtc, FALSE);
    return 0;
}

static void teardown_session(void) {
    if (g_smtc) {
        if (g_btn_token) {
            VT(g_smtc, SMTC)->remove_ButtonPressed(g_smtc, g_btn_token);
            g_btn_token = 0;
        }
        VT(g_smtc, SMTC)->put_PlaybackStatus(g_smtc, PLAYBACK_CLOSED);
        VT(g_smtc, SMTC)->put_IsEnabled(g_smtc, FALSE);
    }
    if (g_smtc2 && g_pos_token) {
        VT(g_smtc2, SMTC2)->remove_PlaybackPositionChangeRequested(g_smtc2, g_pos_token);
        g_pos_token = 0;
    }
    if (g_display) VT(g_display, DisplayUpdater)->Release(g_display);
    if (g_timeline) VT(g_timeline, TimelineProps)->Release(g_timeline);
    if (g_uri_factory) VT(g_uri_factory, UriFactory)->Release(g_uri_factory);
    if (g_rasr_statics) VT(g_rasr_statics, RasrStatics)->Release(g_rasr_statics);
    if (g_smtc2) VT(g_smtc2, SMTC2)->Release(g_smtc2);
    if (g_smtc) VT(g_smtc, SMTC)->Release(g_smtc);
    g_smtc = g_smtc2 = g_display = g_timeline = NULL;
    g_uri_factory = g_rasr_statics = NULL;
    if (g_own_hwnd) {
        DestroyWindow(g_own_hwnd);
        g_own_hwnd = NULL;
    }
    g_host_hwnd = NULL;
    g_has_applied = 0;
    if (pfnRoUninitialize) pfnRoUninitialize();
}

static DWORD WINAPI
smtc_worker(LPVOID
param) {
(void)
param;
g_init_rc = setup_session();
SetEvent(g_ready);
if (g_init_rc != 0) {
teardown_session();

return 0;
}
/* STA 必须泵消息才能被系统回调 (缩略图流打开 / 按钮事件都走这里) */
MSG msg;
for (;;) {
BOOL r = GetMessageW(&msg, NULL, 0, 0);
if (r <= 0) break;
if (msg.message == LGM_QUIT) break;
if (msg.message == LGM_APPLY) {
/* 先清标记再应用: 应用期间来的新状态能再投一次, 不会丢 */
InterlockedExchange(&g_apply_posted, 0);

apply_pending();

continue;
}
TranslateMessage(&msg);
DispatchMessageW(&msg);
}

teardown_session();

return 0;
}

/* ---- 导出 API (JVM JNA 调用) ---- */

/**
 * 初始化 (幂等)。hwnd = 主窗口句柄, 会话归属于它 (任务栏悬停媒体卡按窗口关联);
 * 传 NULL 则内部自建兜底窗口。返回 0 = 成功, 非 0 = 失败 (调用方熔断)。
 */
__declspec(dllexport) int lgsmtc_init(void *hwnd, void *cb) {
    if (g_inited) return 0;
    if (!g_lock_ready) {
        InitializeCriticalSection(&g_lock);
        g_lock_ready = 1;
    }
    g_host_hwnd = (HWND) hwnd;
    g_btn_handler.cb = cb;
    g_pos_handler.cb = cb;
    g_ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!g_ready) return -10;
    g_init_rc = -1;
    g_thread = CreateThread(NULL, 0, smtc_worker, NULL, 0, &g_tid);
    if (!g_thread) {
        CloseHandle(g_ready);
        g_ready = NULL;
        return -11;
    }
    if (WaitForSingleObject(g_ready, 8000) != WAIT_OBJECT_0) return -12;
    CloseHandle(g_ready);
    g_ready = NULL;
    if (g_init_rc != 0) {
        WaitForSingleObject(g_thread, 3000);
        CloseHandle(g_thread);
        g_thread = NULL;
        return g_init_rc;
    }
    g_inited = 1;
    return 0;
}

/** 诊断: 返回最近一次失败的真实 HRESULT (如 GetForWindow 的 -5 分支)。 */
__declspec(dllexport) long lgsmtc_last_hr(void) {
    return g_last_hr;
}

/** 推送状态/元数据/进度 (只写快照并唤醒工作线程; WinRT 调用一律在工作线程做)。 */
__declspec(dllexport) void lgsmtc_update(
        const wchar_t *title, const wchar_t *artist, const wchar_t *album,
        const wchar_t *cover, int playing, int paused, int prevnext,
        long long pos_ms, long long dur_ms, double rate) {
    if (!g_inited) return;
    EnterCriticalSection(&g_lock);
    copy_ws(g_pending.title, 512, title);
    copy_ws(g_pending.artist, 512, artist);
    copy_ws(g_pending.album, 512, album);
    copy_ws(g_pending.cover, 1024, cover);
    g_pending.playing = playing;
    g_pending.paused = paused;
    g_pending.prevnext = prevnext;
    g_pending.pos_ms = pos_ms;
    g_pending.dur_ms = dur_ms;
    g_pending.rate = rate;
    LeaveCriticalSection(&g_lock);
    /* 合并投递: 已有未处理的 APPLY 就不再投 (快照本身是最新值) */
    if (InterlockedExchange(&g_apply_posted, 1) == 0) {
        if (!PostThreadMessageW(g_tid, LGM_APPLY, 0, 0)) {
            InterlockedExchange(&g_apply_posted, 0);
        }
    }
}

/** 摘除事件 + 释放全部 COM 引用 + 停工作线程 (幂等; 之后可重新 init)。 */
__declspec(dllexport) void lgsmtc_release(void) {
    if (!g_inited) return;
    g_inited = 0;
    if (g_thread) {
        PostThreadMessageW(g_tid, LGM_QUIT, 0, 0);
        WaitForSingleObject(g_thread, 5000);
        CloseHandle(g_thread);
        g_thread = NULL;
    }
    g_tid = 0;
    InterlockedExchange(&g_apply_posted, 0);
    EnterCriticalSection(&g_lock);
    memset(&g_pending, 0, sizeof(g_pending));
    LeaveCriticalSection(&g_lock);
    memset(&g_applied, 0, sizeof(g_applied));
}
