package io.legado.app.ui.association

import com.github.jershell.rjpath.RJPath
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
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.parseJsonElement
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonPrimitive

/**
 * 导入书源 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `ImportBookSourceViewModel(app) : BaseViewModel(app)`: 4 个方法
 * (importSelect/importSource/importFromJson/comparisonSource) + private importSourceUrl,
 * 仅依赖 DAO + 协程 + okHttpClient + SourceHelp + ContentProcessor + GSON + RJPath,
 * 可下沉多端复用。DAO 走 [AppDbProviders.get].bookSourceDao; `execute{...}` 链式回调
 * 下沉为直接调 [Coroutine.async] (业务 IO / 回调 mainDispatcher, 行为等价)。
 *
 * Android 专属依赖替换: Uri 读取留 app 端 (app 端 importSource 先判 isUri 读文本/字节再转发);
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
class ImportBookSourceViewModelShared(
    private val scope: CoroutineScope,
) {

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

    /** 每个 allSources 元素是否被选中导入 (默认新增/更新都选中, 由 app 端 UI 切换)。 */
    val selectStatus = arrayListOf<Boolean>()

    /** 标记对应位置书源是否为新增 (comparisonSource 设置, app 端 selectNew 用)。 */
    val newSourceStatus = arrayListOf<Boolean>()

    /** 标记对应位置书源是否为更新 (comparisonSource 设置, app 端 selectUpdate 用)。 */
    val updateSourceStatus = arrayListOf<Boolean>()

    /** 是否全部选中 (对照原 `isSelectAll: Boolean get() = selectStatus.all { it }`)。 */
    val isSelectAll: Boolean get() = selectStatus.all { it }

    /** 新增项是否全部选中 (对照原 `isSelectAllNew` 逻辑)。 */
    val isSelectAllNew: Boolean
        get() = newSourceStatus.indices.all { !newSourceStatus[it] || selectStatus[it] }

    /** 更新项是否全部选中 (对照原 `isSelectAllUpdate` 逻辑)。 */
    val isSelectAllUpdate: Boolean
        get() = updateSourceStatus.indices.all { !updateSourceStatus[it] || selectStatus[it] }

    /** 选中数量 (对照原 `selectCount: Int get() = selectStatus.count { it }`)。 */
    val selectCount: Int get() = selectStatus.count { it }

    /**
     * 导入选中的书源, 对应 app 端 `importSelect(finally)`。
     *
     * # 实现细节保持
     *
     * - 取 [groupName] trim, 读 AppConfigProviders 的 importKeepName/Group/Enable 三个开关;
     * - 遍历 [selectStatus], 选中的项走 keepName/Group/Enable/customOrder 还原逻辑
     *   (与 app 端原 `checkSources[index]?.let { ... }` 完全一致);
     * - 若 [groupName] 非空: isAddGroup=true 时追加到原分组 (linkedSetOf 去重),
     *   isAddGroup=false 时覆盖 (与 app 端原逻辑完全一致, 不"偷懒"分离分支);
     * - `appDb.bookSourceDao.insert(*selectSource.toTypedArray())` 批量写入;
     * - `Coroutine.async { SourceHelp.adjustSortNumber() }` 异步调整排序
     *   (内层 Coroutine.async 用 DEFAULT scope, 与 app 端原写法一致);
     * - `ContentProcessorProviders.get().upReplaceRules()` 刷新替换规则缓存
     *   (替代 app 端 `ContentProcessor.upReplaceRules()`);
     * - `onFinally` 回调 [finally] (与 app 端 `onFinally { finally.invoke() }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发, 与 app 端 onFinally 一致)
     */
    fun importSelect(finally: () -> Unit) {
        Coroutine.async(scope = scope) {
            val group = groupName?.trim()
            val keepName = AppConfigProviders.get().importKeepName
            val keepGroup = AppConfigProviders.get().importKeepGroup
            val keepEnable = AppConfigProviders.get().importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
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
                        source.bookSourceGroup = if (isAddGroup) {
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
            appDb.bookSourceDao.insert(*selectSource.toTypedArray())
            Coroutine.async {
                SourceHelp.adjustSortNumber()
            }
            ContentProcessorProviders.get().upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 导入书源, 对应 app 端 `importSource(text)`。
     *
     * # 实现细节保持
     *
     * - **Uri 分支留 app 端**: app 端 `ImportBookSourceViewModel.importSource(text)`
     *   先检查 `mText.isUri()`, 是 Uri 则读取 inputStream 文本后转发到本类,
     *   否则直接转发到本类。本类仅处理纯文本 (URL/JSON) 分支。
     * - isAbsUrl: 走 [importSourceUrl] 网络请求 (OkHttpClientProviders + newCallResponseBody);
     * - isJsonObject && contains("sourceUrls"): RJPath 解析 sourceUrls 数组, 逐 URL 调 [importSourceUrl];
     * - isJsonObject || isJsonArray: 走 [importFromJson] 本地 JSON 解析;
     * - else: 抛 `NoStackTraceException("格式不对")` (替代 `context.getString(R.string.wrong_format)`,
     *   与 shared 端其他下沉 VM 文案一致)。
     * - `onError`: 推送 `_errorState.tryEmit("ImportError:${localizedMessage}")`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价);
     * - `onSuccess`: 调 [comparisonSource] 比对本地已有书源 (与 app 端原 `onSuccess { comparisonSource() }` 一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 纯文本 (URL / JSON / sourceUrls JSON), Uri 路径已由 app 端预处理
     */
    fun importSource(text: String) {
        Coroutine.async(scope = scope) {
            val mText = text.trim()
            when {
                mText.isAbsUrl() -> importSourceUrl(mText)

                mText.isJsonObject() && mText.contains("sourceUrls") -> {
                    val json = parseJsonElement(mText)
                    RJPath.selector("$.sourceUrls").getAll(json).forEach { element ->
                        val url = (element as? JsonPrimitive)?.content
                        url?.let { importSourceUrl(it) }
                    }
                }

                mText.isJsonObject() || mText.isJsonArray() -> importFromJson(mText)
                else -> throw NoStackTraceException("格式不对")
            }
        }.onError {
            _errorState.tryEmit("ImportError:${it.message}")
            AppLog.put("ImportError:${it.message}", it)
        }.onSuccess {
            comparisonSource()
        }
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
     * - 先判断 `json.contains("bookSourceUrl")` (书源): 数组走 fromJsonArray,
     *   单对象走 fromJsonObject, 失败抛 `NoStackTraceException("不是书源")`;
     * - 再判断 `json.contains("sourceUrl")` (旧版 RSS 源): 数组走 fromJsonArray,
     *   单对象走 fromJsonObject, 解析后调 `OldRssSource.toBookSource()` 转换;
     * - 其他: 抛 `NoStackTraceException("不是书源")` (与 app 端原逻辑完全一致)。
     *
     * 注: GSON.fromJsonArray/fromJsonObject 在 shared 端为 kotlinx-serialization 包装
     * (GsonExtensions.kt), 行为与 app 端 Gson 等价。
     */
    private fun importFromJson(json: String) {
        val isArray = json.isJsonArray()
        when {
            json.contains("bookSourceUrl") -> {
                if (isArray) {
                    allSources.addAll(GSON.fromJsonArray<BookSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    })
                } else {
                    allSources.add(GSON.fromJsonObject<BookSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    })
                }
            }

            json.contains("sourceUrl") -> {
                if (isArray) {
                    allSources.addAll(GSON.fromJsonArray<OldRssSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    }.map { it.toBookSource() })
                } else {
                    allSources.add(GSON.fromJsonObject<OldRssSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    }.toBookSource())
                }
            }
            else -> throw NoStackTraceException("不是书源")
        }
    }

    /**
     * 比对本地已有书源, 对应 app 端 `private fun comparisonSource()`。
     *
     * # 实现细节保持
     *
     * - 遍历 [allSources], 调 `appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)` 查本地;
     * - selectStatus: source 为 null 或本地 lastUpdateTime < 新源 lastUpdateTime 时选中 (新增/更新默认选);
     * - newSourceStatus: source == null (新增);
     * - updateSourceStatus: source != null 且本地 lastUpdateTime < 新源 lastUpdateTime (更新);
     * - 推送 `_successState.tryEmit(allSources.size)` (替代 `successLiveData.postValue(allSources.size)`)。
     *
     * 业务在 IO 跑 (DAO 查询必须 IO), 与 BaseViewModel.execute 默认值一致。
     */
    private fun comparisonSource() {
        Coroutine.async(scope = scope) {
            allSources.forEach {
                val source = appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)
                checkSources.add(source)
                selectStatus.add(source == null || source.lastUpdateTime < it.lastUpdateTime)
                newSourceStatus.add(source == null)
                updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
            }
            _successState.tryEmit(allSources.size)
        }
    }
}
