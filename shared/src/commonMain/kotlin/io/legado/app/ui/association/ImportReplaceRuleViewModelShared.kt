package io.legado.app.ui.association

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ReplaceAnalyzer
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.text
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 导入替换规则 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ImportReplaceRuleViewModel(app: Application) : BaseViewModel(app)`:
 * - 2 个公开方法 (importSelect / import) + 2 个 private suspend (importAwait / importUrl)
 *   + 1 个 private (comparisonSource), 仅依赖 DAO + 协程 + okHttpClient +
 *   ReplaceAnalyzer + AppPattern, 可以下沉 commonMain 供多端复用
 *   (Android / Desktop / iOS / 鸿蒙)。
 * - DAO 访问走 [AppDbProviders.get].replaceRuleDao (宿主启动时由 app 端注册
 *   AppDbAccessorImpl, 已暴露 replaceRuleDao DAO)。
 * - 原 `execute { ... }.onError { ... }.onSuccess { ... }.onFinally { ... }`
 *   (BaseViewModel 内委托 [Coroutine.async]) 下沉后直接调 [Coroutine.async],
 *   保留链式 onError/onSuccess/onFinally 回调结构, 行为等价。
 *
 * # 方法清单对照 (与 app 端原 VM 完全一致)
 *
 * - importSelect(finally): 遍历 selectStatus 选中的 ReplaceRule, 按 [groupName]/[isAddGroup]
 *   处理分组覆盖/追加 (linkedSetOf 去重), 批量 insert 到 replaceRuleDao;
 * - import(text): 入口方法 (注意 app 端原方法名是 `import` 不是 `importSource`),
 *   调 importAwait 处理 URL/JSON 分支, 成功后调 comparisonSource 比对本地;
 * - importAwait(text) [private suspend]: isAbsUrl→importUrl / isJsonArray→
 *   ReplaceAnalyzer.jsonToReplaceRules / isJsonObject→ReplaceAnalyzer.jsonToReplaceRule;
 * - importUrl(url) [private suspend]: okHttpClient.newCallResponseBody, URL 以
 *   `#requestWithoutUA` 结尾时截断并设 `User-Agent: null` 头;
 * - comparisonSource() [private]: 遍历 allRules 调 replaceRuleDao.findById(id),
 *   本地不存在的选中, 推送 successState。
 *
 * # Android 专属依赖替换
 *
 * - **Uri 读取留 app 端**: 原 `importAwait(text)` 内 `text.isUri()` 分支 +
 *   `text.toUri().readText(appCtx)` 是 Android ContentResolver 专属, commonMain 不可用。
 *   下沉后 app 端 `ImportReplaceRuleViewModel.import(text)` 先判断 isUri, 若是 Uri
 *   则先读取文本再传入本类 [import]; 否则直接转发到本类 [import]。
 *   URL/JSON 解析逻辑全部下沉到本类。
 * - **appDb.replaceRuleDao**: 改为 [AppDbProviders.get].replaceRuleDao (宿主注册)。
 * - **okHttpClient**: 改为 [OkHttpClientProviders.get].okHttpClient
 *   (shared 内 KmpHttpClient 经 typealias 等价 okhttp3.OkHttpClient,
 *   newCallResponseBody / decompressed / text 均为 commonMain 扩展)。
 * - **ReplaceAnalyzer**: 已下沉 commonMain, 直接复用 (与 app 端原调用一致);
 * - **AppPattern.splitGroupRegex**: 已下沉 commonMain, 直接复用;
 * - **androidx.lifecycle.MutableLiveData**: 不可 KMP, 改为 [MutableSharedFlow] (replay=1) + [asSharedFlow]
 *   (对照 [ImportBookSourceViewModelShared] 的事件流模式)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [ImportBookSourceViewModelShared] / [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared]):
 * - app 端 `ImportReplaceRuleViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` 注入;
 * - app 端 errorLiveData / successLiveData (MutableLiveData) 在 init 块内
 *   `viewModelScope.launch { collect { postValue } }` 桥接本类的 [errorState] / [successState],
 *   调用方 `observe` 用法不变;
 * - allRules / checkRules / selectStatus 等 MutableList 直接 getter 转发到本类
 *   (同实例引用);
 * - isAddGroup / groupName 通过 var getter/setter 委托本类 (修改同步反映到本类);
 * - importSelect / import 转发 (import 先处理 Uri 分支)。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class ImportReplaceRuleViewModelShared(
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
     * 值为 allRules.size (导入的替换规则总数), 0 表示解析无结果。
     * 事件流 (replay=1): 每次解析完成都投递, 数量与上次相同也不会被吞。
     */
    private val _successState = signalFlow<Int>()
    val successState: SharedFlow<Int> = _successState.asSharedFlow()

    /** 解析出的待导入替换规则列表 (importAwait 累积, comparisonSource 比对, importSelect 写入)。 */
    val allRules = arrayListOf<ReplaceRule>()

    /** 已有替换规则 (用于 comparisonSource 比对), null=新增。 */
    val checkRules = arrayListOf<ReplaceRule?>()

    /** 每个 allRules 元素是否被选中导入 (默认新增选中, 由 app 端 UI 切换)。 */
    val selectStatus = arrayListOf<Boolean>()

    /** 是否全部选中 (对照原 `isSelectAll: Boolean get() = selectStatus.all { it }`)。 */
    val isSelectAll: Boolean
        get() {
            selectStatus.forEach {
                if (!it) {
                    return false
                }
            }
            return true
        }

    /** 选中数量 (对照原 `selectCount: Int get() = selectStatus.count { it }`)。 */
    val selectCount: Int
        get() {
            var count = 0
            selectStatus.forEach {
                if (it) {
                    count++
                }
            }
            return count
        }

    /**
     * 导入选中的替换规则, 对应 app 端 `importSelect(finally)`。
     *
     * # 实现细节保持
     *
     * - 取 [groupName] trim, 遍历 [selectStatus], 选中的项走分组覆盖/追加逻辑;
     * - 若 [groupName] 非空:
     *   - isAddGroup=true 时追加到原分组 (linkedSetOf 去重 + splitNotBlank 拆原分组),
     *     与 [ImportBookSourceViewModelShared.importSelect] 中 bookSourceGroup 处理逻辑同构;
     *   - isAddGroup=false 时直接覆盖 `rule.group = group`;
     * - `appDb.replaceRuleDao.insert(*selectRules.toTypedArray())` 批量写入;
     * - `onFinally` 回调 [finally] (与 app 端 `onFinally { finally.invoke() }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发, 与 app 端 onFinally 一致)
     */
    fun importSelect(finally: () -> Unit) {
        Coroutine.async(scope = scope) {
            val group = groupName?.trim()
            val selectRules = arrayListOf<ReplaceRule>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val rule = allRules[index]
                    if (!group.isNullOrEmpty()) {
                        if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            rule.group?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            rule.group = groups.joinToString(",")
                        } else {
                            rule.group = group
                        }
                    }
                    selectRules.add(rule)
                }
            }
            appDb.replaceRuleDao.insert(*selectRules.toTypedArray())
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 导入替换规则, 对应 app 端 `import(text)`。
     *
     * # 实现细节保持
     *
     * - **Uri 分支留 app 端**: app 端 `ImportReplaceRuleViewModel.import(text)` 先检查
     *   `mText.isUri()`, 是 Uri 则读取文本后转发到本类, 否则直接转发到本类。
     *   本类仅处理纯文本 (URL/JSON) 分支。
     * - 调 [importAwait] 处理 isAbsUrl / isJsonArray / isJsonObject 分支;
     * - `onError`: 推送 `_errorState.tryEmit("ImportError:${localizedMessage}")`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价);
     * - `onSuccess`: 调 [comparisonSource] 比对本地已有替换规则 (与 app 端原
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
     * 解析文本为 ReplaceRule 并累积到 [allRules], 对应 app 端 `private suspend fun importAwait(text)`。
     *
     * # 实现细节保持
     *
     * - isAbsUrl: 走 [importUrl] 网络请求 (OkHttpClientProviders + newCallResponseBody),
     *   下载的 JSON 文本递归调 [importAwait];
     * - isJsonArray: 用 `ReplaceAnalyzer.jsonToReplaceRules(text).getOrThrow()` 解析数组;
     * - isJsonObject: 用 `ReplaceAnalyzer.jsonToReplaceRule(text).getOrThrow()` 解析单对象;
     * - else: 抛 `NoStackTraceException("格式不对")` (与 app 端原逻辑完全一致)。
     *
     * 注: 与其他 ImportXxxViewModelShared 用 GSON.fromJsonObject / fromJsonArray 不同,
     * 替换规则走 [ReplaceAnalyzer] (内部处理替换规则的字段兼容性, 如旧版 isRegex / order
     * 等字段映射), 与 app 端原逻辑完全一致, 不"偷懒"简化。
     *
     * 注: 原 app 端有 `text.isUri()` 分支 (`text.toUri().readText(appCtx)` 后递归),
     * commonMain 不可用, 已留 app 端 `ImportReplaceRuleViewModel.import` 入口预处理。
     */
    private suspend fun importAwait(text: String) {
        when {
            text.isAbsUrl() -> importUrl(text)
            text.isJsonArray() -> {
                val rules = ReplaceAnalyzer.jsonToReplaceRules(text).getOrThrow()
                allRules.addAll(rules)
            }

            text.isJsonObject() -> {
                val rule = ReplaceAnalyzer.jsonToReplaceRule(text).getOrThrow()
                allRules.add(rule)
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    /**
     * 从 URL 下载替换规则 JSON, 对应 app 端 `private suspend fun importUrl(url)`。
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
     * 注: app 端原版用 `text("utf-8")` 显式指定编码, 本类保留 (与
     * [ImportSourceFilterRuleViewModelShared] 的 `text("utf-8")` 一致)。
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
     * 比对本地已有替换规则, 对应 app 端 `private fun comparisonSource()`。
     *
     * # 实现细节保持
     *
     * - 遍历 [allRules], 调 `appDb.replaceRuleDao.findById(it.id)` 查本地;
     * - selectStatus: rule 为 null (本地不存在) 时选中 (新增默认选);
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
                val rule = appDb.replaceRuleDao.findById(it.id)
                checkRules.add(rule)
                selectStatus.add(rule == null)
            }
        }.onSuccess {
            _successState.tryEmit(allRules.size)
        }
    }
}
