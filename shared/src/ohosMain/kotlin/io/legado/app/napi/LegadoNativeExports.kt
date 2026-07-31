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
import io.legado.app.ui.association.LegadoDeepLinkHandler
import io.legado.app.ui.book.manga.OhosMangaImageExtractor
import io.legado.app.ui.explore.ExploreViewModelShared
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // 发现页 VM 共享核心 (topSource/deleteSource 转发到此, scope=应用级 IO 协程)
    private val exploreVmShared = ExploreViewModelShared(
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    // 漫画图片 URL 提取器 (复用 commonMain MangaImageExtractorShared 纯字符串扫描)
    private val mangaExtractor = OhosMangaImageExtractor()

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
        return json.cstr.getPointer(nativeHeap)
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

    // ===== 漫画 + 发现页函数 (KP5+ 新增) =====

    /**
     * 加载漫画章节图片 URL 列表 (返回 `{"images":["url1","url2",...]}` JSON)。
     *
     * 调用链 (对齐 Index.d.ts 注释 + MangaReaderViewModelShared.getManageChapter):
     * 1. `AppDbProviders.get().bookDao.getBook(bookUrl)` 取书籍
     * 2. `AppDbProviders.get().bookChapterDao.getChapterList(bookUrl)` 取章节列表
     * 3. `BookStorageProviders.get().getContent(book, chapter)` 读本地缓存正文
     * 4. `mangaExtractor.flowImages(chapter, content).distinctUntilChanged().toList()` 提取图片 URL
     *
     * 同步语义: 同 [loadChapter], 内部 [runBlocking] 转 suspend, napi 层应在 worker 线程调用。
     *
     * 失败兜底: 任何步骤异常 / 书籍不存在 / 章节越界时返回 `{"images":[]}`。
     *
     * @param bookUrl 书籍 URL (UTF-8 C 字符串)
     * @param chapterIndex 章节索引 (0-based)
     * @return UTF-8 C 字符串, JSON 格式 `{"images":["url1","url2",...]}`
     */
    @CName("legado_load_manga_chapter")
    fun loadMangaChapter(bookUrl: CPointer<ByteVar>, chapterIndex: Int): CPointer<ByteVar> {
        val bookUrlStr = bookUrl.toKString()
        val json = runCatching {
            runBlocking {
                val book = AppDbProviders.get().bookDao.getBook(bookUrlStr) ?: return@runBlocking
                val chapterList = AppDbProviders.get().bookChapterDao.getChapterList(bookUrlStr)
                val chapter = chapterList.getOrNull(chapterIndex) ?: return@runBlocking
                // 读本地缓存正文 (对齐 loadChapter), 由该 content 提取图片 URL
                val content = BookStorageProviders.get().getContent(book, chapter) ?: ""
                val images = mangaExtractor.flowImages(chapter, content)
                    .distinctUntilChanged()
                    .toList()
                buildJsonObject {
                    put("images", buildJsonArray { images.forEach { add(it) } })
                }.toString()
            }
        }.getOrNull() ?: buildJsonObject { put("images", buildJsonArray()) }.toString()
        return json.cstr.getPointer(nativeHeap)
    }

    /**
     * 获取发现源列表 (返回 JSON 数组字符串, 仅 enabledExplore=true 且 hasExploreUrl=1 的源)。
     *
     * 调用链: `AppDbProviders.get().bookSourceDao.flowExplore(true).first()` 取发现源列表,
     * 手动序列化 [io.legado.app.data.entities.BookSourcePart] 全字段 (该 data class 未标 @Serializable,
     * 与 chapterList 同模式用 [buildJsonArray] + [buildJsonObject] 拼 JSON, 避免给它加注解影响 Room)。
     *
     * 同步语义: 同 [bookshelfList], 内部 [runBlocking] 转 suspend, napi 层应在 worker 线程调用。
     *
     * 失败兜底: provider 未注册 / DAO 查询异常时返回 `"[]"` (空数组)。
     *
     * @return UTF-8 C 字符串, JSON 数组格式 `[{"bookSourceUrl":"...","bookSourceName":"...",...}, ...]`
     */
    @CName("legado_explore_list")
    fun exploreList(): CPointer<ByteVar> {
        val json = runCatching {
            runBlocking {
                val list = AppDbProviders.get().bookSourceDao.flowExplore(true).first()
                buildJsonArray {
                    list.forEach { src ->
                        add(buildJsonObject {
                            put("bookSourceUrl", src.bookSourceUrl)
                            put("bookSourceName", src.bookSourceName)
                            put("bookSourceGroup", src.bookSourceGroup)
                            put("customOrder", src.customOrder)
                            put("enabled", src.enabled)
                            put("enabledExplore", src.enabledExplore)
                            put("hasLoginUrl", src.hasLoginUrl)
                            put("lastUpdateTime", src.lastUpdateTime)
                            put("respondTime", src.respondTime)
                            put("weight", src.weight)
                            put("hasExploreUrl", src.hasExploreUrl)
                        })
                    }
                }.toString()
            }
        }.getOrNull() ?: "[]"
        return json.cstr.getPointer(nativeHeap)
    }

    /**
     * 打开发现源详情/书籍列表 (跳转 ExploreShow)。
     *
     * # stub no-op
     * 页面跳转属 ArkTS 路由范畴, Kotlin 侧无 ArkTS 路由 API 访问能力 (与 showToast 走 tsfn 不同,
     * 路由跳转需在 ArkTS 主线程同步执行, tsfn 异步 dispatch 反而带来时序问题)。
     * 本函数仅满足 napi 三件套契约, 实际导航应由 ArkTS 端直接调 router.pushUrl 完成。
     *
     * @param sourceUrl 书源 URL (UTF-8 C 字符串)
     * @param exploreUrl 发现分类 URL (UTF-8 C 字符串, 可为空)
     */
    @CName("legado_open_explore")
    fun openExplore(sourceUrl: CPointer<ByteVar>, exploreUrl: CPointer<ByteVar>) {
        // stub no-op: ArkTS 端应直接处理导航, 不经 napi 往返
    }

    /**
     * 编辑书源 (跳转 BookSourceEdit)。
     *
     * 同 [openExplore], stub no-op (页面跳转属 ArkTS 路由范畴)。
     *
     * @param sourceUrl 书源 URL (UTF-8 C 字符串)
     */
    @CName("legado_edit_explore_source")
    fun editExploreSource(sourceUrl: CPointer<ByteVar>) {
        // stub no-op: ArkTS 端应直接处理导航, 不经 napi 往返
    }

    /**
     * 置顶书源 (调 ExploreViewModelShared.topSource)。
     *
     * 调用链:
     * 1. `AppDbProviders.get().bookSourceDao.getBookSourcePart(sourceUrl)` 取 BookSourcePart
     * 2. `exploreVmShared.topSource(part)` → 内部 `scope.launch(Dispatchers.IO) { ... }`
     *    把 customOrder 改为 minOrder - 1 后 upOrder
     *
     * 同步语义: 仅步骤 1 同步 (runBlocking), 步骤 2 fire-and-forget (VM 内部 launch)。
     *
     * 失败兜底: provider 未注册 / 书源不存在 / 异常时静默 no-op (ArkTS 侧 loadSources 重载即可看到结果)。
     *
     * @param sourceUrl 书源 URL (UTF-8 C 字符串)
     */
    @CName("legado_top_explore_source")
    fun topExploreSource(sourceUrl: CPointer<ByteVar>) {
        val sourceUrlStr = sourceUrl.toKString()
        runCatching {
            runBlocking {
                val part = AppDbProviders.get().bookSourceDao.getBookSourcePart(sourceUrlStr)
                    ?: return@runBlocking
                exploreVmShared.topSource(part)
            }
        }
    }

    /**
     * 删除书源 (调 ExploreViewModelShared.deleteSource)。
     *
     * 调用链:
     * 1. `AppDbProviders.get().bookSourceDao.getBookSourcePart(sourceUrl)` 取 BookSourcePart
     * 2. `exploreVmShared.deleteSource(part)` → 内部 `scope.launch(Dispatchers.IO) { ... }`
     *    调 SourceHelp.deleteBookSource(part.bookSourceUrl)
     *
     * 同步语义: 同 [topExploreSource], 仅步骤 1 同步, 步骤 2 fire-and-forget。
     *
     * 失败兜底: provider 未注册 / 书源不存在 / 异常时静默 no-op。
     *
     * @param sourceUrl 书源 URL (UTF-8 C 字符串)
     */
    @CName("legado_delete_explore_source")
    fun deleteExploreSource(sourceUrl: CPointer<ByteVar>) {
        val sourceUrlStr = sourceUrl.toKString()
        runCatching {
            runBlocking {
                val part = AppDbProviders.get().bookSourceDao.getBookSourcePart(sourceUrlStr)
                    ?: return@runBlocking
                exploreVmShared.deleteSource(part)
            }
        }
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
     * @param dispatch C++ tsfn dispatch 入口 (`ohos_http_dispatch`), 类型 `void(*)(const char*)`
     */
    @CName("legado_register_http_fn")
    fun registerHttpFn(dispatch: CPointer<CFunction<(CPointer<ByteVar>) -> Unit>>) {
        OhosNativeBridge.registerHttpFn { json ->
            memScoped {
                dispatch(json.cstr.getPointer(this))
            }
        }
    }

    /**
     * ArkTS → Kotlin HTTP 请求结果回调 (由 legado_napi.cpp HttpCallback 调用)。
     *
     * 调用链: `ArkTS httpCallback(requestId, resultJson)` → napi (legado_napi.cpp HttpCallback) →
     * dlsym("legado_http_callback") → 本函数 → [OhosNativeBridge.onHttpResult] →
     * 唤醒 [OhosNativeBridge.invokeHttpSync] 中阻塞的 CompletableDeferred。
     *
     * # 跨语言传递
     * - [requestId]: 64 位整数, 与 [OhosNativeBridge.invokeHttpSync] 生成的 requestId 一致
     * - [result]: UTF-8 C 字符串, JSON 格式 (HttpResponsePayload):
     *   - 成功: `{ ok: true, code: <int>, message: "<string>", headers: [...], body: "<base64>" }`
     *   - 失败: `{ ok: false, error: "<string>" }`
     *
     * @param requestId 请求 ID (与 invokeHttpSync 生成的 requestId 对应)
     * @param result ArkTS 返回的结果 JSON 字符串 (UTF-8 C 字符串)
     */
    @CName("legado_http_callback")
    fun httpCallback(requestId: Long, result: CPointer<ByteVar>) {
        OhosNativeBridge.onHttpResult(requestId, result.toKString())
    }

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
     * 未注入时 [OhosNativeBridge.sendWindowCommand] 恒降级 println, 全屏/常亮/方向/系统栏全部失效。
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
