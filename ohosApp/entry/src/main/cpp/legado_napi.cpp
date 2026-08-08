/**
 * legado_napi.cpp - 鸿蒙 napi 桥接层实现。
 *
 * 用途: 把 liblegado_shared.so (Kotlin/Native) 中的 @CName 导出函数
 *       包装为 napi 方法, 暴露给 ArkTS 调用。
 *
 * 函数列表 (与 LegadoNativeExports.kt @CName 一一对应):
 *
 * 工具类 (KP4 已存在):
 * - chineseT2S(text: string): string        → legado_chinese_t2s(const char*)
 * - chineseS2T(text: string): string        → legado_chinese_s2t(const char*)
 * - md5Encode(text: string): string          → legado_md5_encode(const char*)
 * - formatPercentUs(value: number): string  → legado_format_percent_us(double)
 * - isProvidersRegistered(): boolean        → legado_providers_registered() -> int
 * - registerOhosProviders(): void           → legado_register_providers()
 *
 * 业务类 (KP5 新增, 接入真实 KMP 数据):
 * - bookshelfList(): string                 → legado_bookshelf_list() -> const char* (JSON 数组)
 * - searchBook(query: string): string       → legado_search_book(const char*) -> const char* (JSON 数组)
 * - loadChapter(bookUrl: string, chapterIndex: number): string
 *                                            → legado_load_chapter(const char*, int) -> const char* (章节正文)
 * - chapterList(bookUrl: string): string    → legado_chapter_list(const char*) -> const char* (章节目录 JSON 数组)
 * - importBookSource(json: string): number  → legado_import_booksource(const char*) -> int (导入数量)
 *
 * FileDir/CacheDir 路径注入 (KP7+ 新增, ArkTS → Kotlin 同步推送):
 * - registerFileDir(path: string): void     → legado_register_file_dir(const char*) (注入 filesDir)
 * - registerCacheDir(path: string): void    → legado_register_cache_dir(const char*) (注入 cacheDir)
 *
 * legado:// deep link 投递 (ArkTS → Kotlin 同步推送, 无回调):
 * - handleDeepLink(uri: string): boolean    → legado_handle_deep_link(const char*) -> int
 *                                            (Kotlin LegadoDeepLinkHandler 解析后写 pending, Compose 侧消费)
 *
 * Toast/Notification tsfn 回调注册 (KP7+ 新增, KMP → ArkTS 跨线程 dispatch):
 * - registerToastCallback(cb): void         → C++ 创建 tsfn 包装 cb, 通过 legado_register_toast_fn
 *                                            注入 ohos_toast_dispatch 函数指针到 Kotlin OhosNativeBridge.toastTsfn
 * - registerNotificationCallback(cb): void  → 同上, 注入 ohos_notification_dispatch 到 notificationTsfn
 *
 * Image/Media tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增):
 * - registerImageCallback(cb): void         → 同 Toast, 注入 ohos_image_dispatch 到 imageTsfn
 * - registerMediaCallback(cb): void         → 同 Toast, 注入 ohos_media_dispatch 到 mediaTsfn
 * - imageCallback(requestId, result): void  → ArkTS → Kotlin 图片操作结果 (dlsym legado_image_callback)
 * - mediaEvent(event): void                 → ArkTS → Kotlin media 事件 (dlsym legado_media_event)
 *
 * TTS tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Media 模式):
 * - registerTtsCallback(cb): void           → 同 Toast, 注入 ohos_tts_dispatch 到 ttsTsfn
 * - ttsEvent(event): void                   → ArkTS → Kotlin TTS 事件 (dlsym legado_tts_event)
 *
 * Crypto tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式: encrypt/decrypt/sign/verify):
 * - registerCryptoCallback(cb): void        → 同 Image, 注入 ohos_crypto_dispatch 到 cryptoTsfn
 * - cryptoCallback(requestId, result): void → ArkTS → Kotlin crypto 结果 (dlsym legado_crypto_callback)
 *
 * OpenUrl tsfn 回调注册 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch, 无结果回调):
 * - registerOpenUrlCallback(cb): void       → 同 Toast, 注入 ohos_open_url_dispatch 到 openUrlTsfn
 *                                            (无 openUrlCallback: openUrl 无需返回结果, 与 Toast 同模式)
 *
 * FilePicker tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式: pickDocuments/pickDocumentContent):
 * - registerFilePickerCallback(cb): void    → 同 Image, 注入 ohos_file_picker_dispatch 到 filePickerTsfn
 * - filePickerCallback(requestId, result): void → ArkTS → Kotlin filePicker 结果 (dlsym legado_file_picker_callback)
 *
 * Pasteboard tsfn 回调注册 + ArkTS → Kotlin 回调 (同 FilePicker 模式: read/write 纯文本):
 * - registerPasteboardCallback(cb): void    → 注入 ohos_pasteboard_dispatch 到 pasteboardTsfn
 * - pasteboardCallback(requestId, result): void → ArkTS → Kotlin pasteboard 结果 (dlsym legado_pasteboard_callback)
 *
 * 详细方案见 ohosApp/INTEROP.md。
 *
 * ## 同步语义注意
 * 业务类函数 (bookshelfList/searchBook/loadChapter/importBookSource) 内部用 runBlocking
 * 把 suspend DAO 调用转同步, 在 napi 主线程调用可能阻塞 UI。
 * ArkTS 侧应通过 TaskPool / Worker 调用这些函数, 避免阻塞主线程。
 */

#include "liblegado_shared_api.h"
#include "napi/native_api.h"
#include <dlfcn.h>
#include <hilog/log.h>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "LegadoNapi"

// Kotlin/Native 导出符号类型定义
typedef const char* (*legado_str_str_fn)(const char*);
typedef const char* (*legado_double_str_fn)(double);
typedef const char* (*legado_void_str_fn)(void);
typedef const char* (*legado_str_int_str_fn)(const char*, int);
typedef int (*legado_str_int_fn)(const char*);
typedef int (*legado_int_fn)(void);
typedef void (*legado_void_fn)(void);
typedef void (*legado_cstr_void_fn)(const char*);

typedef void (*legado_int_int_void_fn)(int, int);
typedef void (*legado_cstr_cstr_void_fn)(const char*, const char*);
typedef void (*legado_int64_cstr_void_fn)(int64_t, const char*);

typedef void (*legado_int64_cstr_cstr_void_fn)(int64_t, const char *, const char *);

// 二进制混合协议 (KP9+: Http/Image 大字节面裸传, napi ArrayBuffer, 同 WebView 混合协议思路):
// - 注册函数注入的 dispatch 入口: 控制面 JSON + 数据面裸字节指针 + 长度
//   (Kotlin usePinned 零拷贝直传, C++ malloc 深拷贝后经 tsfn 异步投递)
// - ArkTS → Kotlin 回调: requestId + 控制面 JSON + 数据面裸字节指针 + 长度
//   (C++ napi_get_arraybuffer_info 零拷贝取 ArrayBuffer 数据指针)
typedef void (*legado_bin_dispatch_fn)(const char *, const void *, size_t);

typedef void (*legado_register_bin_dispatch_fn)(legado_bin_dispatch_fn);

typedef void (*legado_int64_cstr_bin_void_fn)(int64_t, const char *, const void *, size_t);

// dlsym 加载的函数指针 - 工具类 (KP4)
static legado_str_str_fn g_chinese_t2s = nullptr;
static legado_str_str_fn g_chinese_s2t = nullptr;
static legado_str_str_fn g_md5_encode = nullptr;
static legado_double_str_fn g_format_percent_us = nullptr;
static legado_int_fn g_providers_registered = nullptr;
static legado_void_fn g_register_providers = nullptr;

// dlsym 加载的函数指针 - 业务类 (KP5 新增)
static legado_void_str_fn g_bookshelf_list = nullptr;
static legado_str_str_fn g_search_book = nullptr;
static legado_str_int_str_fn g_load_chapter = nullptr;
static legado_str_str_fn g_chapter_list = nullptr;
static legado_str_int_fn g_import_booksource = nullptr;

// dlsym 加载的函数指针 - 漫画 + 发现页 (KP5+ 新增)
static legado_str_int_str_fn g_load_manga_chapter = nullptr;     // (bookUrl, idx) -> JSON {"images":[...]}
static legado_void_str_fn g_explore_list = nullptr;              // () -> JSON 数组
static legado_cstr_cstr_void_fn g_open_explore = nullptr;        // (sourceUrl, exploreUrl) -> void (stub no-op)
static legado_cstr_void_fn g_edit_explore_source = nullptr;      // (sourceUrl) -> void (stub no-op)
static legado_cstr_void_fn g_top_explore_source = nullptr;       // (sourceUrl) -> void
static legado_cstr_void_fn g_delete_explore_source = nullptr;    // (sourceUrl) -> void

// dlsym 加载的函数指针 - FileDir/CacheDir 路径注入 (KP7+ 新增, ArkTS → Kotlin 同步推送)
static legado_cstr_void_fn g_register_file_dir = nullptr;
static legado_cstr_void_fn g_register_cache_dir = nullptr;

// dlsym 加载的函数指针 - 屏幕尺寸注入 (ArkTS → Kotlin 同步推送, 同 FileDir 模式)
static legado_int_int_void_fn g_register_screen_size = nullptr;

// dlsym 加载的函数指针 - legado:// deep link 投递 (ArkTS → Kotlin 同步推送, 返回是否已识别)
static legado_str_int_fn g_handle_deep_link = nullptr;

// dlsym 加载的函数指针 - Toast/Notification tsfn 注入 (KP7+ 新增, C++ → Kotlin @CName 注入 dispatch 函数指针)
// Kotlin 侧 legado_register_toast_fn / legado_register_notification_fn 接收一个 C 函数指针,
// 包成 (String) -> Unit lambda 存入 OhosNativeBridge.toastTsfn / notificationTsfn;
// KMP 业务调用 showToast/showNotification 时, lambda 调用此 dispatch 函数指针回到 C++,
// 由 C++ napi_call_threadsafe_function 跨线程 dispatch 到 ArkTS 主线程执行回调。
typedef void (*legado_register_dispatch_fn)(legado_cstr_void_fn);
static legado_register_dispatch_fn g_register_toast_fn = nullptr;
static legado_register_dispatch_fn g_register_notification_fn = nullptr;

// WebView 混合协议注册函数类型: C++ 注入双字符串 dispatch 入口 (控制面 JSON + 数据面裸 html)
typedef void (*legado_register_webview_dispatch_fn)(legado_cstr_cstr_void_fn);

// dlsym 加载的函数指针 - Image/Media tsfn 注入 (KP8+ 新增, 同 Toast/Notification 模式)
// Image 桥 KP9+ 升级为二进制混合协议 (JSON + 裸字节), 用 legado_register_bin_dispatch_fn
static legado_register_bin_dispatch_fn g_register_image_fn = nullptr;
static legado_register_dispatch_fn g_register_media_fn = nullptr;

// dlsym 加载的函数指针 - Image/Media ArkTS → Kotlin 回调 (KP8+ 新增, 同 FileDir @CName 模式)
// ArkTS 侧 imageCallback(requestId, resultJson, body?) / mediaEvent(eventJson) 通过 napi 调 C++,
// C++ 通过 dlsym 调 Kotlin @CName 函数, 把结果/事件推送给 Kotlin。
// Image 桥 KP9+ 升级为二进制混合协议 (JSON + 裸字节), 用 legado_int64_cstr_bin_void_fn
static legado_int64_cstr_bin_void_fn g_image_callback = nullptr;
static legado_cstr_void_fn g_media_event = nullptr;

// dlsym 加载的函数指针 - TTS tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Media 模式)
static legado_register_dispatch_fn g_register_tts_fn = nullptr;
static legado_cstr_void_fn g_tts_event = nullptr;

// dlsym 加载的函数指针 - Crypto tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式, encrypt/decrypt/sign/verify)
static legado_register_dispatch_fn g_register_crypto_fn = nullptr;
static legado_int64_cstr_void_fn g_crypto_callback = nullptr;

// dlsym 加载的函数指针 - Http tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto 模式, execute/cancel)
// Http 桥 KP9+ 升级为二进制混合协议 (JSON + 裸字节), 用 legado_register_bin_dispatch_fn /
// legado_int64_cstr_bin_void_fn
static legado_register_bin_dispatch_fn g_register_http_fn = nullptr;
static legado_int64_cstr_bin_void_fn g_http_callback = nullptr;

// dlsym 加载的函数指针 - OpenUrl tsfn 注入 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch)
static legado_register_dispatch_fn g_register_open_url_fn = nullptr;

// dlsym 加载的函数指针 - FilePicker tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式, pickDocuments/pickDocumentContent)
static legado_register_dispatch_fn g_register_file_picker_fn = nullptr;
static legado_int64_cstr_void_fn g_file_picker_callback = nullptr;

// dlsym 加载的函数指针 - Pasteboard tsfn 注入 + ArkTS → Kotlin 回调 (同 FilePicker 模式, read/write)
static legado_register_dispatch_fn g_register_pasteboard_fn = nullptr;
static legado_int64_cstr_void_fn g_pasteboard_callback = nullptr;

// dlsym 加载的函数指针 - TextCodec tsfn 注入 + ArkTS → Kotlin 回调 (同 Pasteboard 模式, decode/encode)
static legado_register_dispatch_fn g_register_text_codec_fn = nullptr;
static legado_int64_cstr_void_fn g_text_codec_callback = nullptr;

// dlsym 加载的函数指针 - Battery tsfn 注入 + ArkTS → Kotlin 回调 (同 Crypto 模式, getLevel)
static legado_register_dispatch_fn g_register_battery_fn = nullptr;
static legado_int64_cstr_void_fn g_battery_callback = nullptr;

// dlsym 加载的函数指针 - Share tsfn 注入 (同 Toast 模式, fire-and-forget, 无 ArkTS → Kotlin 回调)
static legado_register_dispatch_fn g_register_share_fn = nullptr;

// dlsym 加载的函数指针 - Keyboard tsfn 注入 (同 Window 模式, fire-and-forget, 无 ArkTS → Kotlin 回调)
static legado_register_dispatch_fn g_register_keyboard_fn = nullptr;

// dlsym 加载的函数指针 - Permission tsfn 注入 + ArkTS → Kotlin 回调 (同 Pasteboard 模式, check/request)
static legado_register_dispatch_fn g_register_permission_fn = nullptr;
static legado_int64_cstr_void_fn g_permission_callback = nullptr;

// dlsym 加载的函数指针 - 外部启动请求投递 (ArkTS → Kotlin 同步推送, 同 legado_handle_deep_link 模式)
static legado_str_int_fn g_handle_launch_request = nullptr;

// dlsym 加载的函数指针 - 图片下载管线导出 (ArkTS 保存到相册, 带书源 header, 返回 base64)
static legado_str_str_fn g_download_image_bytes = nullptr;

// dlsym 加载的函数指针 - TextAction tsfn 注入 + ArkTS → Kotlin 菜单动作回调 (阅读页文本操作浮动菜单, KP8+ 新增)
static legado_register_dispatch_fn g_register_text_action_fn = nullptr;
static legado_int64_cstr_void_fn g_text_action_callback = nullptr;

// dlsym 加载的函数指针 - WebView tsfn 注入 + ArkTS → Kotlin 回调 (混合协议: 控制面 JSON + 数据面裸字符串, 后台 WebView 抓取)
static legado_register_webview_dispatch_fn g_register_webview_fn = nullptr;
static legado_int64_cstr_cstr_void_fn g_webview_callback = nullptr;

// dlsym 加载的函数指针 - Window tsfn 注入 (KMP → ArkTS @ohos.window 窗口策略命令, 同 Toast 模式, fire-and-forget)
static legado_register_dispatch_fn g_register_window_fn = nullptr;

// Toast/Notification/Image/Media/TTS/Crypto/Http/OpenUrl/FilePicker/Pasteboard threadsafe_function 引用 (C++ 侧持有, ArkTS registerXxxCallback 时创建)
static napi_threadsafe_function g_toast_tsfn = nullptr;
static napi_threadsafe_function g_notification_tsfn = nullptr;
static napi_threadsafe_function g_image_tsfn = nullptr;
static napi_threadsafe_function g_media_tsfn = nullptr;
static napi_threadsafe_function g_tts_tsfn = nullptr;
static napi_threadsafe_function g_crypto_tsfn = nullptr;
static napi_threadsafe_function g_http_tsfn = nullptr;
static napi_threadsafe_function g_open_url_tsfn = nullptr;
static napi_threadsafe_function g_text_action_tsfn = nullptr;
static napi_threadsafe_function g_file_picker_tsfn = nullptr;
static napi_threadsafe_function g_pasteboard_tsfn = nullptr;
static napi_threadsafe_function g_text_codec_tsfn = nullptr;
static napi_threadsafe_function g_webview_tsfn = nullptr;
static napi_threadsafe_function g_window_tsfn = nullptr;
static napi_threadsafe_function g_battery_tsfn = nullptr;
static napi_threadsafe_function g_share_tsfn = nullptr;
static napi_threadsafe_function g_keyboard_tsfn = nullptr;
static napi_threadsafe_function g_permission_tsfn = nullptr;

// liblegado_shared.so 句柄
static void* g_legado_so = nullptr;

// 加载 liblegado_shared.so 并解析符号
static bool load_legado_shared() {
    if (g_legado_so != nullptr) return true;

    // 尝试加载 liblegado_shared.so (HAP 内置)
    g_legado_so = dlopen("liblegado_shared.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_legado_so == nullptr) {
        OH_LOG_ERROR(LOG_APP, "dlopen liblegado_shared.so failed: %{public}s", dlerror());
        return false;
    }

    // 解析 @CName 导出符号 - 工具类 (KP4)
    g_chinese_t2s = (legado_str_str_fn)dlsym(g_legado_so, "legado_chinese_t2s");
    g_chinese_s2t = (legado_str_str_fn)dlsym(g_legado_so, "legado_chinese_s2t");
    g_md5_encode = (legado_str_str_fn)dlsym(g_legado_so, "legado_md5_encode");
    g_format_percent_us = (legado_double_str_fn)dlsym(g_legado_so, "legado_format_percent_us");
    g_providers_registered = (legado_int_fn)dlsym(g_legado_so, "legado_providers_registered");
    g_register_providers = (legado_void_fn)dlsym(g_legado_so, "legado_register_providers");

    // 解析 @CName 导出符号 - 业务类 (KP5 新增)
    g_bookshelf_list = (legado_void_str_fn)dlsym(g_legado_so, "legado_bookshelf_list");
    g_search_book = (legado_str_str_fn)dlsym(g_legado_so, "legado_search_book");
    g_load_chapter = (legado_str_int_str_fn)dlsym(g_legado_so, "legado_load_chapter");
    g_chapter_list = (legado_str_str_fn)dlsym(g_legado_so, "legado_chapter_list");
    g_import_booksource = (legado_str_int_fn)dlsym(g_legado_so, "legado_import_booksource");

    // 解析 @CName 导出符号 - 漫画 + 发现页 (KP5+ 新增)
    g_load_manga_chapter = (legado_str_int_str_fn)dlsym(g_legado_so, "legado_load_manga_chapter");
    g_explore_list = (legado_void_str_fn)dlsym(g_legado_so, "legado_explore_list");
    g_open_explore = (legado_cstr_cstr_void_fn)dlsym(g_legado_so, "legado_open_explore");
    g_edit_explore_source = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_edit_explore_source");
    g_top_explore_source = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_top_explore_source");
    g_delete_explore_source = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_delete_explore_source");

    // 解析 @CName 导出符号 - FileDir/CacheDir 路径注入 (KP7+ 新增)
    g_register_file_dir = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_register_file_dir");
    g_register_cache_dir = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_register_cache_dir");

    // 解析 @CName 导出符号 - 屏幕尺寸注入
    g_register_screen_size = (legado_int_int_void_fn) dlsym(g_legado_so, "legado_register_screen_size");

    // 解析 @CName 导出符号 - legado:// deep link 投递
    g_handle_deep_link = (legado_str_int_fn)dlsym(g_legado_so, "legado_handle_deep_link");

    // 解析 @CName 导出符号 - Toast/Notification tsfn 注入 (KP7+ 新增)
    g_register_toast_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_toast_fn");
    g_register_notification_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_notification_fn");

    // 解析 @CName 导出符号 - Window tsfn 注入 (KMP → ArkTS 窗口策略, 同 Toast 模式)
    g_register_window_fn = (legado_register_dispatch_fn) dlsym(g_legado_so, "legado_register_window_fn");

    // 解析 @CName 导出符号 - Image/Media tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增)
    g_register_image_fn = (legado_register_bin_dispatch_fn) dlsym(g_legado_so, "legado_register_image_fn");
    g_register_media_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_media_fn");
    g_image_callback = (legado_int64_cstr_bin_void_fn) dlsym(g_legado_so, "legado_image_callback");
    g_media_event = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_media_event");

    // 解析 @CName 导出符号 - TTS tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Media 模式)
    g_register_tts_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_tts_fn");
    g_tts_event = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_tts_event");

    // 解析 @CName 导出符号 - Crypto tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式)
    g_register_crypto_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_crypto_fn");
    g_crypto_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_crypto_callback");

    // 解析 @CName 导出符号 - Http tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto 模式)
    g_register_http_fn = (legado_register_bin_dispatch_fn) dlsym(g_legado_so, "legado_register_http_fn");
    g_http_callback = (legado_int64_cstr_bin_void_fn) dlsym(g_legado_so, "legado_http_callback");

    // 解析 @CName 导出符号 - OpenUrl tsfn 注入 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch)
    g_register_open_url_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_open_url_fn");

    // 解析 @CName 导出符号 - FilePicker tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式)
    g_register_file_picker_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_file_picker_fn");
    g_file_picker_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_file_picker_callback");

    // 解析 @CName 导出符号 - Pasteboard tsfn 注入 + ArkTS → Kotlin 回调 (同 FilePicker 模式)
    g_register_pasteboard_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_pasteboard_fn");
    g_pasteboard_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_pasteboard_callback");

    // 解析 @CName 导出符号 - TextCodec tsfn 注入 + ArkTS → Kotlin 回调 (同 Pasteboard 模式)
    g_register_text_codec_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_text_codec_fn");
    g_text_codec_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_text_codec_callback");

    // 解析 @CName 导出符号 - Battery tsfn 注入 + ArkTS → Kotlin 回调 (同 Crypto 模式)
    g_register_battery_fn = (legado_register_dispatch_fn) dlsym(g_legado_so, "legado_register_battery_fn");
    g_battery_callback = (legado_int64_cstr_void_fn) dlsym(g_legado_so, "legado_battery_callback");

    // 解析 @CName 导出符号 - Share tsfn 注入 (同 Toast 模式, fire-and-forget)
    g_register_share_fn = (legado_register_dispatch_fn) dlsym(g_legado_so, "legado_register_share_fn");

    // 解析 @CName 导出符号 - Keyboard tsfn 注入 (同 Window 模式, fire-and-forget)
    g_register_keyboard_fn = (legado_register_dispatch_fn) dlsym(g_legado_so, "legado_register_keyboard_fn");

    // 解析 @CName 导出符号 - Permission tsfn 注入 + ArkTS → Kotlin 回调 (同 Pasteboard 模式)
    g_register_permission_fn = (legado_register_dispatch_fn) dlsym(g_legado_so, "legado_register_permission_fn");
    g_permission_callback = (legado_int64_cstr_void_fn) dlsym(g_legado_so, "legado_permission_callback");

    // 解析 @CName 导出符号 - 外部启动请求投递 (ArkTS → Kotlin 同步推送, 同 deep link 模式)
    g_handle_launch_request = (legado_str_int_fn) dlsym(g_legado_so, "legado_handle_launch_request");

    // 解析 @CName 导出符号 - 图片下载管线 (ArkTS 保存到相册, 带书源 header, 返回 base64)
    g_download_image_bytes = (legado_str_str_fn) dlsym(g_legado_so, "legado_download_image_bytes");

    // 解析 @CName 导出符号 - TextAction tsfn 注入 + ArkTS → Kotlin 菜单动作回调
    g_register_text_action_fn = (legado_register_dispatch_fn) dlsym(g_legado_so, "legado_register_text_action_fn");
    g_text_action_callback = (legado_int64_cstr_void_fn) dlsym(g_legado_so, "legado_text_action_callback");

    // 解析 @CName 导出符号 - WebView tsfn 注入 + ArkTS → Kotlin 回调 (混合协议: 控制面 JSON + 数据面裸字符串)
    g_register_webview_fn = (legado_register_webview_dispatch_fn) dlsym(g_legado_so, "legado_register_webview_fn");
    g_webview_callback = (legado_int64_cstr_cstr_void_fn) dlsym(g_legado_so, "legado_webview_callback");

    OH_LOG_INFO(LOG_APP, "liblegado_shared.so loaded, symbols resolved (KP5: + bookshelfList/searchBook/loadChapter/chapterList/importBookSource; KP5+: + loadMangaChapter/exploreList/openExplore/editExploreSource/topExploreSource/deleteExploreSource; KP7+: + registerFileDir/registerCacheDir/registerToastFn/registerNotificationFn; KP8+: + registerImageFn/registerMediaFn/imageCallback/mediaEvent/registerTtsFn/ttsEvent/registerCryptoFn/cryptoCallback/registerHttpFn/httpCallback/registerOpenUrlFn/registerFilePickerFn/filePickerCallback/registerPasteboardFn/pasteboardCallback/registerTextCodecFn/textCodecCallback)");
    return true;
}

// CPF Compose 控制器：把 K/N 导出的 MainArkUIViewController(env) 暴露给 ArkTS。
static napi_value CreateMainArkUIViewController(napi_env env, napi_callback_info info) {
    return reinterpret_cast<napi_value>(MainArkUIViewController(env));
}

// ============ 工具类 napi 包装 (KP4 已存在) ============

// napi 包装: chineseT2S(text: string): string
static napi_value ChineseT2S(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    const char* result = "[mock t2s] (liblegado_shared.so not loaded)";
    if (load_legado_shared() && g_chinese_t2s != nullptr) {
        result = g_chinese_t2s(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: chineseS2T(text: string): string
static napi_value ChineseS2T(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    const char* result = "[mock s2t] (liblegado_shared.so not loaded)";
    if (load_legado_shared() && g_chinese_s2t != nullptr) {
        result = g_chinese_s2t(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: md5Encode(text: string): string
static napi_value Md5Encode(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    const char* result = "[mock md5] (liblegado_shared.so not loaded)";
    if (load_legado_shared() && g_md5_encode != nullptr) {
        result = g_md5_encode(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: formatPercentUs(value: number): string
static napi_value FormatPercentUs(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    double value = 0.0;
    napi_get_value_double(env, args[0], &value);

    const char* result = "[mock percent] (liblegado_shared.so not loaded)";
    if (load_legado_shared() && g_format_percent_us != nullptr) {
        result = g_format_percent_us(value);
    }

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: isProvidersRegistered(): boolean
static napi_value IsProvidersRegistered(napi_env env, napi_callback_info info) {
    int registered = 0;
    if (load_legado_shared() && g_providers_registered != nullptr) {
        registered = g_providers_registered();
    }
    napi_value ret;
    napi_get_boolean(env, registered != 0, &ret);
    return ret;
}

// napi 包装: registerOhosProviders(): void
static napi_value RegisterOhosProviders(napi_env env, napi_callback_info info) {
    if (load_legado_shared() && g_register_providers != nullptr) {
        g_register_providers();
    }
    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ 业务类 napi 包装 (KP5 新增) ============

// napi 包装: bookshelfList(): string (返回 JSON 数组)
// 注: 内部 runBlocking 转 suspend, 调用方应在 TaskPool/Worker 中调用避免阻塞 UI
static napi_value BookshelfList(napi_env env, napi_callback_info info) {
    const char* result = "[]";  // 兜底空数组 (liblegado_shared.so 未加载或异常时)
    if (load_legado_shared() && g_bookshelf_list != nullptr) {
        result = g_bookshelf_list();
    }

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: searchBook(query: string): string (返回 JSON 数组, 书架内搜索)
static napi_value SearchBook(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    const char* result = "[]";  // 兜底空数组
    if (load_legado_shared() && g_search_book != nullptr) {
        result = g_search_book(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: loadChapter(bookUrl: string, chapterIndex: number): string (返回章节正文)
static napi_value LoadChapter(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: bookUrl (string)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    // args[1]: chapterIndex (number)
    int32_t chapter_index = 0;
    napi_get_value_int32(env, args[1], &chapter_index);

    const char* result = "";  // 兜底空字符串 (缓存未命中或异常时)
    if (load_legado_shared() && g_load_chapter != nullptr) {
        result = g_load_chapter(buf, chapter_index);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: chapterList(bookUrl: string): string (返回章节目录 JSON 数组)
static napi_value ChapterList(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    const char* result = "[]";  // 兜底空数组 (异常时)
    if (load_legado_shared() && g_chapter_list != nullptr) {
        result = g_chapter_list(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: importBookSource(json: string): number (返回导入数量)
static napi_value ImportBookSource(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    int imported = 0;  // 兜底 0 (异常时)
    if (load_legado_shared() && g_import_booksource != nullptr) {
        imported = g_import_booksource(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_int32(env, imported, &ret);
    return ret;
}

// ============ 漫画 + 发现页 napi 包装 (KP5+ 新增) ============

// napi 包装: loadMangaChapter(bookUrl: string, chapterIndex: number): string
// 返回漫画章节图片 URL JSON: {"images":["url1","url2",...]}
// 注: MangaImageExtractor ohosMain 未注入, Kotlin 侧暂返回空 images; 桥接就绪后填充
static napi_value LoadMangaChapter(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: bookUrl (string)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    // args[1]: chapterIndex (number)
    int32_t chapter_index = 0;
    napi_get_value_int32(env, args[1], &chapter_index);

    const char* result = "{\"images\":[]}";  // 兜底空 images (MangaImageExtractor 未注入或异常时)
    if (load_legado_shared() && g_load_manga_chapter != nullptr) {
        result = g_load_manga_chapter(buf, chapter_index);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: exploreList(): string (返回发现源 JSON 数组, 仅 enabledExplore=true)
static napi_value ExploreList(napi_env env, napi_callback_info info) {
    const char* result = "[]";  // 兜底空数组 (liblegado_shared.so 未加载或异常时)
    if (load_legado_shared() && g_explore_list != nullptr) {
        result = g_explore_list();
    }

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi 包装: openExplore(sourceUrl: string, exploreUrl: string): void (stub no-op, 跳转 ExploreShow)
// 注: 页面跳转属 ArkTS 路由范畴, Kotlin 侧无路由 API; 实际导航应由 ArkTS 端直接 router.pushUrl
static napi_value OpenExplore(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: sourceUrl (string)
    size_t str_len1 = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len1);
    char* buf1 = new char[str_len1 + 1];
    napi_get_value_string_utf8(env, args[0], buf1, str_len1 + 1, &str_len1);

    // args[1]: exploreUrl (string)
    size_t str_len2 = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len2);
    char* buf2 = new char[str_len2 + 1];
    napi_get_value_string_utf8(env, args[1], buf2, str_len2 + 1, &str_len2);

    if (load_legado_shared() && g_open_explore != nullptr) {
        g_open_explore(buf1, buf2);
    }
    delete[] buf1;
    delete[] buf2;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: editExploreSource(sourceUrl: string): void (stub no-op, 跳转 BookSourceEdit)
static napi_value EditExploreSource(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_edit_explore_source != nullptr) {
        g_edit_explore_source(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: topExploreSource(sourceUrl: string): void (置顶书源, 调 ExploreViewModelShared.topSource)
static napi_value TopExploreSource(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_top_explore_source != nullptr) {
        g_top_explore_source(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: deleteExploreSource(sourceUrl: string): void (删除书源, 调 ExploreViewModelShared.deleteSource)
static napi_value DeleteExploreSource(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_delete_explore_source != nullptr) {
        g_delete_explore_source(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ FileDir/CacheDir 路径注入 napi 包装 (KP7+ 新增) ============

// napi 包装: registerFileDir(path: string): void  注入鸿蒙沙盒 filesDir 路径 (ArkTS → Kotlin)
// 注: 调用时机须在 registerOhosProviders 之前 (见 EntryAbility.ets), 使 DatabaseDriver 读到真实沙盒路径
static napi_value RegisterFileDir(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_register_file_dir != nullptr) {
        g_register_file_dir(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: registerCacheDir(path: string): void  注入鸿蒙沙盒 cacheDir 路径
static napi_value RegisterCacheDir(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_register_cache_dir != nullptr) {
        g_register_cache_dir(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ 屏幕尺寸注入 napi 包装 (ArkTS → Kotlin 同步推送, 同 FileDir 模式) ============

// napi 包装: registerScreenSize(widthPx: number, heightPx: number): void  注入显示物理像素尺寸
// 调用方: EntryAbility.onWindowStageCreate (loadContent 之前, 任何 shared 对话框尺寸计算之前),
// 由 Kotlin LegadoNativeExports.registerScreenSize → OhosNativeBridge.registerScreenSizeFn 存储;
// sharedUiMain AppDialogSizes 兜底取 ScreenInfoProviders.get() (未注册时 error 导致对话框崩溃)。
static napi_value RegisterScreenSize(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    int32_t width = 0;
    int32_t height = 0;
    napi_get_value_int32(env, args[0], &width);
    napi_get_value_int32(env, args[1], &height);

    if (load_legado_shared() && g_register_screen_size != nullptr) {
        g_register_screen_size(width, height);
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ legado:// deep link 投递 napi 包装 ============

// napi 包装: handleDeepLink(uri: string): boolean  投递 legado://|yuedu:// 一键导入链接
// 调用方: EntryAbility.onCreate / onNewWant (want.uri), 由 Kotlin LegadoDeepLinkHandler 解析后
// 写入 pending (StateFlow), Compose 侧 DeepLinkImportHost 消费弹勾选对话框。
// 同步返回 (不走 tsfn): Kotlin 侧被动接收无回调, 仅需告知 ArkTS 是否已识别。
static napi_value HandleDeepLink(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    int handled = 0;  // 兜底 0 (so 未加载/符号缺失/非 legado scheme)
    if (load_legado_shared() && g_handle_deep_link != nullptr) {
        handled = g_handle_deep_link(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_boolean(env, handled != 0, &ret);
    return ret;
}

// ============ Toast/Notification tsfn 接线 (KP7+ 新增, KMP → ArkTS 跨线程 dispatch) ============
// 设计: KMP 业务线程 (OhosNativeBridge.showToast/showNotification) 不能直接调 ArkTS API
// (promptAction.showToast / notificationManager.publish 仅 ArkTS 可用), 故用 napi_threadsafe_function
// 把调用 dispatch 到 ArkTS 主线程。
//
// 调用链 (以 toast 为例):
// KMP OhosNativeBridge.showToast(msg, dur)
//   → toastTsfn?.invoke(json)              (toastTsfn 为 Kotlin (String)->Unit lambda)
//   → lambda 调 C++ ohos_toast_dispatch(json) (函数指针由 legado_register_toast_fn 注入)
//   → napi_call_threadsafe_function(g_toast_tsfn, json_dup, nonblocking)
//   → [ArkTS 主线程] ToastCallJs(env, js_cb, ctx, json_dup)
//   → napi_create_string_utf8 + napi_call_function(js_cb, jsonArg) (调 ArkTS 注册的 callback)
//   → ArkTS callback 解析 JSON 后调 promptAction.showToast

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.toastTsfn) 调用。
// 接收 JSON 字符串, 拷贝一份后通过 napi_call_threadsafe_function 异步 dispatch 到 ArkTS。
// 注: 原 json 字符串生命周期由 Kotlin lambda 控制 (调用结束即回收), 故需拷贝;
//     拷贝在 ToastCallJs 回调中释放 (调用成功时) 或在本函数中释放 (调用失败时)。
extern "C" void ohos_toast_dispatch(const char* json) {
    if (g_toast_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_toast_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        // 调用失败 (队列满 / tsfn 关闭中): 自行释放拷贝, 回调不会执行
        free(json_dup);
    }
}

extern "C" void ohos_notification_dispatch(const char* json) {
    if (g_notification_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_notification_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调。
// 调用完成后释放 ohos_toast_dispatch 中 malloc 的 JSON 拷贝。
static void ToastCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

static void NotificationCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.windowTsfn) 调用。
// 同 ohos_toast_dispatch: 拷贝 JSON 后 napi_call_threadsafe_function 异步 dispatch 到 ArkTS。
extern "C" void ohos_window_dispatch(const char *json) {
    if (g_window_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char *json_dup = (char *) malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_window_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        // 调用失败 (队列满 / tsfn 关闭中): 自行释放拷贝, 回调不会执行
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调。
static void WindowCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    char *json = static_cast<char *>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerWindowCallback(callback: (json: string) => void): void
// ArkTS 注册窗口策略回调; C++ 创建 napi_threadsafe_function 包装 ArkTS callback, 存入 g_window_tsfn,
// 并通过 @CName legado_register_window_fn 把 ohos_window_dispatch 函数指针注入 Kotlin
// (Kotlin 包成 (String) -> Unit lambda 存入 OhosNativeBridge.windowTsfn),
// 使 KMP sendWindowCommand (全屏/常亮/方向/系统栏) 能跨线程 dispatch 到 ArkTS 执行 @ohos.window API。
static napi_value RegisterWindowCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerWindowCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    // 重复注册场景: 释放旧 tsfn
    if (g_window_tsfn != nullptr) {
        napi_release_threadsafe_function(g_window_tsfn, napi_tsfn_abort);
        g_window_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoWindowTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,WindowCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerWindowCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_window_tsfn = tsfn;

    // 把 ohos_window_dispatch 函数指针注入 Kotlin (Kotlin 包成 lambda 存入 OhosNativeBridge.windowTsfn)
    if (load_legado_shared() && g_register_window_fn != nullptr) {
        g_register_window_fn(&ohos_window_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerWindowCallback: legado_register_window_fn not resolved, tsfn 仅 C++ 侧持有 (KMP 窗口策略将降级 println)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: registerToastCallback(callback: (json: string) => void): void
// ArkTS 注册 toast 回调; C++ 创建 napi_threadsafe_function 包装 ArkTS callback, 存入 g_toast_tsfn,
// 并通过 @CName legado_register_toast_fn 把 ohos_toast_dispatch 函数指针注入 Kotlin
// (Kotlin 包成 (String) -> Unit lambda 存入 OhosNativeBridge.toastTsfn), 使 KMP showToast 能跨线程 dispatch。
static napi_value RegisterToastCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerToastCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    // 重复注册场景: 释放旧 tsfn
    if (g_toast_tsfn != nullptr) {
        napi_release_threadsafe_function(g_toast_tsfn, napi_tsfn_abort);
        g_toast_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoToastTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,ToastCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerToastCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_toast_tsfn = tsfn;

    // 把 ohos_toast_dispatch 函数指针注入 Kotlin (Kotlin 包成 lambda 存入 OhosNativeBridge.toastTsfn)
    if (load_legado_shared() && g_register_toast_fn != nullptr) {
        g_register_toast_fn(&ohos_toast_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerToastCallback: legado_register_toast_fn not resolved, tsfn 仅 C++ 侧持有 (KMP showToast 将降级 println)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: registerNotificationCallback(callback: (json: string) => void): void
// 同 RegisterToastCallback, 注入 ohos_notification_dispatch 到 Kotlin OhosNativeBridge.notificationTsfn。
static napi_value RegisterNotificationCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerNotificationCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_notification_tsfn != nullptr) {
        napi_release_threadsafe_function(g_notification_tsfn, napi_tsfn_abort);
        g_notification_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoNotificationTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,NotificationCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerNotificationCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_notification_tsfn = tsfn;

    if (load_legado_shared() && g_register_notification_fn != nullptr) {
        g_register_notification_fn(&ohos_notification_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerNotificationCallback: legado_register_notification_fn not resolved, tsfn 仅 C++ 侧持有 (KMP showNotification 将降级 println)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ 二进制混合协议 tsfn 传输基建 (KP9+: Http/Image 大字节面裸传, napi ArrayBuffer) ============
// 与 WebView 混合协议 (双裸字符串) 同思路: 控制面 JSON 走字符串, 大字节数据面 (HTTP body /
// 图片字节) 走 napi ArrayBuffer 裸传, 不经 base64 (避免 33% 体积膨胀 + 双端编解码拷贝),
// 二进制保真。
// 生命周期: Kotlin 侧 usePinned 零拷贝直传 → ohos_xxx_dispatch malloc 深拷贝 (tsfn 异步投递,
// 原指针生命周期不可控) → call-js 回调 (ArkTS 主线程) 把 bytes 用 external ArrayBuffer 包装
// (零拷贝, 所有权转移给 JS, GC 时 finalize 释放)。

// tsfn 跨线程传输数据: 控制面 JSON + 可选数据面裸字节
struct DispatchBinaryData {
    char *json;          // malloc 分配, CallJsWithBinary 统一释放
    uint8_t *bytes;      // malloc 分配 (可为 null); 非空且长度>0 时所有权转移给 external ArrayBuffer
    size_t bytes_len;
};

// external ArrayBuffer 被 GC 回收时释放对应 malloc 内存
static void DispatchBinaryFinalize(napi_env /*env*/, void *data, void * /*hint*/) {
    free(data);
}

// 通用 call-js 回调体: 把 (JSON, bytes) 包成 (napi string, napi ArrayBuffer|undefined) 两个参数
// 调 ArkTS 回调; bytes_len>0 时 external ArrayBuffer 零拷贝包装 (无二次拷贝)
static void CallJsWithBinary(napi_env env, napi_value js_cb, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    DispatchBinaryData *d = static_cast<DispatchBinaryData *>(data);
    napi_value args[2];
    napi_create_string_utf8(env, d->json, NAPI_AUTO_LENGTH, &args[0]);
    if (d->bytes != nullptr && d->bytes_len > 0) {
        napi_status st = napi_create_external_arraybuffer(
                env, d->bytes, d->bytes_len, DispatchBinaryFinalize, nullptr, &args[1]);
        if (st != napi_ok) {
            // 包装失败 (极罕见): 释放字节, 降级传 undefined (调用方按无字节面处理)
            free(d->bytes);
            napi_get_undefined(env, &args[1]);
        }
    } else {
        napi_get_undefined(env, &args[1]);
    }
    napi_call_function(env, js_cb, 2, args, nullptr);
    free(d->json);
    free(d);
}

// 通用 dispatch 入口: 深拷贝 JSON + 可选字节后投递给 tsfn (napi_call_threadsafe_function 异步,
// 调用方指针生命周期不可控, 必须拷贝; 拷贝由 call-js 回调 / external ArrayBuffer finalize 释放)
static void DispatchBinaryCommon(napi_threadsafe_function tsfn, const char *json,
        const void *bytes, size_t bytes_len) {
    if (tsfn == nullptr || json == nullptr) return;
    DispatchBinaryData *d = (DispatchBinaryData *) malloc(sizeof(DispatchBinaryData));
    if (d == nullptr) return;
    d->bytes = nullptr;
    d->bytes_len = bytes_len;
    size_t json_len = strlen(json) + 1;
    d->json = (char *) malloc(json_len);
    if (d->json == nullptr) {
        free(d);
        return;
    }
    memcpy(d->json, json, json_len);
    if (bytes != nullptr && bytes_len > 0) {
        d->bytes = (uint8_t *) malloc(bytes_len);
        if (d->bytes == nullptr) {
            free(d->json);
            free(d);
            return;
        }
        memcpy(d->bytes, bytes, bytes_len);
    }
    napi_status status = napi_call_threadsafe_function(tsfn, d, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(d->json);
        free(d->bytes);
        free(d);
    }
}

// ============ Image/Media/TTS tsfn 接线 (KP8+ 新增, 同 Toast/Notification 模式) ============
// 设计与 Toast/Notification 完全一致:
// - KMP 通过 OhosNativeBridge.invokeImageSync / sendMediaCommand / speakTts 发请求
// - 请求经 C++ dispatch 函数 → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS 处理后通过 imageCallback/mediaEvent/ttsEvent (napi → @CName) 回送结果/事件给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.imageTsfn) 调用
// 混合协议双参: 控制面 JSON + 数据面裸字节 (decode 的图片字节, 可为 null)
extern "C" void ohos_image_dispatch(const char *json, const void *bytes, size_t bytes_len) {
    DispatchBinaryCommon(g_image_tsfn, json, bytes, bytes_len);
}

extern "C" void ohos_media_dispatch(const char* json) {
    if (g_media_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_media_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

extern "C" void ohos_tts_dispatch(const char* json) {
    if (g_tts_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_tts_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON + 裸字节) 包成
// (napi string, napi ArrayBuffer|undefined) 后调用 ArkTS 回调
static void ImageCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    CallJsWithBinary(env, js_cb, data);
}

static void MediaCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

static void TtsCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerImageCallback(callback: (json: string, bytes?: ArrayBuffer) => void): void
// ArkTS 注册 image 回调; C++ 创建 tsfn, 通过 legado_register_image_fn 注入 ohos_image_dispatch 到 Kotlin
// (混合协议: 第二参数 bytes 为 decode 图片字节的 external ArrayBuffer, 无字节面时 undefined)
static napi_value RegisterImageCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerImageCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_image_tsfn != nullptr) {
        napi_release_threadsafe_function(g_image_tsfn, napi_tsfn_abort);
        g_image_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoImageTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,ImageCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerImageCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_image_tsfn = tsfn;

    if (load_legado_shared() && g_register_image_fn != nullptr) {
        g_register_image_fn(&ohos_image_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerImageCallback: legado_register_image_fn not resolved (KMP invokeImageSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: registerMediaCallback(callback: (json: string) => void): void
// ArkTS 注册 media 回调; C++ 创建 tsfn, 通过 legado_register_media_fn 注入 ohos_media_dispatch 到 Kotlin
static napi_value RegisterMediaCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerMediaCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_media_tsfn != nullptr) {
        napi_release_threadsafe_function(g_media_tsfn, napi_tsfn_abort);
        g_media_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoMediaTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,MediaCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerMediaCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_media_tsfn = tsfn;

    if (load_legado_shared() && g_register_media_fn != nullptr) {
        g_register_media_fn(&ohos_media_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerMediaCallback: legado_register_media_fn not resolved (KMP sendMediaCommand 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: registerTtsCallback(callback: (json: string) => void): void
// ArkTS 注册 tts 回调; C++ 创建 tsfn, 通过 legado_register_tts_fn 注入 ohos_tts_dispatch 到 Kotlin
static napi_value RegisterTtsCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerTtsCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_tts_tsfn != nullptr) {
        napi_release_threadsafe_function(g_tts_tsfn, napi_tsfn_abort);
        g_tts_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoTtsTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,TtsCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerTtsCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_tts_tsfn = tsfn;

    if (load_legado_shared() && g_register_tts_fn != nullptr) {
        g_register_tts_fn(&ohos_tts_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerTtsCallback: legado_register_tts_fn not resolved (KMP speakTts 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Image/Media/TTS ArkTS → Kotlin 回调 napi 包装 (KP8+ 新增) ============
// ArkTS 侧处理完图片操作/AVPlayer/TTS 事件后, 通过这三个 napi 方法把结果/事件回送给 Kotlin。
// C++ 通过 dlsym 调 Kotlin @CName 函数 (legado_image_callback / legado_media_event / legado_tts_event)。

// napi 包装: imageCallback(requestId: number, result: string, body?: ArrayBuffer): void
// ArkTS → Kotlin 图片操作结果回调 (decode/encode/size/crop/split/stitch 完成后调用, 混合协议)
static napi_value ImageCallback(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string, 控制面: 不含字节的结果 JSON)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    // args[2]: body (ArrayBuffer, 数据面: encode 的 packed 字节; 无字节面时 undefined)
    // napi_get_arraybuffer_info 零拷贝取数据指针, 调用期间 ArrayBuffer 由 JS 引擎持有, 生命周期安全
    const void *bytes = nullptr;
    size_t bytes_len = 0;
    bool is_ab = false;
    if (argc >= 3 && args[2] != nullptr &&
            napi_is_arraybuffer(env, args[2], &is_ab) == napi_ok && is_ab) {
        napi_get_arraybuffer_info(env, args[2], (void **) &bytes, &bytes_len);
    }

    if (load_legado_shared() && g_image_callback != nullptr) {
        g_image_callback(request_id, buf, bytes, bytes_len);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: mediaEvent(event: string): void
// ArkTS → Kotlin media 事件回调 (AVPlayer onReady/onEndOfMedia/onError/onBufferingUpdate 等)
static napi_value MediaEvent(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_media_event != nullptr) {
        g_media_event(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: ttsEvent(event: string): void
// ArkTS → Kotlin TTS 事件回调 (onStart/onComplete/onError/onRange 等)
static napi_value TtsEvent(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_tts_event != nullptr) {
        g_tts_event(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Crypto tsfn 接线 (KP8+ 新增, 同 Image 模式: tsfn 发请求 + @CName 回调返回结果) ============
// 设计与 Image 完全一致:
// - KMP 通过 OhosNativeBridge.invokeCryptoSync 发 encrypt/decrypt/sign/verify 请求
// - 请求经 C++ ohos_crypto_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS CryptoBridgeHandler 调 @ohos.security.cryptoFramework 执行真实运算
// - 完成后通过 cryptoCallback(requestId, resultJson) (napi → @CName) 回送结果给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.cryptoTsfn) 调用
extern "C" void ohos_crypto_dispatch(const char* json) {
    if (g_crypto_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_crypto_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void CryptoCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerCryptoCallback(callback: (json: string) => void): void
// ArkTS 注册 crypto 回调; C++ 创建 tsfn, 通过 legado_register_crypto_fn 注入 ohos_crypto_dispatch 到 Kotlin
static napi_value RegisterCryptoCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerCryptoCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_crypto_tsfn != nullptr) {
        napi_release_threadsafe_function(g_crypto_tsfn, napi_tsfn_abort);
        g_crypto_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoCryptoTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,CryptoCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerCryptoCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_crypto_tsfn = tsfn;

    if (load_legado_shared() && g_register_crypto_fn != nullptr) {
        g_register_crypto_fn(&ohos_crypto_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerCryptoCallback: legado_register_crypto_fn not resolved (KMP invokeCryptoSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: cryptoCallback(requestId: number, result: string): void
// ArkTS → Kotlin crypto 操作结果回调 (encrypt/decrypt/sign/verify 完成后调用)
static napi_value CryptoCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_crypto_callback != nullptr) {
        g_crypto_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Http tsfn 接线 (KP8+ 新增, 二进制混合协议: 控制面 JSON + 数据面裸字节) ============
// 设计与 Image 一致 (KP9+ 升级):
// - KMP 通过 OhosNativeBridge.invokeHttpSync 发 execute/cancel 请求
// - 请求经 C++ ohos_http_dispatch(json, body, bodyLen) → napi_call_threadsafe_function →
//   ArkTS 主线程回调 (body 为请求体字节, napi external ArrayBuffer 零拷贝包装)
// - ArkTS HttpBridgeHandler 调 @ohos.net.http 执行真实请求
// - 完成后通过 httpCallback(requestId, resultJson, bodyArrayBuffer) (napi → @CName) 回送结果给 Kotlin
//   (resultJson 为控制面小字段, body 为响应体裸字节)

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.httpTsfn) 调用
// 混合协议双参: 控制面 JSON + 数据面裸字节 (请求 body, 可为 null)
extern "C" void ohos_http_dispatch(const char *json, const void *bytes, size_t bytes_len) {
    DispatchBinaryCommon(g_http_tsfn, json, bytes, bytes_len);
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON + 裸字节) 包成
// (napi string, napi ArrayBuffer|undefined) 后调用 ArkTS 回调
static void HttpCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    CallJsWithBinary(env, js_cb, data);
}

// napi 包装: registerHttpCallback(callback: (json: string, body?: ArrayBuffer) => void): void
// ArkTS 注册 http 回调; C++ 创建 tsfn, 通过 legado_register_http_fn 注入 ohos_http_dispatch 到 Kotlin
// (混合协议: 第二参数 body 为请求体字节的 external ArrayBuffer, 无 body 时 undefined)
static napi_value RegisterHttpCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerHttpCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_http_tsfn != nullptr) {
        napi_release_threadsafe_function(g_http_tsfn, napi_tsfn_abort);
        g_http_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoHttpTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,HttpCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerHttpCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_http_tsfn = tsfn;

    if (load_legado_shared() && g_register_http_fn != nullptr) {
        g_register_http_fn(&ohos_http_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerHttpCallback: legado_register_http_fn not resolved (KMP invokeHttpSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: httpCallback(requestId: number, result: string, body?: ArrayBuffer): void
// ArkTS → Kotlin HTTP 请求结果回调 (execute 完成后调用, 混合协议)
static napi_value HttpCallback(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string, 控制面: 不含 body 的结果 JSON)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    // args[2]: body (ArrayBuffer, 数据面: 响应 body 裸字节; 无 body 时 undefined)
    // napi_get_arraybuffer_info 零拷贝取数据指针, 调用期间 ArrayBuffer 由 JS 引擎持有, 生命周期安全
    const void *bytes = nullptr;
    size_t bytes_len = 0;
    bool is_ab = false;
    if (argc >= 3 && args[2] != nullptr &&
            napi_is_arraybuffer(env, args[2], &is_ab) == napi_ok && is_ab) {
        napi_get_arraybuffer_info(env, args[2], (void **) &bytes, &bytes_len);
    }

    if (load_legado_shared() && g_http_callback != nullptr) {
        g_http_callback(request_id, buf, bytes, bytes_len);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ WebView tsfn 接线 (混合协议: 控制面 JSON + 数据面裸字符串) ============
// 设计与 Http/Image 一致, 但传输为混合协议, 避免大段 HTML 的 JSON 转义膨胀 + 双端编解码拷贝:
// - KMP 通过 OhosNativeBridge.invokeWebViewSync(jsonControl, htmlRaw) 发后台 WebView 抓取请求 (书源 webView 规则)
// - 请求经 C++ ohos_webview_dispatch(json, html) → napi_call_threadsafe_function → ArkTS 主线程回调
//   (tsfn data 携带两个字符串: 控制面 JSON + 数据面裸 html, 均不经 JSON 转义)
// - ArkTS WebViewBridgeHandler 用隐藏 Web 组件 loadUrl/loadData + onPageEnd 后 runJavaScript 取源码
// - 完成后通过 webViewCallback(requestId, resultJson, bodyRaw) (napi → @CName) 回送结果给 Kotlin
//   (resultJson 为控制面小字段, bodyRaw 为裸源码/命中 URL)

// tsfn 传输数据: 控制面 JSON + 数据面裸 html (均 malloc 拷贝, WebViewCallJs 统一释放)
struct WebViewDispatchData {
    char *json;
    char *html;
};

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.webViewTsfn) 调用
// html 为第二参数裸字符串 (Kotlin 侧已把 null 归一为空串, 此处仅防御性判空)
extern "C" void ohos_webview_dispatch(const char *json, const char *html) {
    if (g_webview_tsfn == nullptr || json == nullptr) return;
    if (html == nullptr) html = "";
    WebViewDispatchData *data = (WebViewDispatchData *) malloc(sizeof(WebViewDispatchData));
    if (data == nullptr) return;
    size_t json_len = strlen(json) + 1;
    size_t html_len = strlen(html) + 1;
    data->json = (char *) malloc(json_len);
    data->html = (char *) malloc(html_len);
    if (data->json == nullptr || data->html == nullptr) {
        free(data->json);
        free(data->html);
        free(data);
        return;
    }
    memcpy(data->json, json, json_len);
    memcpy(data->html, html, html_len);
    napi_status status = napi_call_threadsafe_function(g_webview_tsfn, data, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(data->json);
        free(data->html);
        free(data);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON + html) 包成两个 napi string 后调用 ArkTS 回调
static void WebViewCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    WebViewDispatchData *d = static_cast<WebViewDispatchData *>(data);
    napi_value args[2];
    napi_create_string_utf8(env, d->json, NAPI_AUTO_LENGTH, &args[0]);
    napi_create_string_utf8(env, d->html, NAPI_AUTO_LENGTH, &args[1]);
    napi_call_function(env, js_cb, 2, args, nullptr);
    free(d->json);
    free(d->html);
    free(d);
}

// napi 包装: registerWebViewCallback(callback: (json: string, html: string) => void): void
// ArkTS 注册 webView 回调; C++ 创建 tsfn, 通过 legado_register_webview_fn 注入 ohos_webview_dispatch 到 Kotlin
static napi_value RegisterWebViewCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerWebViewCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_webview_tsfn != nullptr) {
        napi_release_threadsafe_function(g_webview_tsfn, napi_tsfn_abort);
        g_webview_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoWebViewTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,WebViewCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerWebViewCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_webview_tsfn = tsfn;

    if (load_legado_shared() && g_register_webview_fn != nullptr) {
        g_register_webview_fn(&ohos_webview_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerWebViewCallback: legado_register_webview_fn not resolved (KMP invokeWebViewSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: webViewCallback(requestId: number, result: string, body: string): void
// ArkTS → Kotlin webView 后台抓取结果回调 (混合协议: 控制面 JSON + 数据面裸源码/命中 URL)
static napi_value WebViewCallback(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string, 控制面: 不含 body 的结果 JSON)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char *buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    // args[2]: bodyRaw (string, 数据面: 裸源码/命中 URL, 失败或空结果时为空串)
    size_t body_len = 0;
    napi_get_value_string_utf8(env, args[2], nullptr, 0, &body_len);
    char *body_buf = new char[body_len + 1];
    napi_get_value_string_utf8(env, args[2], body_buf, body_len + 1, &body_len);

    if (load_legado_shared() && g_webview_callback != nullptr) {
        g_webview_callback(request_id, buf, body_buf);
    }
    delete[] buf;
    delete[] body_buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ OpenUrl tsfn 接线 (KP8+ 新增, 同 Toast 模式: fire-and-forget dispatch) ============
// 设计与 Toast 完全一致 (fire-and-forget, 无需 ArkTS → Kotlin 结果回调):
// - KMP 通过 OhosNativeBridge.openUrl 发 URL 打开请求
// - 请求经 C++ ohos_open_url_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS SystemBridgeHandler.handleOpenUrl 调 context.startAbility(Want.uri=url) 打开 URL
// 注: 与 Image/Crypto/Http 的 "tsfn + @CName callback" 模式不同, openUrl 无需返回结果,
//     故不注册 legado_open_url_callback (与 Toast 不注册 legado_toast_callback 同理)。

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.openUrlTsfn) 调用
extern "C" void ohos_open_url_dispatch(const char* json) {
    if (g_open_url_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_open_url_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void OpenUrlCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerOpenUrlCallback(callback: (json: string) => void): void
// ArkTS 注册 openUrl 回调; C++ 创建 tsfn, 通过 legado_register_open_url_fn 注入 ohos_open_url_dispatch 到 Kotlin
static napi_value RegisterOpenUrlCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerOpenUrlCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_open_url_tsfn != nullptr) {
        napi_release_threadsafe_function(g_open_url_tsfn, napi_tsfn_abort);
        g_open_url_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoOpenUrlTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,OpenUrlCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerOpenUrlCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_open_url_tsfn = tsfn;

    if (load_legado_shared() && g_register_open_url_fn != nullptr) {
        g_register_open_url_fn(&ohos_open_url_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerOpenUrlCallback: legado_register_open_url_fn not resolved (KMP openUrl 将降级 println)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ FilePicker tsfn 接线 (KP8+ 新增, 同 Image/Crypto/Http 模式: tsfn 发请求 + @CName 回调返回结果) ============
// 设计与 Image/Crypto/Http 完全一致:
// - KMP 通过 OhosNativeBridge.invokeFilePickerSync 发 pickDocuments/pickDocumentContent 请求
// - 请求经 C++ ohos_file_picker_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS FilePickerBridgeHandler 调 @ohos.file.picker.DocumentViewPicker / @ohos.file.fs 执行真实操作
// - 完成后通过 filePickerCallback(requestId, resultJson) (napi → @CName) 回送结果给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.filePickerTsfn) 调用
extern "C" void ohos_file_picker_dispatch(const char* json) {
    if (g_file_picker_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_file_picker_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void FilePickerCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerFilePickerCallback(callback: (json: string) => void): void
// ArkTS 注册 filePicker 回调; C++ 创建 tsfn, 通过 legado_register_file_picker_fn 注入 ohos_file_picker_dispatch 到 Kotlin
static napi_value RegisterFilePickerCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerFilePickerCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_file_picker_tsfn != nullptr) {
        napi_release_threadsafe_function(g_file_picker_tsfn, napi_tsfn_abort);
        g_file_picker_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoFilePickerTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,FilePickerCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerFilePickerCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_file_picker_tsfn = tsfn;

    if (load_legado_shared() && g_register_file_picker_fn != nullptr) {
        g_register_file_picker_fn(&ohos_file_picker_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerFilePickerCallback: legado_register_file_picker_fn not resolved (KMP invokeFilePickerSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: filePickerCallback(requestId: number, result: string): void
// ArkTS → Kotlin filePicker 操作结果回调 (pickDocuments/pickDocumentContent 完成后调用)
static napi_value FilePickerCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_file_picker_callback != nullptr) {
        g_file_picker_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Pasteboard tsfn 接线 (同 FilePicker 模式: tsfn 发请求 + @CName 回调返回结果) ============
// - KMP 通过 OhosNativeBridge.invokePasteboardSync 发 read/write 请求
// - 请求经 C++ ohos_pasteboard_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS PasteboardBridgeHandler 调 @ohos.pasteboard.getSystemPasteboard 读写纯文本
// - 完成后通过 pasteboardCallback(requestId, resultJson) (napi → @CName) 回送结果给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.pasteboardTsfn) 调用
extern "C" void ohos_pasteboard_dispatch(const char* json) {
    if (g_pasteboard_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_pasteboard_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void PasteboardCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerPasteboardCallback(callback: (json: string) => void): void
// ArkTS 注册 pasteboard 回调; C++ 创建 tsfn, 通过 legado_register_pasteboard_fn 注入 ohos_pasteboard_dispatch 到 Kotlin
static napi_value RegisterPasteboardCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerPasteboardCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_pasteboard_tsfn != nullptr) {
        napi_release_threadsafe_function(g_pasteboard_tsfn, napi_tsfn_abort);
        g_pasteboard_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoPasteboardTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,PasteboardCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerPasteboardCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_pasteboard_tsfn = tsfn;

    if (load_legado_shared() && g_register_pasteboard_fn != nullptr) {
        g_register_pasteboard_fn(&ohos_pasteboard_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerPasteboardCallback: legado_register_pasteboard_fn not resolved (KMP invokePasteboardSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: pasteboardCallback(requestId: number, result: string): void
// ArkTS → Kotlin pasteboard 操作结果回调 (read/write 完成后调用)
static napi_value PasteboardCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_pasteboard_callback != nullptr) {
        g_pasteboard_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ TextCodec tsfn 接线 (同 Pasteboard 模式: tsfn 发请求 + @CName 回调返回结果) ============
// - KMP 通过 OhosNativeBridge.invokeTextCodecSync 发 decode/encode 请求
// - 请求经 C++ ohos_text_codec_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS TextCodecBridgeHandler 调 @ohos.util.TextDecoder/TextEncoder 完成 GB18030/Big5 等字符集编解码
// - 完成后通过 textCodecCallback(requestId, resultJson) (napi → @CName) 回送结果给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.textCodecTsfn) 调用
extern "C" void ohos_text_codec_dispatch(const char* json) {
    if (g_text_codec_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_text_codec_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void TextCodecCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerTextCodecCallback(callback: (json: string) => void): void
// ArkTS 注册 textCodec 回调; C++ 创建 tsfn, 通过 legado_register_text_codec_fn 注入 ohos_text_codec_dispatch 到 Kotlin
static napi_value RegisterTextCodecCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerTextCodecCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_text_codec_tsfn != nullptr) {
        napi_release_threadsafe_function(g_text_codec_tsfn, napi_tsfn_abort);
        g_text_codec_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoTextCodecTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
        env, args[0], nullptr, work_name, 0, 1,
        nullptr, nullptr, nullptr,TextCodecCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerTextCodecCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_text_codec_tsfn = tsfn;

    if (load_legado_shared() && g_register_text_codec_fn != nullptr) {
        g_register_text_codec_fn(&ohos_text_codec_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerTextCodecCallback: legado_register_text_codec_fn not resolved (KMP invokeTextCodecSync 将降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: textCodecCallback(requestId: number, result: string): void
// ArkTS → Kotlin textCodec 操作结果回调 (decode/encode 完成后调用)
static napi_value TextCodecCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    // args[0]: requestId (number, int64)
    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    // args[1]: resultJson (string)
    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char* buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_text_codec_callback != nullptr) {
        g_text_codec_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ TextAction tsfn 接线 (阅读页文本操作浮动菜单, 同 OpenUrl fire-and-forget + ArkTS → Kotlin 回调) ============
// - KMP OhosReaderPlatformProvider.onTextSelected → OhosNativeBridge.showTextActionMenu(text, x, y)
// - 请求经 C++ ohos_text_action_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS TextActionBridgeHandler + Index.ets 叠层浮动菜单展示, 菜单项点击经
//   TextActionCallback(requestId, resultJson) (napi → @CName legado_text_action_callback) 回送动作

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.textActionTsfn) 调用
extern "C" void ohos_text_action_dispatch(const char *json) {
    if (g_text_action_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char *json_dup = (char *) malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_text_action_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void TextActionCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    char *json = static_cast<char *>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerTextActionCallback(callback: (json: string) => void): void
// ArkTS 注册文本菜单回调; C++ 创建 tsfn, 通过 legado_register_text_action_fn 注入 ohos_text_action_dispatch 到 Kotlin
static napi_value RegisterTextActionCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerTextActionCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_text_action_tsfn != nullptr) {
        napi_release_threadsafe_function(g_text_action_tsfn, napi_tsfn_abort);
        g_text_action_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoTextActionTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,TextActionCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerTextActionCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_text_action_tsfn = tsfn;

    if (load_legado_shared() && g_register_text_action_fn != nullptr) {
        g_register_text_action_fn(&ohos_text_action_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerTextActionCallback: legado_register_text_action_fn not resolved (KMP showTextActionMenu 将降级 println)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: textActionCallback(requestId: number, resultJson: string): void
// ArkTS 菜单项点击/收起 → C++ 转发 @CName legado_text_action_callback → KMP OhosNativeBridge.onTextActionResult
static napi_value TextActionCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "textActionCallback requires 2 arguments (requestId, resultJson)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char *buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_text_action_callback != nullptr) {
        g_text_action_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Battery tsfn 接线 (同 Crypto 模式: tsfn 发请求 + @CName 回调返回结果) ============
// 阅读页电池电量: KMP getBatteryLevel → invokeBatterySync → tsfn dispatch 到 ArkTS →
// BatteryBridgeHandler 调 @ohos.batteryInfo.batterySOC → batteryCallback 回送结果。

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.batteryTsfn) 调用
extern "C" void ohos_battery_dispatch(const char *json) {
    if (g_battery_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char *json_dup = (char *) malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_battery_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void BatteryCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    char *json = static_cast<char *>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerBatteryCallback(callback: (json: string) => void): void
// ArkTS 注册电量查询回调; C++ 创建 tsfn, 通过 legado_register_battery_fn 注入 ohos_battery_dispatch 到 Kotlin
static napi_value RegisterBatteryCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerBatteryCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_battery_tsfn != nullptr) {
        napi_release_threadsafe_function(g_battery_tsfn, napi_tsfn_abort);
        g_battery_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoBatteryTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,BatteryCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerBatteryCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_battery_tsfn = tsfn;

    if (load_legado_shared() && g_register_battery_fn != nullptr) {
        g_register_battery_fn(&ohos_battery_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerBatteryCallback: legado_register_battery_fn not resolved (KMP getBatteryLevel 将返回 -1)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: batteryCallback(requestId: number, result: string): void
// ArkTS → Kotlin 电量查询结果回调 (getLevel 完成后调用)
static napi_value BatteryCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char *buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_battery_callback != nullptr) {
        g_battery_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Share tsfn 接线 (同 Toast 模式: fire-and-forget dispatch, 无结果回调) ============
// 系统分享: KMP shareText/shareFile → tsfn dispatch 到 ArkTS → SystemShareBridgeHandler 调
// @ohos.share.systemShare SharePanel。分享面板由用户操作, 结果对调用方无意义, 无 ArkTS → Kotlin 回调。

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.shareTsfn) 调用
extern "C" void ohos_share_dispatch(const char *json) {
    if (g_share_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char *json_dup = (char *) malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_share_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void ShareCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    char *json = static_cast<char *>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerShareCallback(callback: (json: string) => void): void
// ArkTS 注册分享回调; C++ 创建 tsfn, 通过 legado_register_share_fn 注入 ohos_share_dispatch 到 Kotlin
static napi_value RegisterShareCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerShareCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_share_tsfn != nullptr) {
        napi_release_threadsafe_function(g_share_tsfn, napi_tsfn_abort);
        g_share_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoShareTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,ShareCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerShareCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_share_tsfn = tsfn;

    if (load_legado_shared() && g_register_share_fn != nullptr) {
        g_register_share_fn(&ohos_share_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerShareCallback: legado_register_share_fn not resolved (KMP shareText/shareFile 将降级剪贴板)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Keyboard tsfn 接线 (同 Window 模式: fire-and-forget dispatch, 无结果回调) ============
// 软键盘显隐/避让: KMP hideSoftInput/showSoftInput/setKeyboardAvoidMode → tsfn dispatch 到 ArkTS →
// KeyboardBridgeHandler 调 @ohos.inputMethod。命令 fire-and-forget, 无 ArkTS → Kotlin 回调。

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.keyboardTsfn) 调用
extern "C" void ohos_keyboard_dispatch(const char *json) {
    if (g_keyboard_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char *json_dup = (char *) malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_keyboard_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void KeyboardCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    char *json = static_cast<char *>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerKeyboardCallback(callback: (json: string) => void): void
// ArkTS 注册软键盘回调; C++ 创建 tsfn, 通过 legado_register_keyboard_fn 注入 ohos_keyboard_dispatch 到 Kotlin
static napi_value RegisterKeyboardCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerKeyboardCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_keyboard_tsfn != nullptr) {
        napi_release_threadsafe_function(g_keyboard_tsfn, napi_tsfn_abort);
        g_keyboard_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoKeyboardTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,KeyboardCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerKeyboardCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_keyboard_tsfn = tsfn;

    if (load_legado_shared() && g_register_keyboard_fn != nullptr) {
        g_register_keyboard_fn(&ohos_keyboard_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerKeyboardCallback: legado_register_keyboard_fn not resolved (KMP 软键盘命令将降级 println)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ Permission tsfn 接线 (同 Pasteboard 模式: tsfn 发请求 + @CName 回调返回结果) ============
// 权限查询/申请: KMP hasPermission/requestPermission → invokePermissionSync → tsfn dispatch 到 ArkTS →
// PermissionBridgeHandler 调 @ohos.abilityAccessCtrl → permissionCallback 回送结果。

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.permissionTsfn) 调用
extern "C" void ohos_permission_dispatch(const char *json) {
    if (g_permission_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char *json_dup = (char *) malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_permission_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void PermissionCallJs(napi_env env, napi_value js_cb, void * /*context*/, void *data) {
    if (js_cb == nullptr || data == nullptr) return;
    char *json = static_cast<char *>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerPermissionCallback(callback: (json: string) => void): void
// ArkTS 注册权限回调; C++ 创建 tsfn, 通过 legado_register_permission_fn 注入 ohos_permission_dispatch 到 Kotlin
static napi_value RegisterPermissionCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "registerPermissionCallback requires 1 argument (callback)");
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }

    if (g_permission_tsfn != nullptr) {
        napi_release_threadsafe_function(g_permission_tsfn, napi_tsfn_abort);
        g_permission_tsfn = nullptr;
    }

    napi_value work_name;
    napi_create_string_utf8(env, "LegadoPermissionTsfn", NAPI_AUTO_LENGTH, &work_name);
    napi_threadsafe_function tsfn;
    napi_status status = napi_create_threadsafe_function(
            env, args[0], nullptr, work_name, 0, 1,
            nullptr, nullptr, nullptr,PermissionCallJs, &tsfn);
    if (status != napi_ok) {
        OH_LOG_ERROR(LOG_APP, "registerPermissionCallback: napi_create_threadsafe_function failed: %{public}d", status);
        napi_value ret;
        napi_get_undefined(env, &ret);
        return ret;
    }
    g_permission_tsfn = tsfn;

    if (load_legado_shared() && g_register_permission_fn != nullptr) {
        g_register_permission_fn(&ohos_permission_dispatch);
    } else {
        OH_LOG_WARN(LOG_APP, "registerPermissionCallback: legado_register_permission_fn not resolved (KMP 权限查询/申请将按无权限降级)");
    }

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// napi 包装: permissionCallback(requestId: number, result: string): void
// ArkTS → Kotlin 权限查询/申请结果回调 (check/request 完成后调用)
static napi_value PermissionCallback(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    int64_t request_id = 0;
    napi_get_value_int64(env, args[0], &request_id);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[1], nullptr, 0, &str_len);
    char *buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[1], buf, str_len + 1, &str_len);

    if (load_legado_shared() && g_permission_callback != nullptr) {
        g_permission_callback(request_id, buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_undefined(env, &ret);
    return ret;
}

// ============ 外部启动请求投递 napi 包装 (ArkTS → Kotlin, 同 legado_handle_deep_link 模式) ============
// 文件关联 / 通知 route / 处理文本 / 其他 scheme deep link: ArkTS EntryAbility 拿到 want.uri 后
// 经本函数投递给 Kotlin OhosLaunchRequests.parse + LaunchRequestBus.dispatch (Compose 侧消费)。
// 同步返回 (不走 tsfn): 与 handleDeepLink 同模式, 仅告知 ArkTS 是否已识别。

// napi 包装: handleLaunchRequest(uri: string): boolean
static napi_value HandleLaunchRequest(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char *buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    int handled = 0;  // 兜底 0 (so 未加载/符号缺失/无法识别)
    if (load_legado_shared() && g_handle_launch_request != nullptr) {
        handled = g_handle_launch_request(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_get_boolean(env, handled != 0, &ret);
    return ret;
}

// ============ 图片下载管线导出 napi 包装 (ArkTS 保存到相册复用, 带书源 header 防盗链) ============
// 返回 base64 编码的图片字节。内部 runBlocking 转同步且下载走 HTTP 桥 (tsfn → ArkTS 主线程回调),
// 调用方必须在 TaskPool/Worker 线程调用 (主线程调用会死锁超时, 与 bookshelfList 等业务函数同约束)。

// napi 包装: downloadImageBytes(url: string): string
static napi_value DownloadImageBytes(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    size_t str_len = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &str_len);
    char *buf = new char[str_len + 1];
    napi_get_value_string_utf8(env, args[0], buf, str_len + 1, &str_len);

    const char *result = "";  // 兜底空串 (so 未加载/下载失败, ArkTS 侧回退裸下载或 toast)
    if (load_legado_shared() && g_download_image_bytes != nullptr) {
        result = g_download_image_bytes(buf);
    }
    delete[] buf;

    napi_value ret;
    napi_create_string_utf8(env, result, NAPI_AUTO_LENGTH, &ret);
    return ret;
}

// napi module 初始化: 注册所有方法
EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
androidx_compose_ui_arkui_init(env, exports
);
    napi_property_descriptor desc[] = {
            {"MainArkUIViewController", nullptr, CreateMainArkUIViewController, nullptr, nullptr, nullptr, napi_default, nullptr},
        // 工具类 (KP4)
        {"chineseT2S", nullptr, ChineseT2S, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"chineseS2T", nullptr, ChineseS2T, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"md5Encode", nullptr, Md5Encode, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"formatPercentUs", nullptr, FormatPercentUs, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"isProvidersRegistered", nullptr, IsProvidersRegistered, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerOhosProviders", nullptr, RegisterOhosProviders, nullptr, nullptr, nullptr, napi_default, nullptr},
        // 业务类 (KP5 新增)
        {"bookshelfList", nullptr, BookshelfList, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"searchBook", nullptr, SearchBook, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"loadChapter", nullptr, LoadChapter, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"chapterList", nullptr, ChapterList, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"importBookSource", nullptr, ImportBookSource, nullptr, nullptr, nullptr, napi_default, nullptr},
        // 漫画 + 发现页 (KP5+ 新增)
        {"loadMangaChapter", nullptr, LoadMangaChapter, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"exploreList", nullptr, ExploreList, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openExplore", nullptr, OpenExplore, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"editExploreSource", nullptr, EditExploreSource, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"topExploreSource", nullptr, TopExploreSource, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"deleteExploreSource", nullptr, DeleteExploreSource, nullptr, nullptr, nullptr, napi_default, nullptr},
        // FileDir/CacheDir 路径注入 (KP7+ 新增, ArkTS → Kotlin 同步推送)
        {"registerFileDir", nullptr, RegisterFileDir, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerCacheDir", nullptr, RegisterCacheDir, nullptr, nullptr, nullptr, napi_default, nullptr},
            // 屏幕尺寸注入 (ArkTS → Kotlin 同步推送, 同 FileDir 模式)
            {"registerScreenSize", nullptr, RegisterScreenSize, nullptr, nullptr, nullptr, napi_default, nullptr},
        // legado:// deep link 投递 (ArkTS → Kotlin 同步推送, 返回是否已识别)
        {"handleDeepLink", nullptr, HandleDeepLink, nullptr, nullptr, nullptr, napi_default, nullptr},
        // Toast/Notification tsfn 回调注册 (KP7+ 新增, KMP → ArkTS 跨线程 dispatch)
        {"registerToastCallback", nullptr, RegisterToastCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerNotificationCallback", nullptr, RegisterNotificationCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // Window tsfn 回调注册 (KMP → ArkTS 窗口策略命令: 全屏/常亮/方向/系统栏)
            {"registerWindowCallback", nullptr, RegisterWindowCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // Image/Media tsfn 回调注册 (KP8+ 新增, KMP → ArkTS 跨线程 dispatch)
        {"registerImageCallback", nullptr, RegisterImageCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerMediaCallback", nullptr, RegisterMediaCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // TTS tsfn 回调注册 (KP8+ 新增, 同 Image/Media 模式)
        {"registerTtsCallback", nullptr, RegisterTtsCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // Image/Media ArkTS → Kotlin 回调 (KP8+ 新增, ArkTS → Kotlin 结果/事件推送)
        {"imageCallback", nullptr, ImageCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"mediaEvent", nullptr, MediaEvent, nullptr, nullptr, nullptr, napi_default, nullptr},
        // TTS ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Media 模式)
        {"ttsEvent", nullptr, TtsEvent, nullptr, nullptr, nullptr, napi_default, nullptr},
        // Crypto tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式)
        {"registerCryptoCallback", nullptr, RegisterCryptoCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"cryptoCallback", nullptr, CryptoCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // Http tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto 模式)
        {"registerHttpCallback", nullptr, RegisterHttpCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"httpCallback", nullptr, HttpCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // OpenUrl tsfn 回调注册 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch)
        {"registerOpenUrlCallback", nullptr, RegisterOpenUrlCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // FilePicker tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式)
        {"registerFilePickerCallback", nullptr, RegisterFilePickerCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"filePickerCallback", nullptr, FilePickerCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // Pasteboard tsfn 回调注册 + ArkTS → Kotlin 回调 (同 FilePicker 模式, read/write)
        {"registerPasteboardCallback", nullptr, RegisterPasteboardCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pasteboardCallback", nullptr, PasteboardCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        // TextCodec tsfn 回调注册 + ArkTS → Kotlin 回调 (同 Pasteboard 模式, decode/encode)
        {"registerTextCodecCallback", nullptr, RegisterTextCodecCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            {"registerTextActionCallback", nullptr, RegisterTextActionCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            {"textActionCallback", nullptr, TextActionCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"textCodecCallback", nullptr, TextCodecCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // WebView tsfn 回调注册 + ArkTS → Kotlin 回调 (后台 WebView 抓取, 同 Http/Image 模式)
            {"registerWebViewCallback", nullptr, RegisterWebViewCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            {"webViewCallback", nullptr, WebViewCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // Battery tsfn 回调注册 + ArkTS → Kotlin 回调 (阅读页电量, 同 Crypto 模式)
            {"registerBatteryCallback", nullptr, RegisterBatteryCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            {"batteryCallback", nullptr, BatteryCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // Share tsfn 回调注册 (同 Toast 模式, fire-and-forget dispatch, 无结果回调)
            {"registerShareCallback", nullptr, RegisterShareCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // Keyboard tsfn 回调注册 (同 Window 模式, fire-and-forget dispatch, 无结果回调)
            {"registerKeyboardCallback", nullptr, RegisterKeyboardCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // Permission tsfn 回调注册 + ArkTS → Kotlin 回调 (同 Pasteboard 模式, check/request)
            {"registerPermissionCallback", nullptr, RegisterPermissionCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            {"permissionCallback", nullptr, PermissionCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
            // 外部启动请求投递 (ArkTS → Kotlin 同步推送, 文件关联/其他 deep link)
            {"handleLaunchRequest", nullptr, HandleLaunchRequest, nullptr, nullptr, nullptr, napi_default, nullptr},
            // 图片下载管线 (ArkTS 保存到相册, 带书源 header, 返回 base64; 须 TaskPool/Worker 调用)
            {"downloadImageBytes", nullptr, DownloadImageBytes, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

// napi module 注册
static napi_module legadoNapiModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = "legado_napi",
    .nm_register_func = Init,
        .nm_modname = "legado_napi",
    .nm_priv = ((void *)0),
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterLegadoNapiModule(void) {
    napi_module_register(&legadoNapiModule);
}
