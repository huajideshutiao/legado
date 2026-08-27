@file:Suppress("unused")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.napi

import io.legado.app.OhosLaunchRequests
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookStorageProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.registerOhosProviders
import io.legado.app.help.image.ohosDownloadImageBytes
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.formatPercentUs as formatPercentUsValue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.native.CName

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
 * - `legado_chapter_list(const char* bookUrl) -> const char*` (返回章节目录 JSON 数组)
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

    private fun allocateCString(value: String): CPointer<ByteVar> {
        val bytes = value.encodeToByteArray()
        return nativeHeap.allocArrayOf(bytes + byteArrayOf(0))
    }

    /**
     * 二进制混合协议 dispatch 公共包装 (Http/Image 桥): 控制面 JSON + 数据面裸字节 → C++。
     *
     * 字节用 [usePinned] 零拷贝直传 (pinned 期间调用 [dispatch], C++ 侧同步 malloc 深拷贝后返回,
     * 故 pinned 块内安全); 无字节面时传 null 指针 + 长度 0。
     *
     * @param dispatch C++ tsfn dispatch 入口 `void(*)(const char*, const void*, size_t)`
     * @param json 控制面 JSON 字符串
     * @param bytes 数据面裸字节 (null/空数组 = 无字节面)
     */
    private fun dispatchBinary(
        dispatch: CPointer<CFunction<(CPointer<ByteVar>, COpaquePointer?, ULong) -> Unit>>,
        json: String,
        bytes: ByteArray?,
    ) {
        if (bytes == null || bytes.isEmpty()) {
            memScoped {
                dispatch(json.cstr.getPointer(this), null, 0uL)
            }
        } else {
            bytes.usePinned { pinned ->
                memScoped {
                    dispatch(json.cstr.getPointer(this), pinned.addressOf(0), bytes.size.toULong())
                }
            }
        }
    }

    /**
     * 把 ArkTS → Kotlin 回调传来的数据面裸字节指针拷成 [ByteArray]。
     * null 指针 / 长度 0 / 长度超 Int 上限时返回 null (调用方按"无字节面"处理)。
     */
    private fun COpaquePointer?.toByteArrayOrNull(len: ULong): ByteArray? {
        if (this == null || len == 0uL || len > Int.MAX_VALUE.toULong()) return null
        return reinterpret<ByteVar>().readBytes(len.toInt())
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
        return allocateCString(result)
    }

    /**
     * 简体 → 繁体。
     */
    @CName("legado_chinese_s2t")
    fun chineseS2T(input: CPointer<ByteVar>): CPointer<ByteVar> {
        val inputStr = input.toKString()
        val result = ChineseUtils.s2t(inputStr)
        return allocateCString(result)
    }

    /**
     * MD5 摘要 (返回 32 字符 hex 字符串)。
     */
    @CName("legado_md5_encode")
    fun md5Encode(input: CPointer<ByteVar>): CPointer<ByteVar> {
        val inputStr = input.toKString()
        val result = MD5Utils.md5Encode(inputStr)
        return allocateCString(result)
    }

    /**
     * 百分比格式化 (US locale, "12.3%")。
     */
    @CName("legado_format_percent_us")
    fun formatPercentUs(value: Double): CPointer<ByteVar> {
        val result = formatPercentUsValue(value)
        return allocateCString(result)
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
     * 调用链: `AppDbProviders.get().bookDao.flowByGroup(BookGroup.IdAll).first()` 取所有书架书籍,
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
                // 走 flowByGroup 正确分流 (getBooksByGroup 对负数特殊 id 位掩码会漏未分组书)
                val books = AppDbProviders.get().bookDao.flowByGroup(BookGroup.IdAll).first()
                KS_JSON.encodeToString(ListSerializer(Book.serializer()), books)
            }
        }.getOrNull() ?: "[]"
        return allocateCString(json)
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
        return allocateCString(json)
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
        return allocateCString(content)
    }

    /**
     * 获取章节目录列表 (返回 JSON 数组字符串)。
     *
     * 调用链: `AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)` 取章节列表,
     * 仅序列化 UI 所需字段 (index/title/url) 返回, 避免传输 BookChapter 全部字段。
     *
     * 同步语义: 同 [loadChapter], 内部 [runBlocking] 转 suspend, napi 层应在 worker 线程调用。
     *
     * 失败兜底: provider 未注册 / 章节列表为空 / 异常时返回 `"[]"` (空数组)。
     *
     * @param bookUrl 书籍 URL (UTF-8 C 字符串)
     * @return UTF-8 C 字符串, JSON 数组格式 `[{"index":0,"title":"...","url":"..."}, ...]`
     */
    @CName("legado_chapter_list")
    fun chapterList(bookUrl: CPointer<ByteVar>): CPointer<ByteVar> {
        val bookUrlStr = bookUrl.toKString()
        val json = runCatching {
            runBlocking {
                val list = AppDbProviders.get().bookChapterDao.getChapterList(bookUrlStr)
                buildJsonArray {
                    list.forEach { ch ->
                        add(buildJsonObject {
                            put("index", ch.index)
                            put("title", ch.title)
                            put("url", ch.url)
                        })
                    }
                }.toString()
            }
        }.getOrNull() ?: "[]"
        return allocateCString(json)
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

    /**
     * 注入显示物理像素尺寸 (ArkTS EntryAbility.onWindowStageCreate 调用)。
     *
     * 调用链: `ArkTS EntryAbility` → `legado.registerScreenSize(w, h)` →
     * napi (legado_napi.cpp RegisterScreenSize) → dlsym("legado_register_screen_size") →
     * 本函数 → [OhosNativeBridge.registerScreenSizeFn]。
     *
     * # 时机
     * EntryAbility.onWindowStageCreate 中 loadContent 之前调用 (任何 shared 对话框尺寸计算之前;
     * sharedUiMain AppDialogSizes 未注册 ScreenInfoProviders 时 get() 直接 error 导致对话框崩溃)。
     *
     * # 跨语言传递
     * 两个 int 直接传 (无需 JSON 包装: payload 仅两字段, 与 registerFileDir 同思路)。
     *
     * @param widthPx 显示物理像素宽度 (vp × densityPixels, 与 iOS nativeBounds / Android displayMetrics 语义一致)
     * @param heightPx 显示物理像素高度
     */
    @CName("legado_register_screen_size")
    fun registerScreenSize(widthPx: Int, heightPx: Int) {
        OhosNativeBridge.registerScreenSizeFn(widthPx, heightPx)
    }

    // ===== legado:// deep link 投递 (ArkTS → Kotlin, 被动接收无回调) =====

    /**
     * 投递 legado:// / yuedu:// deep link (ArkTS EntryAbility.onCreate/onNewWant 调用)。
     *
     * 调用链: `ArkTS EntryAbility.handleDeepLink(want)` → `legado.handleDeepLink(want.uri)` →
     * napi (legado_napi.cpp HandleDeepLink) → dlsym("legado_handle_deep_link") →
     * 本函数 → [LegadoDeepLinkHandler.handle] → 解析后写入 `pending` (StateFlow)。
     *
     * 消费侧是 [io.legado.app.MainOhos] 尾部的
     * [io.legado.app.ui.association.DeepLinkImportHost], 弹勾选对话框后入库。
     *
     * # 时机
     * pending 是 StateFlow, 冷启动 (onCreate) 早于 Compose 组合投递也不丢, 组合起来即消费。
     *
     * # 跨语言传递
     * 单一 URL 字符串直接传 (同 [registerFileDir], 无需 JSON 包装)。
     * 无回调: ArkTS 只需知道是否已识别, 同步返回即可。
     *
     * @param url deep link URL (UTF-8 C 字符串, 如 `legado://import/bookSource?src=https://...`)
     * @return 1=已识别并记录待导入; 0=非 legado/yuedu scheme 或缺 src 参数 (ArkTS 可透传其他处理)
     */
    @CName("legado_handle_deep_link")
    fun handleDeepLink(url: CPointer<ByteVar>): Int =
        if (LegadoDeepLinkHandler.handle(url.toKString())) 1 else 0

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
     * # 混合协议 (JSON + 裸字节, 同 WebView 桥思路)
     * [dispatch] 接收控制面 JSON (requestId/action/payload) + 数据面裸字节 (decode 的图片字节)。
     * 字节经 usePinned 零拷贝直传 C++ (ohos_image_dispatch 内 malloc 深拷贝, 因 tsfn 异步投递
     * 原指针生命周期不可控), C++ 用 napi_create_external_arraybuffer 包成 ArrayBuffer 传给 ArkTS,
     * 不经 base64 (避免 33% 体积膨胀 + 双端编解码拷贝), 二进制保真。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_image_dispatch`),
     *   类型 `void(*)(const char*, const void*, size_t)` (JSON + 裸字节 + 长度)
     */
    @CName("legado_register_image_fn")
    fun registerImageFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>, COpaquePointer?, ULong) -> Unit>>) {
        OhosNativeBridge.registerImageFn { json, bytes ->
            dispatchBinary(dispatch, json, bytes)
        }
    }

    /**
     * ArkTS → Kotlin 图片操作结果回调 (由 legado_napi.cpp ImageCallback 调用, 混合协议)。
     *
     * 调用链: `ArkTS imageCallback(requestId, resultJson, body?)` → napi (legado_napi.cpp ImageCallback) →
     * dlsym("legado_image_callback") → 本函数 → [OhosNativeBridge.onImageResult] →
     * 唤醒 [OhosNativeBridge.invokeImageSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeImageSync] 生成的 requestId 一致
     * - [result]: 控制面结果 JSON (不含字节):
     *   - decode/crop/stitch 成功: `{ ok: true, pixelMapId: <number> }`
     *   - split 成功: `{ ok: true, pixelMapIds: [...] }`
     *   - size 成功: `{ ok: true, width, height }`
     *   - encode 成功: `{ ok: true }` (packed 字节走 [bytes] 裸参)
     *   - 失败: `{ ok: false, error: "..." }`
     * - [bytes]: 数据面裸字节 (C++ 用 napi_get_arraybuffer_info 零拷贝取 ArrayBuffer 数据指针;
     *   仅 encode 回传 packed 图片, 其余操作/失败为 null)
     * - [bytesLen]: [bytes] 长度 (无字节面时为 0)
     *
     * # 线程语义
     * ArkTS 主线程调用 napi → C++ dlsym → 本函数, 运行在 ArkTS 主线程;
     * CompletableDeferred.complete 线程安全, 可安全唤醒 JS 引擎线程的 runBlocking。
     *
     * @param requestId 请求 ID (与 invokeImageSync 生成的 requestId 对应)
     * @param result ArkTS 返回的控制面结果 JSON 字符串 (UTF-8 C 字符串)
     * @param bytes ArkTS 返回的数据面裸字节指针 (ArrayBuffer data, 无字节面时为 null)
     * @param bytesLen 数据面字节长度 (无字节面时为 0)
     */
    @CName("legado_image_callback")
    fun imageCallback(
        requestId: Long,
        result: CPointer<ByteVar>,
        bytes: COpaquePointer?,
        bytesLen: ULong
    ) {
        OhosNativeBridge.onImageResult(
            requestId,
            result.toKString(),
            bytes.toByteArrayOrNull(bytesLen)
        )
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

    // ===== Crypto tsfn 注入 + ArkTS → Kotlin 结果回调 (KP8+, 同 Image 模式) =====

    /**
     * 注入 crypto dispatch 函数指针 (由 legado_napi.cpp RegisterCryptoCallback 调用)。
     *
     * 同 [registerImageFn], 注入到 [OhosNativeBridge.cryptoTsfn],
     * 使 KMP [OhosNativeBridge.invokeCryptoSync] 能跨线程 dispatch crypto 操作请求到 ArkTS。
     * ArkTS CryptoBridgeHandler 处理完成后通过 [cryptoCallback] (@CName legado_crypto_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_crypto_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_crypto_fn")
    fun registerCryptoFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerCryptoFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin crypto 操作结果回调 (由 legado_napi.cpp CryptoCallback 调用)。
     *
     * 调用链: `ArkTS cryptoCallback(requestId, resultJson)` → napi (legado_napi.cpp CryptoCallback) →
     * dlsym("legado_crypto_callback") → 本函数 → [OhosNativeBridge.onCryptoResult] →
     * 唤醒 [OhosNativeBridge.invokeCryptoSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeCryptoSync] 生成的 requestId 一致
     * - [result]: UTF-8 C 字符串, JSON 格式:
     *   - encrypt/decrypt/sign 成功: `{ ok: true, data: "<base64>" }`
     *   - verify 成功: `{ ok: true, result: true/false }`
     *   - 失败: `{ ok: false, error: "<string>" }`
     *
     * @param requestId 请求 ID (与 invokeCryptoSync 生成的 requestId 对应)
     * @param result ArkTS 返回的结果 JSON 字符串 (UTF-8 C 字符串)
     */
    @CName("legado_crypto_callback")
    fun cryptoCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onCryptoResult(requestId, result.toKString())
    }

    // ===== Http tsfn 注入 + ArkTS → Kotlin 结果回调 (KP8+, 同 Image/Crypto 模式) =====

    /**
     * 注入 http dispatch 函数指针 (由 legado_napi.cpp RegisterHttpCallback 调用)。
     *
     * 同 [registerImageFn]/[registerCryptoFn], 注入到 [OhosNativeBridge.httpTsfn],
     * 使 KMP [OhosNativeBridge.invokeHttpSync] 能跨线程 dispatch HTTP 请求到 ArkTS。
     * ArkTS HttpBridgeHandler 处理完成后通过 [httpCallback] (@CName legado_http_callback) 回送结果。
     *
     * # 混合协议 (JSON + 裸字节, 同 WebView 桥思路)
     * [dispatch] 接收控制面 JSON (requestId/action/payload) + 数据面裸字节 (请求 body)。
     * 字节经 usePinned 零拷贝直传 C++ (ohos_http_dispatch 内 malloc 深拷贝, 因 tsfn 异步投递
     * 原指针生命周期不可控), C++ 用 napi_create_external_arraybuffer 包成 ArrayBuffer 传给 ArkTS,
     * 不经 base64 (避免 33% 体积膨胀 + 双端编解码拷贝), 二进制保真。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_http_dispatch`),
     *   类型 `void(*)(const char*, const void*, size_t)` (JSON + 裸字节 + 长度)
     */
    @CName("legado_register_http_fn")
    fun registerHttpFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>, COpaquePointer?, ULong) -> Unit>>) {
        OhosNativeBridge.registerHttpFn { json, bytes ->
            dispatchBinary(dispatch, json, bytes)
        }
    }

    /**
     * ArkTS → Kotlin HTTP 请求结果回调 (由 legado_napi.cpp HttpCallback 调用, 混合协议)。
     *
     * 调用链: `ArkTS httpCallback(requestId, resultJson, body?)` → napi (legado_napi.cpp HttpCallback) →
     * dlsym("legado_http_callback") → 本函数 → [OhosNativeBridge.onHttpResult] →
     * 唤醒 [OhosNativeBridge.invokeHttpSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeHttpSync] 生成的 requestId 一致
     * - [result]: 控制面结果 JSON (HttpResponsePayload, 不含 body):
     *   - 成功: `{ ok: true, code: <int>, message: "<string>", headers: [...] }`
     *   - 失败: `{ ok: false, error: "<string>" }`
     * - [bytes]: 数据面响应 body 裸字节 (C++ 用 napi_get_arraybuffer_info 零拷贝取
     *   ArrayBuffer 数据指针; 无 body 时为 null)
     * - [bytesLen]: [bytes] 长度 (无 body 时为 0)
     *
     * @param requestId 请求 ID (与 invokeHttpSync 生成的 requestId 对应)
     * @param result ArkTS 返回的控制面结果 JSON 字符串 (UTF-8 C 字符串)
     * @param bytes ArkTS 返回的数据面响应 body 指针 (ArrayBuffer data, 无 body 时为 null)
     * @param bytesLen 数据面字节长度 (无 body 时为 0)
     */
    @CName("legado_http_callback")
    fun httpCallback(
        requestId: Long,
        result: CPointer<ByteVar>,
        bytes: COpaquePointer?,
        bytesLen: ULong
    ) {
        OhosNativeBridge.onHttpResult(
            requestId,
            result.toKString(),
            bytes.toByteArrayOrNull(bytesLen)
        )
    }

    // ===== WebView tsfn 注入 + ArkTS → Kotlin 结果回调 (同 Http/Image 模式) =====

    /**
     * 注入 webView dispatch 函数指针 (由 legado_napi.cpp RegisterWebViewCallback 调用)。
     *
     * 同 [registerHttpFn], 注入到 [OhosNativeBridge.webViewTsfn],
     * 使 KMP [OhosNativeBridge.invokeWebViewSync] 能跨线程 dispatch 后台 WebView 请求到 ArkTS。
     * ArkTS WebViewBridgeHandler 用隐藏 Web 组件加载页面 + 执行 JS 取源码,
     * 完成后通过 [webViewCallback] (@CName legado_webview_callback) 回送结果。
     *
     * # 混合协议 (双字符串参数)
     * [dispatch] 接收两个 C 字符串: 控制面 JSON (requestId/action/payload) + 数据面裸 html。
     * html 不经 JSON 转义, 避免大段 HTML 的转义膨胀与双端编解码拷贝 (见 OhosNativeBridge
     * [OhosNativeBridge.OhosWebViewTsfnCallback] 注释)。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_webview_dispatch`), 类型 `void(*)(const char*, const char*)`
     */
    @CName("legado_register_webview_fn")
    fun registerWebViewFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>, CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerWebViewFn { json, html ->
            memScoped {
                dispatch(json.cstr.getPointer(this), html.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin webView 后台抓取结果回调 (由 legado_napi.cpp WebViewCallback 调用, 混合协议)。
     *
     * 调用链: `ArkTS webViewCallback(requestId, resultJson, bodyRaw)` → napi (legado_napi.cpp WebViewCallback) →
     * dlsym("legado_webview_callback") → 本函数 → [OhosNativeBridge.onWebViewResult] →
     * 唤醒 [OhosNativeBridge.invokeWebViewSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeWebViewSync] 生成的 requestId 一致
     * - [result]: 控制面结果 JSON (WebViewResult 格式, 不含 body):
     *   - 成功: `{ ok: true, url, cookie? }`
     *   - 失败: `{ ok: false, error }`
     * - [body]: 数据面裸源码/命中 URL (不经 JSON 转义, UTF-8 C 字符串, 失败时为空串)
     *
     * @param requestId 请求 ID (与 invokeWebViewSync 生成的 requestId 对应)
     * @param result ArkTS 返回的控制面结果 JSON 字符串 (UTF-8 C 字符串)
     * @param body ArkTS 返回的数据面裸源码字符串 (UTF-8 C 字符串)
     */
    @CName("legado_webview_callback")
    fun webViewCallback(requestId: Long, result: CPointer<ByteVar>, body: CPointer<ByteVar>) {
        OhosNativeBridge.onWebViewResult(requestId, result.toKString(), body.toKString())
    }

    // ===== Markdown 查看器 tsfn 注入 + ArkTS → Kotlin 事件回调 (同 Toast/Media 模式) =====

    /**
     * 注入 markdown dispatch 函数指针 (由 legado_napi.cpp RegisterMarkdownCallback 调用)。
     *
     * 同 [registerToastFn], 注入到 [OhosNativeBridge.markdownTsfn],
     * 使 KMP [OhosNativeBridge.sendMarkdown] 能跨线程 dispatch 渲染请求到 ArkTS。
     * ArkTS MarkdownBridgeHandler 经 Index.ets 挂载的 Web 组件 runJavaScript 注入
     * renderMarkdown (marked.parse + hljs.highlightAll + github-markdown 亮/暗主题) 渲染。
     *
     * 调用链: `ArkTS registerMarkdownCallback(cb)` → C++ 创建 napi_threadsafe_function 包装 cb →
     * C++ dlsym("legado_register_markdown_fn") → 本函数 → 把 [dispatch] 包成 (String) -> Unit lambda →
     * [OhosNativeBridge.registerMarkdownFn]。之后 KMP MarkdownContent 组合时,
     * `markdownTsfn?.invoke(json)` → 本 lambda → `dispatch(json.cstr)` →
     * C++ ohos_markdown_dispatch → napi_call_threadsafe_function → MarkdownCallJs (ArkTS 主线程) → ArkTS cb(json)。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_markdown_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_markdown_fn")
    fun registerMarkdownFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerMarkdownFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin markdown 查看器事件回调 (由 legado_napi.cpp MarkdownEvent 调用)。
     *
     * 调用链: `ArkTS markdownEvent(eventJson)` → napi (legado_napi.cpp MarkdownEvent) →
     * dlsym("legado_markdown_event") → 本函数 → [OhosNativeBridge.onMarkdownEvent] →
     * 转发给 [OhosNativeBridge.MarkdownEventListener] (ohosMain 启动期注册, 走系统浏览器)。
     *
     * # 事件类型
     * - openLink: viewer 页面内 <a> 链接点击 (url 字段携带链接), 系统浏览器打开
     *
     * @param event 事件 JSON 字符串 (UTF-8 C 字符串, 如 `{"action":"openLink","url":"https://..."}`)
     */
    @CName("legado_markdown_event")
    fun markdownEvent(event: CPointer<ByteVar>) {
        OhosNativeBridge.onMarkdownEvent(event.toKString())
    }

    // ===== 统一平台事件 ArkTS → Kotlin 回调 (HTTP 下载进度 + 应用生命周期, 同 mediaEvent 模式) =====

    /**
     * ArkTS → Kotlin 统一平台事件回调 (由 legado_napi.cpp PlatformEvent 调用)。
     *
     * 调用链: `ArkTS platformEvent(eventJson)` → napi (legado_napi.cpp PlatformEvent) →
     * dlsym("legado_platform_event") → 本函数 → [OhosNativeBridge.onPlatformEvent] →
     * OhosPlatformEventChannel 按 type 分发到 [OhosDownloadProgressEvents] / [OhosAppLifecycle]。
     *
     * 两个事件源 (HTTP 下载进度 / 应用生命周期) 共用一个通道与一个 @CName 符号,
     * 避免每个功能各建一套 C++ tsfn / napi 入口。
     *
     * @param event 事件 JSON (UTF-8 C 字符串):
     *   - HTTP 下载进度: `{"type":"httpProgress","url":"...","bytesReceived":123,"totalBytes":456,"isComplete":false}`
     *   - 应用生命周期: `{"type":"lifecycle","event":"onForeground"|"onBackground"}`
     */
    @CName("legado_platform_event")
    fun platformEvent(event: CPointer<ByteVar>) {
        OhosNativeBridge.onPlatformEvent(event.toKString())
    }

    /**
     * 构建 Markdown 查看器完整 HTML (ArkTS → Kotlin 同步调用, 无参返回字符串)。
     *
     * 鸿蒙端 composeResources 打包进 liblegado_shared.so 内嵌资源, Web 组件无法直接按路径访问;
     * 本函数运行时从 composeResources 直读模板 + marked/highlight/github-markdown 亮暗 css,
     * 内联拼成完整 HTML (见 [OhosMarkdownViewer]), 供 ArkTS 侧 `WebviewController.loadData` 加载。
     * 单一数据源 (composeResources), 不产生任何平台端资源副本。
     *
     * # 线程
     * 内部纯内存读取 (不涉及 tsfn 回调), ArkTS 主线程同步调用安全; 结果进程内缓存。
     *
     * @return UTF-8 C 字符串, 完整 viewer HTML; 读取失败时为空串 (ArkTS 侧跳过 loadData)
     */
    @CName("legado_build_markdown_viewer")
    fun buildMarkdownViewerHtml(): CPointer<ByteVar> =
        allocateCString(OhosMarkdownViewer.buildHtml())

    // ===== OpenUrl tsfn 注入 (KP8+, 同 Toast 模式, fire-and-forget dispatch) =====

    /**
     * 注入 openUrl dispatch 函数指针 (由 legado_napi.cpp RegisterOpenUrlCallback 调用)。
     *
     * 同 [registerToastFn], 注入到 [OhosNativeBridge.openUrlTsfn],
     * 使 KMP [OhosNativeBridge.openUrl] 能跨线程 dispatch URL 打开请求到 ArkTS。
     * ArkTS 侧 SystemBridgeHandler.handleOpenUrl 调 `context.startAbility(Want.uri=url)` 打开 URL
     * (KMP 无 ArkTS API 访问能力, 需 tsfn 桥接; 与 showToast 走相同 fire-and-forget 模式)。
     *
     * 调用链: `ArkTS registerOpenUrlCallback(cb)` → C++ 创建 napi_threadsafe_function 包装 cb →
     * C++ dlsym("legado_register_open_url_fn") → 本函数 → 把 [dispatch] 包成 (String) -> Unit lambda →
     * [OhosNativeBridge.registerOpenUrlFn]。之后 KMP [OhosNativeBridge.openUrl] 调用时,
     * `openUrlTsfn?.invoke(json)` → 本 lambda → `dispatch(json.cstr)` →
     * C++ ohos_open_url_dispatch → napi_call_threadsafe_function → OpenUrlCallJs (ArkTS 主线程) → ArkTS cb(json)。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_open_url_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_open_url_fn")
    fun registerOpenUrlFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerOpenUrlFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    // ===== TextAction tsfn 注入 + ArkTS → KMP 菜单动作回调 (阅读页文本操作浮动菜单) =====

    /**
     * 注入 textAction dispatch 函数指针 (由 legado_napi.cpp RegisterTextActionCallback 调用)。
     *
     * 注入到 [OhosNativeBridge.textActionTsfn], 使 KMP [OhosNativeBridge.showTextActionMenu]
     * 能跨线程 dispatch 菜单请求到 ArkTS (Index.ets 叠层浮动菜单)。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_text_action_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_text_action_fn")
    fun registerTextActionFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerTextActionFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → KMP 文本操作菜单动作回调 (由 legado_napi.cpp TextActionCallback 调用)。
     *
     * @param requestId 请求 ID (当前未用, 保留与其它回调一致的签名)
     * @param result 动作 JSON: `{ action: "replace|copy|bookmark|aloud|dict|search_content|browser|share|view|refresh|save|__dismiss", text: "...", src?: "..." }`
     *   (`view`/`save` 由 ArkTS 本地处理不回送, 回送的主要是 `refresh` (图片刷新);
     *   `src` 为图片 src (图片菜单动作携带, 文本菜单为空);
     *   `__dismiss` = 菜单收起, 取消页内选择, 对标原版 onMenuActionFinally)
     */
    @CName("legado_text_action_callback")
    fun textActionCallback(requestId: Long, result: CPointer<ByteVar>) {
        val json = result.toKString()
        val payload = runCatching {
            Json.parseToJsonElement(json).jsonObject
        }.getOrNull() ?: return
        val action = payload["action"]?.jsonPrimitive?.contentOrNull ?: return
        val text = payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val src = payload["src"]?.jsonPrimitive?.contentOrNull.orEmpty()
        OhosNativeBridge.onTextActionResult(action, text, src)
    }

    // ===== FilePicker tsfn 注入 + ArkTS → Kotlin 结果回调 (KP8+, 同 Image/Crypto/Http 模式) =====

    /**
     * 注入 filePicker dispatch 函数指针 (由 legado_napi.cpp RegisterFilePickerCallback 调用)。
     *
     * 同 [registerImageFn]/[registerCryptoFn]/[registerHttpFn], 注入到 [OhosNativeBridge.filePickerTsfn],
     * 使 KMP [OhosNativeBridge.invokeFilePickerSync] 能跨线程 dispatch 文件选择请求到 ArkTS。
     * ArkTS FilePickerBridgeHandler 处理完成后通过 [filePickerCallback] (@CName legado_file_picker_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_file_picker_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_file_picker_fn")
    fun registerFilePickerFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerFilePickerFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin filePicker 操作结果回调 (由 legado_napi.cpp FilePickerCallback 调用)。
     *
     * 调用链: `ArkTS filePickerCallback(requestId, resultJson)` → napi (legado_napi.cpp FilePickerCallback) →
     * dlsym("legado_file_picker_callback") → 本函数 → [OhosNativeBridge.onFilePickerResult] →
     * 唤醒 [OhosNativeBridge.invokeFilePickerSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeFilePickerSync] 生成的 requestId 一致
     * - [result]: UTF-8 C 字符串, JSON 格式:
     *   - pickDocuments 成功: `{ ok: true, uris: ["<uri1>", "<uri2>", ...] }`
     *   - pickDocuments 用户取消: `{ ok: true, cancelled: true }`
     *   - pickDocumentContent 成功: `{ ok: true, data: "<base64>" }`
     *   - 失败: `{ ok: false, error: "<string>" }`
     *
     * @param requestId 请求 ID (与 invokeFilePickerSync 生成的 requestId 对应)
     * @param result ArkTS 返回的结果 JSON 字符串 (UTF-8 C 字符串)
     */
    @CName("legado_file_picker_callback")
    fun filePickerCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onFilePickerResult(requestId, result.toKString())
    }

    // ===== Pasteboard tsfn 注入 + ArkTS → Kotlin 结果回调 (同 FilePicker 模式) =====

    /**
     * 注入 pasteboard dispatch 函数指针 (由 legado_napi.cpp RegisterPasteboardCallback 调用)。
     *
     * 注入到 [OhosNativeBridge.pasteboardTsfn], 使 KMP [OhosNativeBridge.invokePasteboardSync]
     * 能跨线程 dispatch 剪贴板读写请求到 ArkTS。ArkTS PasteboardBridgeHandler 处理完成后
     * 通过 [pasteboardCallback] (@CName legado_pasteboard_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_pasteboard_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_pasteboard_fn")
    fun registerPasteboardFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerPasteboardFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin pasteboard 操作结果回调 (由 legado_napi.cpp PasteboardCallback 调用)。
     *
     * @param requestId 请求 ID (与 invokePasteboardSync 生成的 requestId 对应)
     * @param result 结果 JSON: read 成功 `{ ok: true, text: "..." }`, write 成功 `{ ok: true }`,
     *   失败 `{ ok: false, error: "..." }`
     */
    @CName("legado_pasteboard_callback")
    fun pasteboardCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onPasteboardResult(requestId, result.toKString())
    }

    // ===== Network tsfn 注入 + ArkTS → Kotlin 结果回调 (同 Pasteboard 模式) =====

    /**
     * 注入 network dispatch 函数指针 (由 legado_napi.cpp RegisterNetworkCallback 调用)。
     *
     * 注入到 [OhosNativeBridge.networkTsfn], 使 KMP [OhosNativeBridge.invokeNetworkSync]
     * 能跨线程 dispatch 网络状态查询到 ArkTS。ArkTS NetworkBridgeHandler
     * (@ohos.net.connection getDefaultNetSync + getConnectionPropertiesSync) 查询完成后
     * 通过 [networkCallback] (@CName legado_network_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_network_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_network_fn")
    fun registerNetworkFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerNetworkFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin network 查询结果回调 (由 legado_napi.cpp NetworkCallback 调用)。
     *
     * @param requestId 请求 ID (与 invokeNetworkSync 生成的 requestId 对应)
     * @param result 结果 JSON: 成功 `{ ok: true, network: boolean, wifi: boolean }`,
     *   失败 `{ ok: false, error: "..." }`
     */
    @CName("legado_network_callback")
    fun networkCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onNetworkResult(requestId, result.toKString())
    }

    // ===== TextCodec tsfn 注入 + ArkTS → Kotlin 结果回调 (同 Crypto/Pasteboard 模式) =====

    /**
     * 注入 textCodec dispatch 函数指针 (由 legado_napi.cpp RegisterTextCodecCallback 调用)。
     *
     * 注入到 [OhosNativeBridge.textCodecTsfn], 使 KMP [OhosNativeBridge.invokeTextCodecSync]
     * 能跨线程 dispatch GB18030/Big5 编解码请求到 ArkTS (TXT 分章解析用)。
     * ArkTS TextCodecBridgeHandler (@ohos.util TextDecoder/TextEncoder) 处理完成后
     * 通过 [textCodecCallback] (@CName legado_text_codec_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_text_codec_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_text_codec_fn")
    fun registerTextCodecFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerTextCodecFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin textCodec 操作结果回调 (由 legado_napi.cpp TextCodecCallback 调用)。
     *
     * @param requestId 请求 ID (与 invokeTextCodecSync 生成的 requestId 对应)
     * @param result 结果 JSON: decode 成功 `{ ok: true, text: "..." }`,
     *   encode 成功 `{ ok: true, data: "<base64>" }`, 失败 `{ ok: false, error: "..." }`
     */
    @CName("legado_text_codec_callback")
    fun textCodecCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onTextCodecResult(requestId, result.toKString())
    }

    // ===== Window / Battery tsfn 注入 (此前 OhosNativeBridge 已备桥, 缺 C ABI 入口) =====

    /**
     * 注入 window dispatch 函数指针 (由 legado_napi.cpp RegisterWindowCallback 调用)。
     *
     * 未注入时 [OhosNativeBridge.sendWindowCommand] 恒丢弃命令, 全屏/常亮/方向/系统栏全部失效。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_window_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_window_fn")
    fun registerWindowFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerWindowFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * 注入 battery dispatch 函数指针 (由 legado_napi.cpp RegisterBatteryCallback 调用)。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_battery_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_battery_fn")
    fun registerBatteryFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerBatteryFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin 电量查询结果回调 (由 legado_napi.cpp BatteryCallback 调用)。
     *
     * @param result `{ ok: true, level: 85 }` 或 `{ ok: false, error: "..." }`
     */
    @CName("legado_battery_callback")
    fun batteryCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onBatteryResult(requestId, result.toKString())
    }

    // ===== Share / Keyboard / Permission tsfn 注入 (PlatformServices 能力补齐) =====

    /**
     * 注入 share dispatch 函数指针 (由 legado_napi.cpp RegisterShareCallback 调用)。
     *
     * 未注入时 [io.legado.app.OhosPlatformServices] 的分享降级为写剪贴板 + toast。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_share_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_share_fn")
    fun registerShareFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerShareFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * 注入 keyboard dispatch 函数指针 (由 legado_napi.cpp RegisterKeyboardCallback 调用)。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_keyboard_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_keyboard_fn")
    fun registerKeyboardFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerKeyboardFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * 注入 permission dispatch 函数指针 (由 legado_napi.cpp RegisterPermissionCallback 调用)。
     *
     * ArkTS 处理完成后通过 [permissionCallback] (@CName legado_permission_callback) 回送结果。
     *
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_permission_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_permission_fn")
    fun registerPermissionFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerPermissionFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin 权限查询/申请结果回调 (由 legado_napi.cpp PermissionCallback 调用)。
     *
     * @param result `{ ok: true, granted: true }` 或 `{ ok: false, error: "..." }`
     */
    @CName("legado_permission_callback")
    fun permissionCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onPermissionResult(requestId, result.toKString())
    }

    // ===== 图片下载管线导出 (ArkTS 保存到相册复用, 带书源 header 防盗链) =====

    /**
     * 下载图片字节并返回 base64 (ArkTS 保存到相册用, 复用 shared 下载管线)。
     *
     * # 与 ArkTS 裸 @ohos.net.http 下载的差异
     * ArkTS 侧拿不到书源 header (防盗链 Referer/Cookie/解密 JS 在 K/N 侧),
     * 裸下载防盗链图片会失败。本函数复用 [io.legado.app.help.image.ohosDownloadImageBytes]
     * (AnalyzeUrlCore 带书源 header/cookie/charset/JS + ImageUtils.decode 解密), 返回 base64
     * 字符串供 ArkTS 侧解码后写入相册 (photoAccessHelper 仅 ArkTS 可用)。
     *
     * # 线程约束
     * 内部 [runBlocking] 把 suspend 下载转同步, 且下载内部走 HTTP 桥 (invokeHttpSync → tsfn
     * → ArkTS 主线程处理回调)。调用方必须在 **非主线程** (ArkTS TaskPool/Worker) 调用,
     * 否则主线程被 runBlocking 阻塞, tsfn 回调无法处理 → 死锁超时。
     *
     * # 失败兜底
     * 未注册 provider / 下载失败 / 解密失败时返回空字符串 (ArkTS 侧回退裸下载或 toast)。
     *
     * @param url 图片地址 (UTF-8 C 字符串)
     * @return UTF-8 C 字符串, base64 编码的图片字节; 失败时为空串
     */
    @CName("legado_download_image_bytes")
    fun downloadImageBytes(url: CPointer<ByteVar>): CPointer<ByteVar> {
        val urlStr = url.toKString()
        val result = runCatching {
            // 当前阅读书的书源 (防盗链 header 来源); 无活动阅读书时按无书源裸 GET 降级
            val book = ActiveReadBookRegistry.current?.bookValue
            val source = book?.let {
                runBlocking { AppDbProviders.get().bookSourceDao.getBookSource(it.origin) }
            }
            val bytes = runBlocking {
                ohosDownloadImageBytes(urlStr, book, source)
            } ?: return@runCatching ""
            Base64.encode(bytes)
        }.getOrNull() ?: ""
        return allocateCString(result)
    }

    // ===== 外部启动请求投递 (ArkTS → Kotlin, 同 legado_handle_deep_link 模式) =====

    /**
     * 投递外部启动 Want (ArkTS EntryAbility.onCreate/onNewWant 调用)。
     *
     * 与 [handleDeepLink] 的分工: 前者只认 legado:// / yuedu:// 导入链接并弹勾选对话框;
     * 本函数覆盖完整的 Want → [io.legado.app.ui.root.LaunchRequest] 映射 (文件关联 / 通知
     * route / 处理文本 / 其他 scheme deep link), 对齐 app 端 `Intent.toLaunchRequest`。
     *
     * @param uri Want.uri (UTF-8 C 字符串); 通知点击可传 `route:<AppRoute 序列化串>`
     * @return 1=已识别并投递到 LaunchRequestBus; 0=无法识别 (ArkTS 可自行处理)
     */
    @CName("legado_handle_launch_request")
    fun handleLaunchRequest(uri: CPointer<ByteVar>): Int {
        val request = OhosLaunchRequests.parse(uri.toKString()) ?: return 0
        return if (OhosLaunchRequests.post(request)) 1 else 0
    }
}
