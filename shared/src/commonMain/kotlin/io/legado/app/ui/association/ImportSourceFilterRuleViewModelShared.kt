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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 导入书源过滤规则 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ImportSourceFilterRuleViewModel(app: Application) : BaseViewModel(app)`:
 * - 2 个公开方法 (importSelect / import) + 2 个 private suspend (importAwait / importUrl)
 *   + 1 个 private (comparisonSource), 仅依赖 DAO + 协程 + okHttpClient + GSON +
 *   SearchBookFilter, 可以下沉 commonMain 供多端复用 (Android / Desktop / iOS / 鸿蒙)。
 * - DAO 访问走 [AppDbProviders.get].sourceFilterRuleDao (宿主启动时由 app 端注册
 *   AppDbAccessorImpl, 已暴露 sourceFilterRuleDao DAO)。
 * - 原 `execute { ... }.onError { ... }.onSuccess { ... }.onFinally { ... }`
 *   (BaseViewModel 内委托 [Coroutine.async]) 下沉后直接调 [Coroutine.async],
 *   保留链式 onError/onSuccess/onFinally 回调结构, 行为等价。
 *
 * # 方法清单对照 (与 app 端原 VM 完全一致)
 *
 * - importSelect(finally): 遍历 selectStatus 选中的 SourceFilterRule, 批量 insert 到
 *   sourceFilterRuleDao, 之后调 SearchBookFilter.reload() 刷新内存缓存;
 * - import(text): 入口方法 (注意 app 端原方法名是 `import` 不是 `importSource`),
 *   调 importAwait 处理 URL/JSON 分支, 成功后调 comparisonSource 比对本地;
 * - importAwait(text) [private suspend]: isAbsUrl→importUrl / isJsonArray→GSON.fromJsonArray
 *   / isJsonObject→GSON.fromJsonObject;
 * - importUrl(url) [private suspend]: okHttpClient.newCallResponseBody, URL 以
 *   `#requestWithoutUA` 结尾时截断并设 `User-Agent: null` 头;
 * - comparisonSource() [private]: 遍历 allRules 调 sourceFilterRuleDao.findById(id),
 *   本地不存在的选中, 推送 successState。
 *
 * # Android 专属依赖替换
 *
 * - **Uri 读取留 app 端**: 原 `importAwait(text)` 内 `text.isUri()` 分支 +
 *   `text.toUri().readText(appCtx)` 是 Android ContentResolver 专属, commonMain 不可用。
 *   下沉后 app 端 `ImportSourceFilterRuleViewModel.import(text)` 先判断 isUri, 若是 Uri
 *   则先读取文本再传入本类 [import]; 否则直接转发到本类 [import]。
 *   URL/JSON 解析逻辑全部下沉到本类。
 * - **appDb.sourceFilterRuleDao**: 改为 [AppDbProviders.get].sourceFilterRuleDao (宿主注册)。
 * - **okHttpClient**: 改为 [OkHttpClientProviders.get].okHttpClient
 *   (shared 内 KmpHttpClient 经 typealias 等价 okhttp3.OkHttpClient,
 *   newCallResponseBody / decompressed / text 均为 commonMain 扩展)。
 * - **SearchBookFilter.reload()**: 已下沉 commonMain, 直接复用 (与 app 端原调用一致)。
 * - **androidx.lifecycle.MutableLiveData**: 不可 KMP, 改为 [MutableStateFlow] + [asStateFlow]
 *   (对照 [ImportBookSourceViewModelShared] 的 2 个 StateFlow 模式)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [ImportBookSourceViewModelShared] / [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared]):
 * - app 端 `ImportSourceFilterRuleViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` 注入;
 * - app 端 errorLiveData / successLiveData (MutableLiveData) 在 init 块内
 *   `viewModelScope.launch { collect { postValue } }` 桥接本类的 [errorState] / [successState],
 *   调用方 `observe` 用法不变;
 * - allRules / checkRules / selectStatus 等 MutableList 直接 getter 转发到本类
 *   (同实例引用);
 * - importSelect / import 转发 (import 先处理 Uri 分支)。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class ImportSourceFilterRuleViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 导入错误信息流 (对照原 `errorLiveData: MutableLiveData<String>`)。
     *
     * 初始 null (与原 LiveData 默认 null 一致), app 端桥接时过滤 null 避免初始假触发。
     * 值格式 `"ImportError:${localizedMessage}"`, 与 app 端原 `errorLiveData.postValue(...)` 一致。
     */
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    /**
     * 导入成功信号流 (对照原 `successLiveData: MutableLiveData<Int>`)。
     *
     * 值为 allRules.size (导入的过滤规则总数), 0 表示解析无结果。
     * 初始 null, app 端桥接时过滤 null 避免初始假触发。
     */
    private val _successState = MutableStateFlow<Int?>(null)
    val successState: StateFlow<Int?> = _successState.asStateFlow()

    /** 解析出的待导入过滤规则列表 (importAwait 累积, comparisonSource 比对, importSelect 写入)。 */
    val allRules = arrayListOf<SourceFilterRule>()

    /** 已有过滤规则 (用于 comparisonSource 比对), null=新增。 */
    val checkRules = arrayListOf<SourceFilterRule?>()

    /** 每个 allRules 元素是否被选中导入 (默认新增选中, 由 app 端 UI 切换)。 */
    val selectStatus = arrayListOf<Boolean>()

    /** 是否全部选中 (对照原 `isSelectAll: Boolean get() = selectStatus.all { it }`)。 */
    val isSelectAll: Boolean get() = selectStatus.all { it }

    /** 选中数量 (对照原 `selectCount: Int get() = selectStatus.count { it }`)。 */
    val selectCount: Int get() = selectStatus.count { it }

    /**
     * 导入选中的过滤规则, 对应 app 端 `importSelect(finally)`。
     *
     * # 实现细节保持
     *
     * - 遍历 [selectStatus], 选中的项加入 selected 列表;
     * - `appDb.sourceFilterRuleDao.insert(*selected.toTypedArray())` 批量写入;
     * - `SearchBookFilter.reload()` 刷新内存缓存 (与 app 端原调用一致, 已下沉 commonMain);
     * - `onFinally` 回调 [finally] (与 app 端 `onFinally { finally.invoke() }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发, 与 app 端 onFinally 一致)
     */
    fun importSelect(finally: () -> Unit) {
        Coroutine.async(scope = scope) {
            val selected = arrayListOf<SourceFilterRule>()
            selectStatus.forEachIndexed { index, b ->
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
     * - `onError`: 推送 `_errorState.value = "ImportError:${localizedMessage}"`
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
            _errorState.value = "ImportError:${it.localizedMessage}"
            AppLog.put("ImportError:${it.localizedMessage}", it)
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
     * - selectStatus: rule 为 null (本地不存在) 时选中 (新增默认选);
     * - `onSuccess` 推送 `_successState.value = allRules.size` (与 app 端
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
                selectStatus.add(rule == null)
            }
        }.onSuccess {
            _successState.value = allRules.size
        }
    }
}
