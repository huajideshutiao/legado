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
 * 详细方案见 ohosApp/INTEROP.md。
 *
 * ## 同步语义注意
 * 业务类函数 (bookshelfList/searchBook/loadChapter/importBookSource) 内部用 runBlocking
 * 把 suspend DAO 调用转同步, 在 napi 主线程调用可能阻塞 UI。
 * ArkTS 侧应通过 TaskPool / Worker 调用这些函数, 避免阻塞主线程。
 */

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
typedef void (*legado_cstr_cstr_void_fn)(const char*, const char*);
typedef void (*legado_int64_cstr_void_fn)(int64_t, const char*);

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

// dlsym 加载的函数指针 - Toast/Notification tsfn 注入 (KP7+ 新增, C++ → Kotlin @CName 注入 dispatch 函数指针)
// Kotlin 侧 legado_register_toast_fn / legado_register_notification_fn 接收一个 C 函数指针,
// 包成 (String) -> Unit lambda 存入 OhosNativeBridge.toastTsfn / notificationTsfn;
// KMP 业务调用 showToast/showNotification 时, lambda 调用此 dispatch 函数指针回到 C++,
// 由 C++ napi_call_threadsafe_function 跨线程 dispatch 到 ArkTS 主线程执行回调。
typedef void (*legado_register_dispatch_fn)(legado_cstr_void_fn);
static legado_register_dispatch_fn g_register_toast_fn = nullptr;
static legado_register_dispatch_fn g_register_notification_fn = nullptr;

// dlsym 加载的函数指针 - Image/Media tsfn 注入 (KP8+ 新增, 同 Toast/Notification 模式)
static legado_register_dispatch_fn g_register_image_fn = nullptr;
static legado_register_dispatch_fn g_register_media_fn = nullptr;

// dlsym 加载的函数指针 - Image/Media ArkTS → Kotlin 回调 (KP8+ 新增, 同 FileDir @CName 模式)
// ArkTS 侧 imageCallback(requestId, resultJson) / mediaEvent(eventJson) 通过 napi 调 C++,
// C++ 通过 dlsym 调 Kotlin @CName 函数, 把结果/事件推送给 Kotlin。
static legado_int64_cstr_void_fn g_image_callback = nullptr;
static legado_cstr_void_fn g_media_event = nullptr;

// dlsym 加载的函数指针 - TTS tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Media 模式)
static legado_register_dispatch_fn g_register_tts_fn = nullptr;
static legado_cstr_void_fn g_tts_event = nullptr;

// dlsym 加载的函数指针 - Crypto tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式, encrypt/decrypt/sign/verify)
static legado_register_dispatch_fn g_register_crypto_fn = nullptr;
static legado_int64_cstr_void_fn g_crypto_callback = nullptr;

// dlsym 加载的函数指针 - Http tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto 模式, execute/cancel)
static legado_register_dispatch_fn g_register_http_fn = nullptr;
static legado_int64_cstr_void_fn g_http_callback = nullptr;

// dlsym 加载的函数指针 - OpenUrl tsfn 注入 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch)
static legado_register_dispatch_fn g_register_open_url_fn = nullptr;

// dlsym 加载的函数指针 - FilePicker tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式, pickDocuments/pickDocumentContent)
static legado_register_dispatch_fn g_register_file_picker_fn = nullptr;
static legado_int64_cstr_void_fn g_file_picker_callback = nullptr;

// Toast/Notification/Image/Media/TTS/Crypto/Http/OpenUrl/FilePicker threadsafe_function 引用 (C++ 侧持有, ArkTS registerXxxCallback 时创建)
static napi_threadsafe_function g_toast_tsfn = nullptr;
static napi_threadsafe_function g_notification_tsfn = nullptr;
static napi_threadsafe_function g_image_tsfn = nullptr;
static napi_threadsafe_function g_media_tsfn = nullptr;
static napi_threadsafe_function g_tts_tsfn = nullptr;
static napi_threadsafe_function g_crypto_tsfn = nullptr;
static napi_threadsafe_function g_http_tsfn = nullptr;
static napi_threadsafe_function g_open_url_tsfn = nullptr;
static napi_threadsafe_function g_file_picker_tsfn = nullptr;

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

    // 解析 @CName 导出符号 - Toast/Notification tsfn 注入 (KP7+ 新增)
    g_register_toast_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_toast_fn");
    g_register_notification_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_notification_fn");

    // 解析 @CName 导出符号 - Image/Media tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增)
    g_register_image_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_image_fn");
    g_register_media_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_media_fn");
    g_image_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_image_callback");
    g_media_event = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_media_event");

    // 解析 @CName 导出符号 - TTS tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Media 模式)
    g_register_tts_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_tts_fn");
    g_tts_event = (legado_cstr_void_fn)dlsym(g_legado_so, "legado_tts_event");

    // 解析 @CName 导出符号 - Crypto tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式)
    g_register_crypto_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_crypto_fn");
    g_crypto_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_crypto_callback");

    // 解析 @CName 导出符号 - Http tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto 模式)
    g_register_http_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_http_fn");
    g_http_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_http_callback");

    // 解析 @CName 导出符号 - OpenUrl tsfn 注入 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch)
    g_register_open_url_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_open_url_fn");

    // 解析 @CName 导出符号 - FilePicker tsfn 注入 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式)
    g_register_file_picker_fn = (legado_register_dispatch_fn)dlsym(g_legado_so, "legado_register_file_picker_fn");
    g_file_picker_callback = (legado_int64_cstr_void_fn)dlsym(g_legado_so, "legado_file_picker_callback");

    OH_LOG_INFO(LOG_APP, "liblegado_shared.so loaded, symbols resolved (KP5: + bookshelfList/searchBook/loadChapter/chapterList/importBookSource; KP5+: + loadMangaChapter/exploreList/openExplore/editExploreSource/topExploreSource/deleteExploreSource; KP7+: + registerFileDir/registerCacheDir/registerToastFn/registerNotificationFn; KP8+: + registerImageFn/registerMediaFn/imageCallback/mediaEvent/registerTtsFn/ttsEvent/registerCryptoFn/cryptoCallback/registerHttpFn/httpCallback/registerOpenUrlFn/registerFilePickerFn/filePickerCallback)");
    return true;
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, ToastCallJs, &tsfn);
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, NotificationCallJs, &tsfn);
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

// ============ Image/Media/TTS tsfn 接线 (KP8+ 新增, 同 Toast/Notification 模式) ============
// 设计与 Toast/Notification 完全一致:
// - KMP 通过 OhosNativeBridge.invokeImageSync / sendMediaCommand / speakTts 发请求
// - 请求经 C++ dispatch 函数 → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS 处理后通过 imageCallback/mediaEvent/ttsEvent (napi → @CName) 回送结果/事件给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.imageTsfn/mediaTsfn/ttsTsfn) 调用
extern "C" void ohos_image_dispatch(const char* json) {
    if (g_image_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_image_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
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

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void ImageCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
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

// napi 包装: registerImageCallback(callback: (json: string) => void): void
// ArkTS 注册 image 回调; C++ 创建 tsfn, 通过 legado_register_image_fn 注入 ohos_image_dispatch 到 Kotlin
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, ImageCallJs, &tsfn);
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, MediaCallJs, &tsfn);
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, TtsCallJs, &tsfn);
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

// napi 包装: imageCallback(requestId: number, result: string): void
// ArkTS → Kotlin 图片操作结果回调 (decode/encode/size/crop/split/stitch 完成后调用)
static napi_value ImageCallback(napi_env env, napi_callback_info info) {
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

    if (load_legado_shared() && g_image_callback != nullptr) {
        g_image_callback(request_id, buf);
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, CryptoCallJs, &tsfn);
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

// ============ Http tsfn 接线 (KP8+ 新增, 同 Image/Crypto 模式: tsfn 发请求 + @CName 回调返回结果) ============
// 设计与 Image/Crypto 完全一致:
// - KMP 通过 OhosNativeBridge.invokeHttpSync 发 execute/cancel 请求
// - 请求经 C++ ohos_http_dispatch → napi_call_threadsafe_function → ArkTS 主线程回调
// - ArkTS HttpBridgeHandler 调 @ohos.net.http 执行真实请求
// - 完成后通过 httpCallback(requestId, resultJson) (napi → @CName) 回送结果给 Kotlin

// C++ dispatch 入口: 由 Kotlin lambda (注入到 OhosNativeBridge.httpTsfn) 调用
extern "C" void ohos_http_dispatch(const char* json) {
    if (g_http_tsfn == nullptr || json == nullptr) return;
    size_t len = strlen(json) + 1;
    char* json_dup = (char*)malloc(len);
    if (json_dup == nullptr) return;
    memcpy(json_dup, json, len);
    napi_status status = napi_call_threadsafe_function(g_http_tsfn, json_dup, napi_tsfn_nonblocking);
    if (status != napi_ok) {
        free(json_dup);
    }
}

// tsfn call-js 回调: 在 ArkTS 主线程执行, 把 data (JSON 串) 包成 napi string 后调用 ArkTS 回调
static void HttpCallJs(napi_env env, napi_value js_cb, void* /*context*/, void* data) {
    if (js_cb == nullptr || data == nullptr) return;
    char* json = static_cast<char*>(data);
    napi_value json_arg;
    napi_create_string_utf8(env, json, NAPI_AUTO_LENGTH, &json_arg);
    napi_call_function(env, js_cb, 1, &json_arg, nullptr);
    free(json);
}

// napi 包装: registerHttpCallback(callback: (json: string) => void): void
// ArkTS 注册 http 回调; C++ 创建 tsfn, 通过 legado_register_http_fn 注入 ohos_http_dispatch 到 Kotlin
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, HttpCallJs, &tsfn);
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

// napi 包装: httpCallback(requestId: number, result: string): void
// ArkTS → Kotlin HTTP 请求结果回调 (execute 完成后调用)
static napi_value HttpCallback(napi_env env, napi_callback_info info) {
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

    if (load_legado_shared() && g_http_callback != nullptr) {
        g_http_callback(request_id, buf);
    }
    delete[] buf;

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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, OpenUrlCallJs, &tsfn);
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
        env, args[0], work_name, nullptr, 0, 1,
        nullptr, nullptr, nullptr, nullptr, FilePickerCallJs, &tsfn);
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

// napi module 初始化: 注册所有方法
EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
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
        // Toast/Notification tsfn 回调注册 (KP7+ 新增, KMP → ArkTS 跨线程 dispatch)
        {"registerToastCallback", nullptr, RegisterToastCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerNotificationCallback", nullptr, RegisterNotificationCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
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
    .nm_modname = "legado.legado_napi",
    .nm_priv = ((void *)0),
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterLegadoNapiModule(void) {
    napi_module_register(&legadoNapiModule);
}
