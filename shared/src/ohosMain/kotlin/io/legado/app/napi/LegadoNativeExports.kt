@file:Suppress("unused")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.napi

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.formatPercentUs
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.experimental.CName

/**
 * KP5 鸿蒙 napi 桥接层: Kotlin/Native 导出的 C ABI 函数。
 *
 * ## 设计目的
 * ArkTS 不能直接调用 Kotlin 类/对象, 只能通过 napi 调用 C 函数。
 * 本文件用 `@CName("xxx")` 注解把若干 KMP 函数导出为 C ABI 符号,
 * 编译后会在 `liblegado_shared.so` 中生成以下符号:
 *
 * ### 工具类 (KP4 已存在)
 * - `legado_chinese_t2s(const char* input) -> const char*`
 * - `legado_chinese_s2t(const char* input) -> const char*`
 * - `legado_md5_encode(const char* input) -> const char*`
 * - `legado_format_percent_us(double) -> const char*`
 * - `legado_providers_registered() -> int`
 * - `legado_register_providers()`
 *
 * ### 业务类 (KP5 新增, 接入真实 KMP 数据)
 * - `legado_bookshelf_list() -> const char*` (返回 JSON 数组)
 * - `legado_search_book(const char* query) -> const char*` (返回 JSON 数组, 书架内搜索)
 * - `legado_load_chapter(const char* bookUrl, int chapterIndex) -> const char*` (返回章节内容)
 * - `legado_import_booksource(const char* json) -> int` (返回导入数量)
 *
 * ## 调用链
 * `ArkTS` → `napi (legado_napi.cpp 包装)` → `dlsym("legado_xxx")` →
 * `Kotlin/Native @CName 函数` → KMP 业务代码 (AppDbProviders/BookStorageProviders/ChineseUtils)
 *   → 返回字符串/整数
 *
 * ## 内存模型
 * - 入参 `const char*` 由 napi 层从 ArkTS 字符串拷贝构造 (CString)
 * - 出参 `const char*` 由 Kotlin 端用 `nativeHeap` 分配 (napi_create_string_utf8 会拷贝走,
 *   理论上原指针可立即释放; 此处简化生命周期管理, 由 nativeHeap 管理直到 module 卸载)
 * - 详细内存约定见 ohosApp/INTEROP.md
 *
 * ## 同步语义
 * 业务函数 (bookshelf_list/search_book/load_chapter/import_booksource) 内部用 [runBlocking]
 * 把 suspend DAO 调用转同步 (Kotlin/Native runBlocking 在 napi 工作线程调用是安全的,
 * ArkTS 主线程不会调用这些函数, napi 层应在 worker 线程调用)。
 *
 * ## 编译要求
 * - Kotlin/Native linuxArm64 target 启用 (`-PenableOhosTarget=true`)
 * - `binaries.sharedLib` 配置输出名为 `legado_shared` 的 .so
 *   (见 modules/shared/build.gradle KP4 段落 TODO 注释)
 * - DevEco Studio 编译 entry 模块时把 .so 一起打包到 HAP
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
object LegadoNativeExports {

    init {
        // 模块加载时自动注册 provider (便于 ArkTS 直接调用转换函数)
        // 真实部署可改在 EntryAbility.onCreate 显式调用 legado_register_providers()
        runCatching { registerOhosProviders() }
    }

    // ===== 工具类函数 (KP4 已存在) =====

    /**
     * 繁体 → 简体。
     *
     * 入参 [input] 为 UTF-8 C 字符串 (napi 层拷贝构造); 出参为 UTF-8 C 字符串。
     * 返回值生命周期由 napi 层负责 (napi_create_string_utf8 会拷贝)。
     */
    @CName("legado_chinese_t2s")
    fun chineseT2S(input: CPointer<ByteVar>): CPointer<ByteVar> {
        val inputStr = input.toKString()
        val result = ChineseUtils.t2s(inputStr)
        return result.cstr.getPointer(nativeHeap)
    }

    /**
     * 简体 → 繁体。
     */
    @CName("legado_chinese_s2t")
    fun chineseS2T(input: CPointer<ByteVar>): CPointer<ByteVar> {
        val inputStr = input.toKString()
        val result = ChineseUtils.s2t(inputStr)
        return result.cstr.getPointer(nativeHeap)
    }

    /**
     * MD5 摘要 (返回 32 字符 hex 字符串)。
     */
    @CName("legado_md5_encode")
    fun md5Encode(input: CPointer<ByteVar>): CPointer<ByteVar> {
        val inputStr = input.toKString()
        val result = MD5Utils.md5Encode(inputStr)
        return result.cstr.getPointer(nativeHeap)
    }

    /**
     * 百分比格式化 (US locale, "12.3%")。
     */
    @CName("legado_format_percent_us")
    fun formatPercentUs(value: Double): CPointer<ByteVar> {
        val result = formatPercentUs(value)
        return result.cstr.getPointer(nativeHeap)
    }

    /**
     * 检查 KMP provider 是否已注册。
     *
     * @return 1 = 已注册, 0 = 未注册
     */
    @CName("legado_providers_registered")
    fun providersRegistered(): Int {
        return try {
            AppConfigProviders.get()
            PreferenceProviders.get()
            1
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * 显式触发 provider 注册 (供 ArkTS EntryAbility.onCreate 调用)。
     */
    @CName("legado_register_providers")
    fun registerProviders() {
        registerOhosProviders()
    }

    // ===== 业务类函数 (KP5 新增, 接入真实 KMP 数据) =====

    /**
     * 获取书架书籍列表 (返回 JSON 数组字符串)。
     *
     * 调用链: `AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll)` 取所有书架书籍,
     * 用 [KS_JSON] + [ListSerializer] 序列化为 JSON 数组字符串返回。
     *
     * 同步语义: 内部 [runBlocking] 把 suspend DAO 调用转同步, napi 层应在 worker 线程调用
     * (Kotlin/Native runBlocking 在主线程可能 deadlock, ArkTS 侧应通过 napi async work 调用)。
     *
     * 失败兜底: provider 未注册 / DAO 查询异常时返回 `"[]"` (空数组), 让 UI 显示空书架而非崩溃。
     *
     * @return UTF-8 C 字符串, JSON 数组格式 `[{"bookUrl":"...","name":"...","author":"...",...}, ...]`
     */
    @CName("legado_bookshelf_list")
    fun bookshelfList(): CPointer<ByteVar> {
        val json = runCatching {
            runBlocking {
                val books = AppDbProviders.get().bookDao.getBooksByGroup(BookGroup.IdAll)
                KS_JSON.encodeToString(ListSerializer(Book.serializer()), books)
            }
        }.getOrNull() ?: "[]"
        return json.cstr.getPointer(nativeHeap)
    }

    /**
     * 在书架内搜索书籍 (返回 JSON 数组字符串)。
     *
     * 调用链: `AppDbProviders.get().bookDao.searchShelfBooks(query)` 取 Flow<List<Book>>,
     * 用 [first] 取首值, 序列化为 JSON 数组字符串返回。
     *
     * 注: 仅搜索书架内已有书籍 (按 name/author/originName/kind/intro 模糊匹配);
     * 不调网络搜索 (WebBook.searchBookAwait 编排复杂, 涉及多书源并发 + UI 进度回调,
     * 后续 KP6+ 在 ArkTS 侧直接调 KMP 业务类实现, 不通过 napi 同步桥接)。
     *
     * 同步语义: 同 [bookshelfList], 内部 [runBlocking] 转 suspend, napi 层应在 worker 线程调用。
     *
     * 失败兜底: 异常时返回 `"[]"` (空数组)。
     *
     * @param query 搜索关键词 (UTF-8 C 字符串)
     * @return UTF-8 C 字符串, JSON 数组格式 `[{"bookUrl":"...","name":"...",...}, ...]`
     */
    @CName("legado_search_book")
    fun searchBook(query: CPointer<ByteVar>): CPointer<ByteVar> {
        val queryStr = query.toKString()
        val json = runCatching {
            runBlocking {
                val books = AppDbProviders.get().bookDao.searchShelfBooks(queryStr).first()
                KS_JSON.encodeToString(ListSerializer(Book.serializer()), books)
            }
        }.getOrNull() ?: "[]"
        return json.cstr.getPointer(nativeHeap)
    }

    /**
     * 加载章节内容 (返回章节正文文本)。
     *
     * 调用链:
     * 1. `AppDbProviders.get().bookDao.getBook(bookUrl)` 取书籍
     * 2. `AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)` 取章节列表
     * 3. 取 [chapterIndex] 对应章节
     * 4. `BookStorageProviders.get().getContent(book, chapter)` 读本地缓存正文
     *
     * 注: 仅读本地缓存, 不联网拉取 (WebBook.getContentAwait 编排复杂, 涉及书源规则解析,
     * 后续 KP6+ 在 ArkTS 侧直接调 KMP ReadBookViewModelShared.loadChapter 实现)。
     * 缓存未命中时返回空字符串, UI 显示"暂无章节内容"。
     *
     * 同步语义: 同 [bookshelfList], 内部 [runBlocking] 转 suspend, napi 层应在 worker 线程调用。
     *
     * 失败兜底: provider 未注册 / 书籍不存在 / 章节越界 / 读取异常时返回空字符串 `""`。
     *
     * @param bookUrl 书籍 URL (UTF-8 C 字符串, 与 Book.bookUrl 一致)
     * @param chapterIndex 章节索引 (0-based)
     * @return UTF-8 C 字符串, 章节正文文本 (缓存未命中时返回空字符串)
     */
    @CName("legado_load_chapter")
    fun loadChapter(bookUrl: CPointer<ByteVar>, chapterIndex: Int): CPointer<ByteVar> {
        val bookUrlStr = bookUrl.toKString()
        val content = runCatching {
            runBlocking {
                val book = AppDbProviders.get().bookDao.getBook(bookUrlStr) ?: return@runBlocking ""
                val chapterList = AppDbProviders.get().bookChapterDao.getChapterList(bookUrlStr)
                val chapter = chapterList.getOrNull(chapterIndex) ?: return@runBlocking ""
                BookStorageProviders.get().getContent(book, chapter) ?: ""
            }
        }.getOrNull() ?: ""
        return content.cstr.getPointer(nativeHeap)
    }

    /**
     * 导入书源 (返回导入数量)。
     *
     * 调用链:
     * 1. 用 [KS_JSON] + [ListSerializer] 反序列化 JSON 为 `List<BookSource>`
     * 2. `AppDbProviders.get().bookSourceDao.insert(*sources.toTypedArray())` 批量插入
     *
     * 注: 仅支持 JSON 数组格式 `[{"bookSourceUrl":"...","bookSourceName":"...",...}, ...]`,
     * 与 app 端 ImportBookSourceActivity 的 JSON 导入行为对齐 (简化版, 不去重 / 不变字段)。
     *
     * 同步语义: 同 [bookshelfList], 内部 [runBlocking] 转 suspend, napi 层应在 worker 线程调用。
     *
     * 失败兜底: provider 未注册 / JSON 解析异常 / DAO 插入异常时返回 0。
     *
     * @param json 书源 JSON 数组字符串 (UTF-8 C 字符串)
     * @return 导入数量 (0 表示失败或空数组)
     */
    @CName("legado_import_booksource")
    fun importBookSource(json: CPointer<ByteVar>): Int {
        val jsonStr = json.toKString()
        return runCatching {
            val sources = KS_JSON.decodeFromString(ListSerializer(BookSource.serializer()), jsonStr)
            if (sources.isEmpty()) return 0
            runBlocking {
                AppDbProviders.get().bookSourceDao.insert(*sources.toTypedArray())
            }
            sources.size
        }.getOrNull() ?: 0
    }

    // ===== FileDir / CacheDir 路径注入 (ArkTS → Kotlin, KP7+) =====

    /**
     * 注入鸿蒙应用沙盒 filesDir 路径 (ArkTS EntryAbility.onCreate 调用)。
     *
     * 调用链: `ArkTS EntryAbility` → `legado.registerFileDir(context.filesDir)` →
     * napi (legado_napi.cpp RegisterFileDir) → dlsym("legado_register_file_dir") →
     * 本函数 → [OhosNativeBridge.registerFileDirFn]。
     *
     * # 时机
     * 必须在 `legado.registerOhosProviders()` 之前调用, 使 OhosDatabaseDriver.defaultDbPath /
     * BookStorage 等读到的 [io.legado.app.help.file.AppFilesDirs.filesDir] 为真实沙盒路径
     * (而非 POSIX user.dir 回退路径)。即便迟到, AppFilesDirs 用计算 getter +
     * registerOhosProviders 重注册机制可自愈 (见 [OhosNativeBridge.registerFileDirFn])。
     *
     * # 跨语言传递
     * 单一路径字符串直接传 (无需 JSON 包装: payload 仅一字段, JSON 化徒增 ArkTS 侧 parse 开销;
     * 与 toast/notification 多字段 payload 用 KS_JSON 不同)。
     *
     * @param path filesDir 绝对路径 (UTF-8 C 字符串, 如 `/data/storage/el2/base/haps/entry/files`)
     */
    @CName("legado_register_file_dir")
    fun registerFileDir(path: CPointer<ByteVar>) {
        OhosNativeBridge.registerFileDirFn(path.toKString())
    }

    /**
     * 注入鸿蒙应用沙盒 cacheDir 路径 (ArkTS EntryAbility.onCreate 调用)。
     *
     * @param path cacheDir 绝对路径 (UTF-8 C 字符串, 如 `/data/storage/el2/base/haps/entry/cache`)
     */
    @CName("legado_register_cache_dir")
    fun registerCacheDir(path: CPointer<ByteVar>) {
        OhosNativeBridge.registerCacheDirFn(path.toKString())
    }

    // ===== Toast/Notification tsfn 注入 (KP7+, C++ → Kotlin dispatch 函数指针注入) =====

    /**
     * 注入 toast dispatch 函数指针 (由 legado_napi.cpp RegisterToastCallback 调用)。
     *
     * 调用链: `ArkTS registerToastCallback(cb)` → C++ 创建 napi_threadsafe_function 包装 cb →
     * C++ dlsym("legado_register_toast_fn") → 本函数 → 把 [dispatch] 包成 (String) -> Unit lambda →
     * [OhosNativeBridge.registerToastFn]。
     *
     * 之后 KMP 业务调用 [OhosNativeBridge.showToast] 时, 调用链:
     * `OhosNativeBridge.showToast` → `toastTsfn?.invoke(json)` → 本 lambda → `dispatch(json.cstr)` →
     * C++ ohos_toast_dispatch → napi_call_threadsafe_function → ToastCallJs (ArkTS 主线程) → ArkTS cb(json)。
     *
     * # 跨语言传递
     * [dispatch] 是 C++ 函数 `ohos_toast_dispatch(const char*)` 的地址, 接收 JSON 字符串,
     * 内部 napi_call_threadsafe_function 跨线程 dispatch 到 ArkTS。
     * Kotlin 侧用 [memScoped] 分配临时 C 字符串 (调用结束即回收); C++ 侧 ohos_toast_dispatch
     * 内部会 malloc 拷贝一份 (napi_call_threadsafe_function 异步, 原指针生命周期不可控), 故临时指针安全。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_toast_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_toast_fn")
    fun registerToastFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerToastFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * 注入 notification dispatch 函数指针 (由 legado_napi.cpp RegisterNotificationCallback 调用)。
     *
     * 同 [registerToastFn], 注入到 [OhosNativeBridge.notificationTsfn],
     * 使 KMP [OhosNativeBridge.showNotification] / [OhosNativeBridge.cancelNotification]
     * 能跨线程 dispatch 到 ArkTS。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_notification_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_notification_fn")
    fun registerNotificationFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerNotificationFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    // ===== Image tsfn 注入 + ArkTS → Kotlin 结果回调 (KP8+) =====

    /**
     * 注入 image dispatch 函数指针 (由 legado_napi.cpp RegisterImageCallback 调用)。
     *
     * 同 [registerToastFn], 注入到 [OhosNativeBridge.imageTsfn],
     * 使 KMP [OhosNativeBridge.invokeImageSync] 能跨线程 dispatch 图片操作请求到 ArkTS。
     * ArkTS 处理完成后通过 [imageCallback] (@CName legado_image_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_image_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_image_fn")
    fun registerImageFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerImageFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin 图片操作结果回调 (由 legado_napi.cpp ImageCallback 调用)。
     *
     * 调用链: `ArkTS imageCallback(requestId, resultJson)` → napi (legado_napi.cpp ImageCallback) →
     * dlsym("legado_image_callback") → 本函数 → [OhosNativeBridge.onImageResult] →
     * 唤醒 [OhosNativeBridge.invokeImageSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeImageSync] 生成的 requestId 一致
     * - [result]: UTF-8 C 字符串, JSON 格式 (含 ok/result 或 ok/error)
     *
     * # 线程语义
     * ArkTS 主线程调用 napi → C++ dlsym → 本函数, 运行在 ArkTS 主线程;
     * CompletableDeferred.complete 线程安全, 可安全唤醒 JS 引擎线程的 runBlocking。
     *
     * @param requestId 请求 ID (与 invokeImageSync 生成的 requestId 对应)
     * @param result ArkTS 返回的结果 JSON 字符串 (UTF-8 C 字符串)
     */
    @CName("legado_image_callback")
    fun imageCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onImageResult(requestId, result.toKString())
    }

    // ===== Media tsfn 注入 + ArkTS → Kotlin 事件回调 (KP8+) =====

    /**
     * 注入 media dispatch 函数指针 (由 legado_napi.cpp RegisterMediaCallback 调用)。
     *
     * 同 [registerToastFn], 注入到 [OhosNativeBridge.mediaTsfn],
     * 使 KMP [OhosNativeBridge.sendMediaCommand] 能跨线程 dispatch 播放器命令到 ArkTS。
     * ArkTS AVPlayer 事件通过 [mediaEvent] (@CName legado_media_event) 回送。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_media_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_media_fn")
    fun registerMediaFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerMediaFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin media 事件回调 (由 legado_napi.cpp MediaEvent 调用)。
     *
     * 调用链: `ArkTS mediaEvent(eventJson)` → napi (legado_napi.cpp MediaEvent) →
     * dlsym("legado_media_event") → 本函数 → [OhosNativeBridge.onMediaEvent] →
     * 转发给 [OhosNativeBridge.MediaEventListener] (即 OhosHttpTtsPlayer)。
     *
     * # 事件类型
     * - onReady: AVPlayer 准备完成 (对应 [HttpTtsPlayerListener.onReady])
     * - onEndOfMedia: 播放结束 (对应 [HttpTtsPlayerListener.onEndOfMedia])
     * - onError: 播放错误 (对应 [HttpTtsPlayerListener.onError])
     * - onBufferingUpdate: 缓冲进度 (对应 [HttpTtsPlayerListener.onBufferingUpdate])
     * - onDuration: 总时长更新 (Kotlin 缓存 duration getter 返回值)
     * - onPosition: 播放位置更新 (Kotlin 缓存 currentPosition getter 返回值)
     * - onPlaying/onPaused: 播放/暂停状态 (Kotlin 缓存 isPlaying getter 返回值)
     *
     * @param event 事件 JSON 字符串 (UTF-8 C 字符串, 如 `{"event":"onReady"}`)
     */
    @CName("legado_media_event")
    fun mediaEvent(event: CPointer<ByteVar>) {
        OhosNativeBridge.onMediaEvent(event.toKString())
    }

    // ===== TTS tsfn 注入 + ArkTS → Kotlin 事件回调 (同 Media 模式) =====

    /**
     * 注入 tts dispatch 函数指针 (由 legado_napi.cpp RegisterTtsCallback 调用)。
     *
     * 同 [registerMediaFn], 注入到 [OhosNativeBridge.ttsTsfn],
     * 使 KMP [OhosNativeBridge.sendTtsCommand] 能跨线程 dispatch TTS 命令到 ArkTS。
     * ArkTS @ohos.textToSpeech 事件通过 [ttsEvent] (@CName legado_tts_event) 回送。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_tts_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_tts_fn")
    fun registerTtsFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerTtsFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin TTS 事件回调 (由 legado_napi.cpp TtsEvent 调用)。
     *
     * 调用链: `ArkTS ttsEvent(eventJson)` → napi (legado_napi.cpp TtsEvent) →
     * dlsym("legado_tts_event") → 本函数 → [OhosNativeBridge.onTtsEvent] →
     * 转发给 [OhosNativeBridge.TtsEventListener] (即 OhosSystemTtsEngine)。
     *
     * # 事件类型
     * - onStart: TTS 开始播报 (对应 [TtsProgressListener.onStart])
     * - onComplete: TTS 播报完成 (对应 [TtsProgressListener.onDone])
     * - onStop: TTS 停止播报 (对应 [TtsProgressListener.onDone])
     * - onError: TTS 错误 (对应 [TtsProgressListener.onError])
     *
     * @param event 事件 JSON 字符串 (UTF-8 C 字符串, 如 `{"event":"onStart","utteranceId":"xxx"}`)
     */
    @CName("legado_tts_event")
    fun ttsEvent(event: CPointer<ByteVar>) {
        OhosNativeBridge.onTtsEvent(event.toKString())
    }
}
