package io.legado.app.ui.association

import kotlinx.atomicfu.atomic
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.OldRssSource
import io.legado.app.data.entities.toBookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.readImportPayload
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.parseToJsonElementLenient
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 导入书源 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `ImportBookSourceViewModel(app) : BaseViewModel(app)`: 4 个方法
 * (importSelect/importSource/importFromJson/comparisonSource) + private importSourceUrl,
 * 仅依赖 DAO + 协程 + okHttpClient + SourceHelp + ContentProcessor + GSON,
 * 可下沉多端复用。DAO 走 [AppDbProviders.get].bookSourceDao; `execute{...}` 链式回调
 * 下沉为直接调 [Coroutine.async] (业务 IO / 回调 mainDispatcher, 行为等价)。
 *
 * Android 专属依赖替换: file:// 载荷读取由本类入口 importSource 内部处理 (读文本后递归);
 * okHttpClient → [OkHttpClientProviders.get]; AppConfig.importKeepXxx →
 * [AppConfigProviders.get]; ContentProcessor.upReplaceRules() → [ContentProcessorProviders.get];
 * R.string 文案 → 直接抛 `NoStackTraceException("格式不对")`;
 * MutableLiveData → [MutableSharedFlow] (replay=1)。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), app 端持有本类实例,
 * errorLiveData/successLiveData 在 init 块桥接 [errorState]/[successState], 列表/分组
 * 状态直接 getter 转发 (同实例引用)。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
private const val CACHE_INTERRUPTED_WARNING = "书源已提交，缓存维护未完成，可重试缓存维护"

class ImportBookSourceViewModelShared(
    val scope: CoroutineScope,
) {
    /** 提交态归 VM: 对话框重建/重组后也不能重复提交同一批。 */
    private val submitSession = ImportSubmitSession()

    /** 提交态 (Writing/Committed/Failed) 归 VM: 重建对话框仍可读。 */
    val submitState: StateFlow<ImportSubmitState> = submitSession.state

    /** 提交瞬间冻结的负载: IO 阶段不再读可变列表/分组/prefs。 */
    private data class FrozenSubmit(
        val checked: List<Boolean>,
        val sources: List<BookSource>,
        val checkSources: List<BookSourcePart?>,
        val groupName: String?,
        val isAddGroup: Boolean,
        val keepName: Boolean,
        val keepGroup: Boolean,
        val keepEnable: Boolean,
    )

    private var frozenSubmit: FrozenSubmit? = null

    fun beginSubmit(checked: List<Boolean>): Boolean {
        if (!submitSession.begin()) return false
        val config = AppConfigProviders.get()
        frozenSubmit = FrozenSubmit(
            checked = checked.toList(),
            sources = allSources.map { it.copy() },
            checkSources = this.checkSources.map { it?.copy() },
            groupName = groupName,
            isAddGroup = isAddGroup,
            keepName = config.importKeepName,
            keepGroup = config.importKeepGroup,
            keepEnable = config.importKeepEnable,
        )
        return true
    }

    fun endSubmit() {
        submitSession.end()
        frozenSubmit = null
    }

    fun recordSubmitResult(result: ImportSubmitResult) = submitSession.record(result)


    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /** 是否将分组追加到原有分组 (false=覆盖), 由 app 端 UI 设置。 */
    var isAddGroup = false

    /** 自定义分组名 (null/空 表示不修改分组), 由 app 端 UI 设置。 */
    var groupName: String? = null

    /**
     * 事件流工厂: replay=1 + DROP_OLDEST, 语义对齐 LiveData.postValue。
     *
     * 不能用 StateFlow: 按值去重会吞掉重复投递 (同一个错误串重试后再次失败),
     * 导入弹窗的加载态就永远停在转圈。
     */
    private fun <T> signalFlow() = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 导入错误信息流 (对照原 `errorLiveData: MutableLiveData<String>`)。
     *
     * 事件流 (replay=1): 每次失败都投递, 重复的相同错误串也不会被吞。
     * 值格式 `"ImportError:${localizedMessage}"`, 与 app 端原 `errorLiveData.postValue(...)` 一致。
     */
    private val _errorState = signalFlow<String>()
    val errorState: SharedFlow<String> = _errorState.asSharedFlow()

    /**
     * 导入成功信号流 (对照原 `successLiveData: MutableLiveData<Int>`)。
     *
     * 值为 allSources.size (导入的书源总数), 0 表示解析无结果。
     * 事件流 (replay=1): 每次解析完成都投递, 数量与上次相同也不会被吞。
     */
    private val _successState = signalFlow<Int>()
    val successState: SharedFlow<Int> = _successState.asSharedFlow()

    /** 解析出的待导入书源列表 (importFromJson 累积, comparisonSource 比对, importSelect 写入)。 */
    val allSources = arrayListOf<BookSource>()

    /** 已有书源的部分字段 (用于 keepName/keepGroup/keepEnable/customOrder 还原), null=新增。 */
    val checkSources = arrayListOf<BookSourcePart?>()

    /** 解析时算出的默认勾选 (默认新增/更新都选中)。勾选状态本身归 UI 层, 这里只提供初值。 */
    val defaultChecked = arrayListOf<Boolean>()

    /** 标记对应位置书源是否为新增 (comparisonSource 设置, app 端 selectNew 用)。 */
    val newSourceStatus = arrayListOf<Boolean>()

    /** 标记对应位置书源是否为更新 (comparisonSource 设置, app 端 selectUpdate 用)。 */
    val updateSourceStatus = arrayListOf<Boolean>()

    /**
     * 导入选中的书源, 写入成功返回, 失败抛异常 (确认协议的唯一写入入口, 区分成功/错误)。
     *
     * # 实现细节保持
     *
     * - 取 [groupName] trim, 读 AppConfigProviders 的 importKeepName/Group/Enable 三个开关;
     * - 遍历 [checked], 选中的项走 keepName/Group/Enable/customOrder 还原逻辑
     *   (与原版 `checkSources[index]?.let { ... }` 完全一致);
     * - 若 [groupName] 非空: isAddGroup=true 时追加到原分组 (linkedSetOf 去重),
     *   isAddGroup=false 时覆盖 (与原版原逻辑完全一致, 不"偷懒"分离分支);
     * - `appDb.bookSourceDao.insert(*selectSource.toTypedArray())` 批量写入;
     * - 必要的全局排序维护 ([SourceHelp.adjustSortNumber]) 与插入在**同一事务**内完成
     *   (复用 [AppDbAccessor.runInTransactionSuspending]): 排序失败不会留下"已插入但报失败";
     * - 事务提交后才刷新源缓存与替换规则缓存, 并产生完成结果。
     *
     * 业务在 IO 上执行。
     */
    suspend fun importSelectAwait(checked: List<Boolean>) = try {
    withContext(IoDispatcher) {
        val frozen = frozenSubmit
        val checkedList = frozen?.checked ?: checked
        val sources = frozen?.sources ?: allSources
        val checkSources = frozen?.checkSources ?: this@ImportBookSourceViewModelShared.checkSources
        val group = (if (frozen != null) frozen.groupName else groupName)?.trim()
        val keepName = if (frozen != null) frozen.keepName else AppConfigProviders.get().importKeepName
        val keepGroup = if (frozen != null) frozen.keepGroup else AppConfigProviders.get().importKeepGroup
        val keepEnable = if (frozen != null) frozen.keepEnable else AppConfigProviders.get().importKeepEnable
        val selectSource = arrayListOf<BookSource>()
        checkedList.forEachIndexed { index, b ->
            if (b) {
                val source = sources[index]
                checkSources[index]?.let {
                    if (keepName) source.bookSourceName = it.bookSourceName
                    if (keepGroup) source.bookSourceGroup = it.bookSourceGroup
                    if (keepEnable) {
                        source.enabled = it.enabled
                        source.enabledExplore = it.enabledExplore
                    }
                    source.customOrder = it.customOrder
                }
                if (!group.isNullOrEmpty()) {
                    source.bookSourceGroup = if (frozen?.let { it.isAddGroup } ?: isAddGroup) {
                        val groups = linkedSetOf<String>()
                        source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                            groups.addAll(it)
                        }
                        groups.add(group)
                        groups.joinToString(",")
                    } else {
                        group
                    }
                }
                selectSource.add(source)
            }
        }
        appDb.runInTransactionSuspending(
            // END 成功后、仍在 useWriterConnection 块内发布真实提交结果
            // 先标“已提交、缓存未完成”: 事务后若在返回/刷新前被取消, 仍是可重试的 Committed
            onCommitted = { submitSession.committed(selectSource.size, cacheRunning = true) },
        ) {
            appDb.bookSourceDao.insert(*selectSource.toTypedArray())
            // 导入后的必要排序维护与插入同事务: 要么全部提交, 要么全部回滚
            SourceHelp.adjustSortNumber()
        }
        // 提交成功后刷新缓存: 失败只影响缓存, 不改写已提交结果
        val cacheError = refreshCaches()
        if (cacheError != null) {
            submitSession.cacheFailed(selectSource.size, cacheError)
            throw ImportPostCommitException("书源已写入，但$cacheError")
        }
        // 缓存真实完成: 清除运行态与告警 (保留真实提交数量)
        submitSession.cacheDone(selectSource.size)
    }
} catch (e: CancellationException) {
    // 非 UI 直连入口也必须由 VM 终结运行态, 不依赖调用方再包一层
    endSubmit()
    throw e
}

    /** 缓存-only 维护: 只重跑提交后的缓存刷新, 不重写已提交数据。 */
    suspend fun repairCaches(): String? = withContext(IoDispatcher) { refreshCaches() }

    /** 缓存维护协程被取消/未启动: 结束运行态, 给出可重试的“未完成”而不是伪装失败。 */
    fun markCacheInterrupted() {
        // 原子检查不是 retry 后才结束导入自身的缓存维护
        submitSession.interruptImportCache(CACHE_INTERRUPTED_WARNING)
    }

    fun finishCacheRetry(attempt: Long, warning: String?): Boolean =
        submitSession.finishCacheRetry(attempt, warning)

    /** 缓存-only 重试开始: 会话状态进入运行态, 重建 UI 也不会开放第二个重试。 */
    fun markCacheRetrying(): Long? = submitSession.tryStartCacheRetry()

    private fun refreshCaches(): String? = runCatching {
        SourceHelp.evictAll()
        ContentProcessorProviders.get().upReplaceRules()
    }.exceptionOrNull()?.let { "刷新缓存失败: ${it.message ?: it}" }

    /**
     * 回调式导入入口 (与原版 `importSelect(finally)` 同形状): 成功/失败都回调 [finally],
     * 失败仅落日志, 结果由 [importSelectAwait] 的返回/异常区分。
     */
    /**
     * 导入书源, 对应 app 端 `importSource(text)`。
     *
     * # 实现细节保持
     *
     * - **输入契约**: source 必须是纯 URL 或 JSON 文本; file:// 地址载荷由
     *   [DeepLinkImportTarget.startImport] 读出文本后传入, 本类不做 Uri 解析。
     * - isAbsUrl: 走 [importSourceUrl] 网络请求 (OkHttpClientProviders + newCallResponseBody);
     * - isJsonObject || isJsonArray: 走 [importFromJson] 本地 JSON 解析 (含 `sourceUrls` 多地址载荷);
     * - else: 抛 `NoStackTraceException("格式不对")` (替代 `context.getString(R.string.wrong_format)`,
     *   与 shared 端其他下沉 VM 文案一致)。
     * - 解析与比对在 [preImportAwait] 内完成, 比对结果经 [successState] 投递;
     * - `onError`: 推送 `_errorState.tryEmit("ImportError:${localizedMessage}")`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 纯文本 (URL / JSON / sourceUrls JSON) 或 file:// 载荷 (由本类入口读成文本)
     */
    fun importSource(text: String) {
        Coroutine.async(scope = scope) {
            preImportAwait(text)
        }.onError {
            _errorState.tryEmit("ImportError:${it.message}")
            AppLog.put("ImportError:${it.message}", it)
        }
    }

    /**
     * 解析载荷 → 与本地库比对, 算好 [defaultChecked] 并投递 [successState]。
     *
     * 手动导入 ([importSource]) 与规则订阅的每日自动更新共用这一段, 两处走同一份解析与同一份
     * "需要导入"判据。载荷无法解析时抛异常 (调用方决定记日志还是提示)。
     *
     * @param text 纯文本 (URL / JSON / sourceUrls JSON) 或 file:// 载荷 (由本类入口读成文本)
     */
    suspend fun preImportAwait(text: String) {
        val mText = readImportPayload(text).trim()
        when {
            mText.isAbsUrl() -> importSourceUrl(mText)

            mText.isJsonObject() || mText.isJsonArray() -> importFromJson(mText)
            else -> throw NoStackTraceException("格式不对")
        }
        comparisonSource()
    }

    /**
     * 从 URL 下载书源 JSON, 对应 app 端 `private suspend fun importSourceUrl(url)`。
     *
     * # 实现细节保持
     *
     * - `OkHttpClientProviders.get().okHttpClient` 替代 app 端 `okHttpClient` 单例;
     * - URL 以 `#requestWithoutUA` 结尾: 截断并设置 `User-Agent: null` 头
     *   (与 app 端原逻辑完全一致, 不"偷懒"省略);
     * - `newCallResponseBody { ... }.decompressed()` 走 commonMain 的 KmpHttpClient 扩展
     *   (jvmAndAndroidMain 经 typealias 等价 okhttp3.*, 行为零 diff);
     * - 用 `bytes()` 一次性读取字节后转 UTF-8 字符串 (与 app 端
     *   `it.readBytes().toString(Charsets.UTF_8)` 等价, 替代 `byteStream().use { readBytes() }`:
     *   commonMain 的 expect InputStream 未声明 `readBytes()` 方法, 也未实现 Closeable,
     *   故不能链 `byteStream().use { }`, 用 `bytes()` 等价且更简洁)。
     * - try-finally 显式关闭 ResponseBody (替代 `use` 扩展, commonMain 的 KmpResponseBody
     *   实现 `io.legado.app.utils.Closeable` 而非 `java.io.Closeable`/`AutoCloseable`,
     *   故 `kotlin.io.use` 不适用; `bytes()` 内部已读尽流, close 兜底资源释放)。
     */
    private suspend fun importSourceUrl(url: String) {
        val responseBody = OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed()
        try {
            val json = responseBody.bytes().decodeToString()
            importFromJson(json)
        } finally {
            responseBody.close()
        }
    }

    /**
     * 从 JSON 字符串解析书源, 对应 app 端 `private fun importFromJson(json)`。
     *
     * # 实现细节保持
     *
     * - 类型按**顶层键**逐条判 (不看整段文本里出现过什么字样): 有 `bookSourceUrl` 键按书源解析,
     *   有 `sourceUrl` 键按旧版订阅源解析后转书源, 两者都没有则抛 `NoStackTraceException("不是书源")`;
     * - 判出类型后校验身份键非空 (书源地址 / 订阅源地址), 为空同样抛 "不是书源":
     *   解析器忽略未知键、实体字段又都有默认值, 缺这道校验会把任意 JSON 落成一条空源;
     *   (原版只校验数组首条, 这里逐条校验, 修正原版缺口)
     * - `{"sourceUrls": [...]}` 载荷: 顶层该键为非空字符串数组时逐条 [importSourceUrl],
     *   其余形态 (缺键/空数组/含非字符串/值非数组) 回落到上面的顶层键判定;
     *   与旧实现差异: 多地址载荷在"下载内容"与 `file://` 载荷上同样生效 (旧实现只看入口文本),
     *   且值必须是字符串数组 (旧实现只取字符串值, 值非数组时静默导入 0 条);
     * - 数组逐条判类型, 混合数组各自处理; 任一条不合格则整批报错且不落库
     *   (旧实现是校验首条后全量入库, 空地址条目会照旧入座)
     * - 文本解析走**宽松入口** (注释/尾逗号/单引号/无引号键), 与全仓导入容错口径一致。
     *
     * 注: GSON.fromJsonObject 在 shared 端为 kotlinx-serialization 包装
     * (GsonExtensions.kt), 行为与 app 端 Gson 等价。
     */
    private suspend fun importFromJson(json: String) {
        val element = runCatching { parseToJsonElementLenient(json) }.getOrElse {
            throw NoStackTraceException("不是书源")
        }
        if (element is JsonObject) {
            val urls = (element["sourceUrls"] as? JsonArray)
                ?.takeIf { array -> array.all { it is JsonPrimitive && it.isString } }
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() }
            if (!urls.isNullOrEmpty()) {
                val sizeBefore = allSources.size
                try {
                    urls.forEach { importSourceUrl(it) }
                } catch (e: Throwable) {
                    while (allSources.size > sizeBefore) {
                        allSources.removeAt(allSources.size - 1)
                    }
                    throw e
                }
                return
            }
        }
        val items = if (element is JsonArray) element.toList() else listOf(element)
        if (items.isEmpty()) throw NoStackTraceException("不是书源")
        // 整批报错时把本次已加入的条目截掉, 避免残留被复用同一实例的后续导入带上
        val sizeBefore = allSources.size
        try {
            items.forEach { item ->
                val obj = item as? JsonObject ?: throw NoStackTraceException("不是书源")
                when {
                    obj.containsKey("bookSourceUrl") -> {
                        val source = GSON.fromJsonObject<BookSource>(item.toString()).getOrElse {
                            throw NoStackTraceException("不是书源")
                        }
                        if (source.bookSourceUrl.isBlank()) throw NoStackTraceException("不是书源")
                        allSources.add(source)
                    }

                    obj.containsKey("sourceUrl") -> {
                        val source = GSON.fromJsonObject<OldRssSource>(item.toString()).getOrElse {
                            throw NoStackTraceException("不是书源")
                        }
                        if (source.sourceUrl.isBlank()) throw NoStackTraceException("不是书源")
                        allSources.add(source.toBookSource())
                    }

                    else -> throw NoStackTraceException("不是书源")
                }
            }
        } catch (e: Throwable) {
            while (allSources.size > sizeBefore) {
                allSources.removeAt(allSources.size - 1)
            }
            throw e
        }
    }

    /**
     * 比对本地已有书源, 对应 app 端 `private fun comparisonSource()`。
     *
     * # 实现细节保持
     *
     * - 遍历 [allSources], 调 `appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)` 查本地;
     * - defaultChecked: source 为 null 或本地 lastUpdateTime < 新源 lastUpdateTime 时选中 (新增/更新默认选);
     * - newSourceStatus: source == null (新增);
     * - updateSourceStatus: source != null 且本地 lastUpdateTime < 新源 lastUpdateTime (更新);
     * - 推送 `_successState.tryEmit(allSources.size)` (替代 `successLiveData.postValue(allSources.size)`)。
     *
     * 由 [preImportAwait] 在 IO 上下文内调用 (DAO 查询必须 IO)。
     */
    private suspend fun comparisonSource() {
        allSources.forEach {
            val source = appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)
            checkSources.add(source)
            defaultChecked.add(source == null || source.lastUpdateTime < it.lastUpdateTime)
            newSourceStatus.add(source == null)
            updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
        }
        _successState.tryEmit(allSources.size)
    }
}
