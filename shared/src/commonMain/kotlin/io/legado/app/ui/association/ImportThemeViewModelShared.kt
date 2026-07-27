package io.legado.app.ui.association

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProvider
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.text
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
 * 导入主题配置 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ImportThemeViewModel(app: Application) : BaseViewModel(app)`:
 * - 2 个公开方法 (importSelect / importSource) + 2 个 private suspend
 *   (importSourceAwait / importSourceUrl) + 1 个 private (comparisonSource),
 *   仅依赖 ThemeConfig + 协程 + okHttpClient + GSON, 可以下沉 commonMain 供多端复用
 *   (Android / Desktop / iOS / 鸿蒙)。
 * - 与其他 ImportXxxViewModelShared 不同, 主题不走 DAO, 而是走 [ThemeConfigProviders]
 *   (因为 app 端 ThemeConfig 单例依赖 SharedPreferences + File + Context, 无法直接下沉;
 *   抽 [ThemeConfigProvider] 接口, app 端 ThemeConfigProviderImpl 包装 ThemeConfig.configList
 *   读和 ThemeConfig.addConfig 写)。
 * - 原 `execute { ... }.onError { ... }.onSuccess { ... }.onFinally { ... }`
 *   (BaseViewModel 内委托 [Coroutine.async]) 下沉后直接调 [Coroutine.async],
 *   保留链式 onError/onSuccess/onFinally 回调结构, 行为等价。
 *
 * # 方法清单对照 (与 app 端原 VM 完全一致)
 *
 * - importSelect(finally): 遍历 selectStatus 选中的 ThemeConfig.Config, 调
 *   ThemeConfig.addConfig (经 [ThemeConfigProviders.get].addConfig 桥接);
 * - importSource(text): 入口方法, 调 importSourceAwait 处理 URL/JSON 分支, 成功后
 *   调 comparisonSource 比对本地;
 * - importSourceAwait(text) [private suspend]: isJsonObject→GSON.fromJsonObject 单对象
 *   / isJsonArray→GSON.fromJsonArray 数组 / isAbsUrl→importSourceUrl;
 * - importSourceUrl(url) [private suspend]: okHttpClient.newCallResponseBody, URL 以
 *   `#requestWithoutUA` 结尾时截断并设 `User-Agent: null` 头;
 * - comparisonSource() [private]: 遍历 allSources 调
 *   `ThemeConfig.configList.find { it.themeName == config.themeName }` (经
 *   [ThemeConfigProviders.get].getConfigList 桥接), 本地不存在或与本地不同则选中, 推送 successState。
 *
 * # Android 专属依赖替换
 *
 * - **Uri 读取留 app 端**: 原 `importSourceAwait(text)` 内 `text.isUri()` 分支 +
 *   `text.toUri().readText(appCtx)` 是 Android ContentResolver 专属, commonMain 不可用。
 *   下沉后 app 端 `ImportThemeViewModel.importSource(text)` 先判断 isUri, 若是 Uri
 *   则先读取文本再传入本类 [importSource]; 否则直接转发到本类 [importSource]。
 *   URL/JSON 解析逻辑全部下沉到本类。
 * - **ThemeConfig.configList**: 改为 [ThemeConfigProviders.get].getConfigList()
 *   (app 端 ThemeConfigProviderImpl 把 `ThemeConfig.configList` 中每个 `Config` 转换为
 *   [ThemeConfigData] 返回, 字段一一对应)。
 * - **ThemeConfig.addConfig(config)**: 改为 [ThemeConfigProviders.get].addConfig(config)
 *   (app 端 ThemeConfigProviderImpl 把 [ThemeConfigData] 转回 `ThemeConfig.Config` 后调
 *   `ThemeConfig.addConfig(newConfig)`, 内部按 themeName 去重 + save 持久化)。
 * - **ThemeConfig.Config 数据类**: 抽为 commonMain 的 [ThemeConfigData] (字段一一对应,
 *   @Serializable + @Transient isBuiltin, hashCode/equals 与 app 端原版完全一致)。
 * - **okHttpClient**: 改为 [OkHttpClientProviders.get].okHttpClient
 *   (shared 内 KmpHttpClient 经 typealias 等价 okhttp3.OkHttpClient,
 *   newCallResponseBody / decompressed / text 均为 commonMain 扩展)。
 * - **context.getString(R.string.wrong_format)**: commonMain 无 R.string 资源,
 *   改为直接抛 `NoStackTraceException("格式不对")` (与 shared 端其他下沉 VM
 *   文案一致, 错误经 `_errorState.value = "ImportError:${it.message}"` 推送)。
 * - **androidx.lifecycle.MutableLiveData**: 不可 KMP, 改为 [MutableStateFlow] + [asStateFlow]
 *   (对照 [ImportBookSourceViewModelShared] 的 2 个 StateFlow 模式)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [ImportBookSourceViewModelShared] / [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared]):
 * - app 端 `ImportThemeViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` 注入;
 * - app 端 errorLiveData / successLiveData (MutableLiveData) 在 init 块内
 *   `viewModelScope.launch { collect { postValue } }` 桥接本类的 [errorState] / [successState],
 *   调用方 `observe` 用法不变;
 * - allSources / checkSources / selectStatus 等 MutableList 直接 getter 转发到本类
 *   (同实例引用);
 * - importSelect / importSource 转发 (importSource 先处理 Uri 分支)。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class ImportThemeViewModelShared(
    private val scope: CoroutineScope,
) {

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
     * 值为 allSources.size (导入的主题配置总数), 0 表示解析无结果。
     * 初始 null, app 端桥接时过滤 null 避免初始假触发。
     */
    private val _successState = MutableStateFlow<Int?>(null)
    val successState: StateFlow<Int?> = _successState.asStateFlow()

    /** 解析出的待导入主题配置列表 (importSourceAwait 累积, comparisonSource 比对, importSelect 写入)。 */
    val allSources = arrayListOf<ThemeConfigData>()

    /** 已有主题配置 (用于 comparisonSource 比对), null=新增。 */
    val checkSources = arrayListOf<ThemeConfigData?>()

    /** 每个 allSources 元素是否被选中导入 (默认新增/与本地不同都选中, 由 app 端 UI 切换)。 */
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
     * 导入选中的主题配置, 对应 app 端 `importSelect(finally)`。
     *
     * # 实现细节保持
     *
     * - 遍历 [selectStatus], 选中的项调 `ThemeConfigProviders.get().addConfig(allSources[index])`
     *   (替代 app 端 `ThemeConfig.addConfig(allSources[index])`, app 端 ThemeConfigProviderImpl
     *   内部把 [ThemeConfigData] 转回 `ThemeConfig.Config` 后调原 `ThemeConfig.addConfig(newConfig)`,
     *   保留按 themeName 去重 + save 持久化行为);
     * - `onFinally` 回调 [finally] (与 app 端 `onFinally { finally.invoke() }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发, 与 app 端 onFinally 一致)
     */
    fun importSelect(finally: () -> Unit) {
        Coroutine.async(scope = scope) {
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    ThemeConfigProviders.get().addConfig(allSources[index])
                }
            }
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 导入主题配置, 对应 app 端 `importSource(text)`。
     *
     * # 实现细节保持
     *
     * - **Uri 分支留 app 端**: app 端 `ImportThemeViewModel.importSource(text)` 先检查
     *   `mText.isUri()`, 是 Uri 则读取文本后转发到本类, 否则直接转发到本类。
     *   本类仅处理纯文本 (URL/JSON) 分支。
     * - 调 [importSourceAwait] 处理 isJsonObject / isJsonArray / isAbsUrl 分支;
     * - `onError`: 推送 `_errorState.value = "ImportError:${localizedMessage}"`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价);
     * - `onSuccess`: 调 [comparisonSource] 比对本地已有主题配置 (与 app 端原
     *   `onSuccess { comparisonSource() }` 一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 纯文本 (URL / JSON), Uri 路径已由 app 端预处理
     */
    fun importSource(text: String) {
        Coroutine.async(scope = scope) {
            importSourceAwait(text.trim())
        }.onError {
            _errorState.value = "ImportError:${it.message}"
            AppLog.put("ImportError:${it.message}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    /**
     * 解析文本为 ThemeConfigData 并累积到 [allSources], 对应 app 端 `private suspend fun importSourceAwait(text)`。
     *
     * # 实现细节保持
     *
     * - isJsonObject: 用 `GSON.fromJsonObject<ThemeConfigData>(text).getOrThrow()` 解析单对象;
     * - isJsonArray: 用 `GSON.fromJsonArray<ThemeConfigData>(text).getOrThrow()` 解析数组;
     * - isAbsUrl: 走 [importSourceUrl] 网络请求 (OkHttpClientProviders + newCallResponseBody),
     *   下载的 JSON 文本递归调 [importSourceAwait];
     * - else: 抛 `NoStackTraceException("格式不对")` (替代 `context.getString(R.string.wrong_format)`,
     *   与 shared 端其他下沉 VM 文案一致)。
     *
     * 注: 原 app 端用 `GSON.fromJsonObject<ThemeConfig.Config>` / `GSON.fromJsonArray<ThemeConfig.Config>`,
     * 本类改为 [ThemeConfigData] (KMP 版数据类, 字段一一对应, kotlinx.serialization 解析)。
     *
     * 注: 原 app 端有 `text.isUri()` 分支 (`text.toUri().readText(appCtx)` 后递归),
     * commonMain 不可用, 已留 app 端 `ImportThemeViewModel.importSource` 入口预处理。
     */
    private suspend fun importSourceAwait(text: String) {
        when {
            text.isJsonObject() -> {
                GSON.fromJsonObject<ThemeConfigData>(text).getOrThrow().let {
                    allSources.add(it)
                }
            }

            text.isJsonArray() -> GSON.fromJsonArray<ThemeConfigData>(text).getOrThrow()
                .let { items ->
                    allSources.addAll(items)
                }

            text.isAbsUrl() -> {
                importSourceUrl(text)
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    /**
     * 从 URL 下载主题配置 JSON, 对应 app 端 `private suspend fun importSourceUrl(url)`。
     *
     * # 实现细节保持
     *
     * - `OkHttpClientProviders.get().okHttpClient` 替代 app 端 `okHttpClient` 单例;
     * - URL 以 `#requestWithoutUA` 结尾: 截断并设置 `User-Agent: null` 头
     *   (与 app 端原逻辑完全一致, 不"偷懒"省略);
     * - `newCallResponseBody { ... }.decompressed().text()` 走 commonMain 的 KmpHttpClient
     *   扩展 (jvmAndAndroidMain 经 typealias 等价 okhttp3.*, 行为零 diff);
     * - 下载的文本递归调 [importSourceAwait] (与 app 端 `importSourceAwait(it)` 一致)。
     */
    private suspend fun importSourceUrl(url: String) {
        OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text().let {
            importSourceAwait(it)
        }
    }

    /**
     * 比对本地已有主题配置, 对应 app 端 `private fun comparisonSource()`。
     *
     * # 实现细节保持
     *
     * - 遍历 [allSources], 调 `ThemeConfigProviders.get().getConfigList().find {
     *     it.themeName == config.themeName }` 查本地 (替代 app 端
     *   `ThemeConfig.configList.find { it.themeName == config.themeName }`, 行为等价);
     * - selectStatus: source 为 null (新增) 或 `source != config` (与本地不同) 时选中
     *   (与 app 端原 `selectStatus.add(source == null || source != config)` 完全一致,
     *   这里 `source != config` 走 [ThemeConfigData.equals] 重写, 6 个字段逐一比较,
     *   与 app 端 ThemeConfig.Config.equals 行为一致);
     * - 推送 `_successState.value = allSources.size` (替代 `successLiveData.postValue(allSources.size)`)。
     *
     * 业务在 IO 跑 (configList 读 + 比对), 与 BaseViewModel.execute 默认值一致。
     */
    private fun comparisonSource() {
        Coroutine.async(scope = scope) {
            val configList = ThemeConfigProviders.get().getConfigList()
            allSources.forEach { config ->
                val source = configList.find {
                    it.themeName == config.themeName
                }
                checkSources.add(source)
                selectStatus.add(source == null || source != config)
            }
            _successState.value = allSources.size
        }
    }
}
