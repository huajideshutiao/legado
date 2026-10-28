package io.legado.app.ui.association

import kotlinx.atomicfu.atomic
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.ThemeConfigData
import io.legado.app.help.config.ThemeConfigProvider
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.text
import io.legado.app.ui.readImportPayload
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 导入主题配置 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `ImportThemeViewModel(app) : BaseViewModel(app)`: 2 个公开方法
 * (importSelect/importSource) + 3 个 private 辅助, 仅依赖 ThemeConfig + 协程 +
 * okHttpClient + GSON, 可下沉多端复用。与其他 ImportXxxViewModelShared 不同,
 * 主题不走 DAO: app 端 ThemeConfig 单例依赖 SharedPreferences/File/Context 无法下沉,
 * 抽 [ThemeConfigProvider] 接口桥接 configList 读 + addConfig 写 (字段互转 [ThemeConfigData])。
 * `execute{...}` 链式回调下沉为直接调 [Coroutine.async], 行为等价。
 *
 * Android 专属依赖替换: file:// 载荷读取由本类入口 importSource 内部处理 (读文本后递归);
 * okHttpClient → [OkHttpClientProviders.get]; R.string 文案 → 直接抛
 * `NoStackTraceException("格式不对")`; MutableLiveData → [MutableSharedFlow] (replay=1)。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), app 端持有本类实例,
 * errorLiveData/successLiveData 在 init 块桥接本类 [errorState]/[successState]。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
class ImportThemeViewModelShared(
    val scope: CoroutineScope,
) {
    /** 提交态归 VM: 对话框重建/重组后也不能重复提交同一批。 */
    private val submitSession = ImportSubmitSession()

    /** 提交态 (Writing/Committed/Failed) 归 VM: 重建对话框仍可读。 */
    val submitState: StateFlow<ImportSubmitState> = submitSession.state

    private data class FrozenSubmit(
        val checked: List<Boolean>,
        val items: List<ThemeConfigData>,
    )

    private var frozenSubmit: FrozenSubmit? = null

    fun beginSubmit(checked: List<Boolean>): Boolean {
        if (!submitSession.begin()) return false
        frozenSubmit = FrozenSubmit(checked = checked.toList(), items = allSources.map { it.copy() })
        return true
    }

    fun endSubmit() {
        submitSession.end()
        frozenSubmit = null
    }

    fun recordSubmitResult(result: ImportSubmitResult) = submitSession.record(result)


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
     * 值为 allSources.size (导入的主题配置总数), 0 表示解析无结果。
     * 事件流 (replay=1): 每次解析完成都投递, 数量与上次相同也不会被吞。
     */
    private val _successState = signalFlow<Int>()
    val successState: SharedFlow<Int> = _successState.asSharedFlow()

    /** 解析出的待导入主题配置列表 (importSourceAwait 累积, comparisonSource 比对, importSelect 写入)。 */
    val allSources = arrayListOf<ThemeConfigData>()

    /** 已有主题配置 (用于 comparisonSource 比对), null=新增。 */
    val checkSources = arrayListOf<ThemeConfigData?>()

    /** 解析时算出的默认勾选 (默认新增/与本地不同都选中)。勾选状态本身归 UI 层, 这里只提供初值。 */
    val defaultChecked = arrayListOf<Boolean>()

    /**
     * 导入选中的主题配置, 写入成功返回, 失败抛异常 (确认协议的唯一写入入口, 区分成功/错误)。
     *
     * 选中的项交 `ThemeConfigProviders.get().addConfigs(...)` 整批校验并一次保存
     * (app 端 ThemeConfigProviderImpl 内部把 [ThemeConfigData] 转回 `ThemeConfig.Config` 后调
     * `ThemeConfig.addConfigs(...)`, 保留按 themeName 去重 + 一次持久化行为)。
     * 任一配置无效或保存失败抛异常, 不发布内存列表; 业务在 IO 上执行。
     */
    suspend fun importSelectAwait(checked: List<Boolean>) = withContext(IoDispatcher) {
        val frozen = frozenSubmit
        val checkedList = frozen?.checked ?: checked
        val items = frozen?.items ?: allSources
        val selected = checkedList.mapIndexedNotNull { index, b ->
            if (b) items[index] else null
        }
        ThemeConfigProviders.get().addConfigs(selected)
        submitSession.committed(selected.size)
    }

    /**
     * 回调式导入入口 (与原版 `importSelect(finally)` 同形状): 成功/失败都回调 [finally],
     * 失败仅落日志, 结果由 [importSelectAwait] 的返回/异常区分。
     */
    /**
     * 导入主题配置, 对应 app 端 `importSource(text)`。
     *
     * # 实现细节保持
     *
     * - **输入契约**: source 必须是纯 URL 或 JSON 文本; file:// 地址载荷由
     *   [DeepLinkImportTarget.startImport] 读出文本后传入, 本类不做 Uri 解析。
     * - 调 [importSourceAwait] 处理 isJsonObject / isJsonArray / isAbsUrl 分支;
     * - `onError`: 推送 `_errorState.tryEmit("ImportError:${localizedMessage}")`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价);
     * - `onSuccess`: 调 [comparisonSource] 比对本地已有主题配置 (与 app 端原
     *   `onSuccess { comparisonSource() }` 一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 纯文本 (URL / JSON) 或 file:// 载荷 (由本类入口读成文本)
     */
    fun importSource(text: String) {
        Coroutine.async(scope = scope) {
            importSourceAwait(readImportPayload(text).trim())
        }.onError {
            _errorState.tryEmit("ImportError:${it.message}")
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
     * file:// 载荷在本类入口读成本地文本后递归 (对齐原 app 端 `text.isUri()` 分支语义),
     * 读取失败按 `NoStackTraceException` 上抛, 不静默降级。
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
     * - defaultChecked: source 为 null (新增) 或 `source != config` (与本地不同) 时选中
     *   (与 app 端原 `selectStatus.add(source == null || source != config)` 完全一致,
     *   这里 `source != config` 走 [ThemeConfigData.equals] 重写, 6 个字段逐一比较,
     *   与 app 端 ThemeConfig.Config.equals 行为一致);
     * - 推送 `_successState.tryEmit(allSources.size)` (替代 `successLiveData.postValue(allSources.size)`)。
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
                defaultChecked.add(source == null || source != config)
            }
            _successState.tryEmit(allSources.size)
        }
    }
}
