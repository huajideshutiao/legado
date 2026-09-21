package io.legado.app.ui.association

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.text
import io.legado.app.help.source.SearchBookFilter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 导入书源过滤规则 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `ImportSourceFilterRuleViewModel(app) : BaseViewModel(app)`: 2 个公开方法
 * (importSelect/import) + importAwait/importUrl/comparisonSource, 仅依赖 DAO + 协程 +
 * okHttpClient + GSON + SearchBookFilter, 可下沉多端复用。DAO 走
 * [AppDbProviders.get].sourceFilterRuleDao; `execute{...}` 链式回调下沉为直接调
 * [Coroutine.async]。方法行为与 app 端一致: importSelect 后调 SearchBookFilter.reload()
 * 刷新内存缓存; importAwait 分支 isAbsUrl/isJsonArray/isJsonObject;
 * URL 以 `#requestWithoutUA` 结尾时截断并设 `User-Agent: null` 头。
 *
 * Android 专属依赖替换: Uri 读取留 app 端 (app 端 import 先判 isUri 读文本再转发);
 * okHttpClient → [OkHttpClientProviders.get]; SearchBookFilter.reload() 已下沉直接复用;
 * MutableLiveData → [MutableSharedFlow] (replay=1)。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), app 端持有本类实例,
 * errorLiveData/successLiveData 在 init 块桥接 [errorState]/[successState]。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
class ImportSourceFilterRuleViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

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
     * 值为 allRules.size (导入的过滤规则总数), 0 表示解析无结果。
     * 事件流 (replay=1): 每次解析完成都投递, 数量与上次相同也不会被吞。
     */
    private val _successState = signalFlow<Int>()
    val successState: SharedFlow<Int> = _successState.asSharedFlow()

    /** 解析出的待导入过滤规则列表 (importAwait 累积, comparisonSource 比对, importSelect 写入)。 */
    val allRules = arrayListOf<SourceFilterRule>()

    /** 已有过滤规则 (用于 comparisonSource 比对), null=新增。 */
    val checkRules = arrayListOf<SourceFilterRule?>()

    /** 解析时算出的默认勾选 (默认新增选中)。勾选状态本身归 UI 层, 这里只提供初值。 */
    val defaultChecked = arrayListOf<Boolean>()

    /**
     * 导入选中的过滤规则, 对应 app 端 `importSelect(finally)`。
     *
     * # 实现细节保持
     *
     * - 遍历 [checked], 选中的项加入 selected 列表;
     * - `appDb.sourceFilterRuleDao.insert(*selected.toTypedArray())` 批量写入;
     * - `SearchBookFilter.reload()` 刷新内存缓存 (与 app 端原调用一致, 已下沉 commonMain);
     * - `onFinally` 回调 [finally] (与 app 端 `onFinally { finally.invoke() }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发, 与 app 端 onFinally 一致)
     */
    fun importSelect(checked: List<Boolean>, finally: () -> Unit) {
        Coroutine.async(scope = scope) {
            val selected = arrayListOf<SourceFilterRule>()
            checked.forEachIndexed { index, b ->
                if (b) selected.add(allRules[index])
            }
            appDb.sourceFilterRuleDao.insert(*selected.toTypedArray())
            SearchBookFilter.reload()
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 导入过滤规则, 对应 app 端 `import(text)`。
     *
     * # 实现细节保持
     *
     * - **Uri 分支留 app 端**: app 端 `ImportSourceFilterRuleViewModel.import(text)` 先检查
     *   `mText.isUri()`, 是 Uri 则读取文本后转发到本类, 否则直接转发到本类。
     *   本类仅处理纯文本 (URL/JSON) 分支。
     * - 调 [importAwait] 处理 isAbsUrl / isJsonArray / isJsonObject 分支;
     * - `onError`: 推送 `_errorState.tryEmit("ImportError:${localizedMessage}")`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价);
     * - `onSuccess`: 调 [comparisonSource] 比对本地已有过滤规则 (与 app 端原
     *   `onSuccess { comparisonSource() }` 一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 纯文本 (URL / JSON), Uri 路径已由 app 端预处理
     */
    fun import(text: String) {
        Coroutine.async(scope = scope) {
            importAwait(text.trim())
        }.onError {
            _errorState.tryEmit("ImportError:${it.message}")
            AppLog.put("ImportError:${it.message}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    /**
     * 解析文本为 SourceFilterRule 并累积到 [allRules], 对应 app 端 `private suspend fun importAwait(text)`。
     *
     * # 实现细节保持
     *
     * - isAbsUrl: 走 [importUrl] 网络请求 (OkHttpClientProviders + newCallResponseBody),
     *   下载的 JSON 文本递归调 [importAwait];
     * - isJsonArray: 用 `GSON.fromJsonArray<SourceFilterRule>(text).getOrThrow()` 解析数组;
     * - isJsonObject: 用 `GSON.fromJsonObject<SourceFilterRule>(text).getOrThrow()` 解析单对象;
     * - else: 抛 `NoStackTraceException("格式不对")` (与 app 端原逻辑完全一致)。
     *
     * 注: 原 app 端有 `text.isUri()` 分支 (`text.toUri().readText(appCtx)` 后递归),
     * commonMain 不可用, 已留 app 端 `ImportSourceFilterRuleViewModel.import` 入口预处理。
     */
    private suspend fun importAwait(text: String) {
        when {
            text.isAbsUrl() -> importUrl(text)
            text.isJsonArray() -> {
                val rules = GSON.fromJsonArray<SourceFilterRule>(text).getOrThrow()
                allRules.addAll(rules)
            }

            text.isJsonObject() -> {
                val rule = GSON.fromJsonObject<SourceFilterRule>(text).getOrThrow()
                allRules.add(rule)
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    /**
     * 从 URL 下载过滤规则 JSON, 对应 app 端 `private suspend fun importUrl(url)`。
     *
     * # 实现细节保持
     *
     * - `OkHttpClientProviders.get().okHttpClient` 替代 app 端 `okHttpClient` 单例;
     * - URL 以 `#requestWithoutUA` 结尾: 截断并设置 `User-Agent: null` 头
     *   (与 app 端原逻辑完全一致, 不"偷懒"省略);
     * - `newCallResponseBody { ... }.decompressed().text("utf-8")` 走 commonMain 的
     *   KmpHttpClient 扩展 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*, 行为零 diff);
     * - 下载的文本递归调 [importAwait] (与 app 端 `importAwait(it)` 一致)。
     *
     * 注: app 端原版用 `text("utf-8")` 显式指定编码, 本类保留 (与 [ImportReplaceRuleViewModelShared]
     * 的 `text("utf-8")` 一致; [ImportHttpTtsViewModelShared] / [ImportTxtTocRuleViewModelShared]
     * 等用 `text()` 默认 utf-8, 与各自 app 端原版一致)。
     */
    private suspend fun importUrl(url: String) {
        OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text("utf-8").let {
            importAwait(it)
        }
    }

    /**
     * 比对本地已有过滤规则, 对应 app 端 `private fun comparisonSource()`。
     *
     * # 实现细节保持
     *
     * - 遍历 [allRules], 调 `appDb.sourceFilterRuleDao.findById(it.id)` 查本地;
     * - defaultChecked: rule 为 null (本地不存在) 时选中 (新增默认选);
     * - `onSuccess` 推送 `_successState.tryEmit(allRules.size)` (与 app 端
     *   `onSuccess { successLiveData.postValue(allRules.size) }` 一致, 注意原版
     *   comparisonSource 是 onSuccess 才推送 successState, 与其他 VM 直接推送不同,
     *   本类保留原版 onSuccess 嵌套结构)。
     *
     * 业务在 IO 跑 (DAO 查询必须 IO), 与 BaseViewModel.execute 默认值一致。
     */
    private fun comparisonSource() {
        Coroutine.async(scope = scope) {
            allRules.forEach {
                val rule = appDb.sourceFilterRuleDao.findById(it.id)
                checkRules.add(rule)
                defaultChecked.add(rule == null)
            }
        }.onSuccess {
            _successState.tryEmit(allRules.size)
        }
    }
}
