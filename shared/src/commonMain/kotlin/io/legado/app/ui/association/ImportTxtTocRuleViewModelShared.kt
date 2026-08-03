package io.legado.app.ui.association

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.NoStackTraceException
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 导入 TxtTocRule (目录规则) VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `ImportTxtTocRuleViewModel(app) : BaseViewModel(app)`: 2 个公开方法
 * (importSelect/importSource) + importSourceAwait/importSourceUrl/comparisonSource,
 * 仅依赖 DAO + 协程 + okHttpClient + GSON, 可下沉多端复用。DAO 走
 * [AppDbProviders.get].txtTocRuleDao; `execute{...}` 链式回调下沉为直接调 [Coroutine.async]。
 * 方法行为与 app 端一致: URL/JSON 分支解析 (isJsonObject/isJsonArray/isAbsUrl),
 * URL 以 `#requestWithoutUA` 结尾时截断并设 `User-Agent: null` 头。
 *
 * Android 专属依赖替换: Uri 读取留 app 端 (app 端 importSource 先判 isUri 读文本再转发);
 * okHttpClient → [OkHttpClientProviders.get]; R.string 文案 → 直接抛
 * `NoStackTraceException("格式不对")`; MutableLiveData → [MutableSharedFlow] (replay=1)。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), app 端持有本类实例,
 * errorLiveData/successLiveData 在 init 块桥接 [errorState]/[successState]。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
class ImportTxtTocRuleViewModelShared(
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
     * 值为 allSources.size (导入的目录规则总数), 0 表示解析无结果。
     * 事件流 (replay=1): 每次解析完成都投递, 数量与上次相同也不会被吞。
     */
    private val _successState = signalFlow<Int>()
    val successState: SharedFlow<Int> = _successState.asSharedFlow()

    /** 解析出的待导入 TxtTocRule 列表 (importSourceAwait 累积, comparisonSource 比对, importSelect 写入)。 */
    val allSources = arrayListOf<TxtTocRule>()

    /** 已有 TxtTocRule (用于 comparisonSource 比对), null=新增。 */
    val checkSources = arrayListOf<TxtTocRule?>()

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
     * 导入选中的 TxtTocRule, 对应 app 端 `importSelect(finally)`。
     *
     * # 实现细节保持
     *
     * - 遍历 [selectStatus], 选中的项加入 selectSource 列表;
     * - `appDb.txtTocRuleDao.insert(*selectSource.toTypedArray())` 批量写入;
     * - `onFinally` 回调 [finally] (与 app 端 `onFinally { finally.invoke() }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param finally 导入完成回调 (无论成功/失败均触发, 与 app 端 onFinally 一致)
     */
    fun importSelect(finally: () -> Unit) {
        Coroutine.async(scope = scope) {
            val selectSource = arrayListOf<TxtTocRule>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    selectSource.add(allSources[index])
                }
            }
            appDb.txtTocRuleDao.insert(*selectSource.toTypedArray())
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 导入 TxtTocRule, 对应 app 端 `importSource(text)`。
     *
     * # 实现细节保持
     *
     * - **Uri 分支留 app 端**: app 端 `ImportTxtTocRuleViewModel.importSource(text)` 先检查
     *   `mText.isUri()`, 是 Uri 则读取文本后转发到本类, 否则直接转发到本类。
     *   本类仅处理纯文本 (URL/JSON) 分支。
     * - 调 [importSourceAwait] 处理 isJsonObject / isJsonArray / isAbsUrl 分支;
     * - `onError`: 推送 `_errorState.tryEmit("ImportError:${localizedMessage}")`
     *   + `AppLog.put(...)` (替代 `errorLiveData.postValue(...)`, 行为等价);
     * - `onSuccess`: 调 [comparisonSource] 比对本地已有 TxtTocRule (与 app 端原
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
            _errorState.tryEmit("ImportError:${it.message}")
            AppLog.put("ImportError:${it.message}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    /**
     * 解析文本为 TxtTocRule 并累积到 [allSources], 对应 app 端 `private suspend fun importSourceAwait(text)`。
     *
     * # 实现细节保持
     *
     * - isJsonObject: 用 `GSON.fromJsonObject<TxtTocRule>(text).getOrThrow()` 解析单对象;
     * - isJsonArray: 用 `GSON.fromJsonArray<TxtTocRule>(text).getOrThrow()` 解析数组;
     * - isAbsUrl: 走 [importSourceUrl] 网络请求 (OkHttpClientProviders + newCallResponseBody),
     *   下载的 JSON 文本递归调 [importSourceAwait];
     * - else: 抛 `NoStackTraceException("格式不对")` (替代 `context.getString(R.string.wrong_format)`,
     *   与 shared 端其他下沉 VM 文案一致)。
     *
     * 注: 原 app 端有 `text.isUri()` 分支 (`text.toUri().readText(appCtx)` 后递归),
     * commonMain 不可用, 已留 app 端 `ImportTxtTocRuleViewModel.importSource` 入口预处理。
     */
    private suspend fun importSourceAwait(text: String) {
        when {
            text.isJsonObject() -> {
                GSON.fromJsonObject<TxtTocRule>(text).getOrThrow().let {
                    allSources.add(it)
                }
            }

            text.isJsonArray() -> GSON.fromJsonArray<TxtTocRule>(text).getOrThrow()
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
     * 从 URL 下载 TxtTocRule JSON, 对应 app 端 `private suspend fun importSourceUrl(url)`。
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
     * 比对本地已有 TxtTocRule, 对应 app 端 `private fun comparisonSource()`。
     *
     * # 实现细节保持
     *
     * - 遍历 [allSources], 调 `appDb.txtTocRuleDao.get(it.id)` 查本地;
     * - selectStatus: source 为 null (新增) 或 `it != source` (与本地不同) 时选中
     *   (与 app 端原 `selectStatus.add(source == null || it != source)` 完全一致);
     * - 推送 `_successState.tryEmit(allSources.size)` (替代 `successLiveData.postValue(allSources.size)`)。
     *
     * 业务在 IO 跑 (DAO 查询必须 IO), 与 BaseViewModel.execute 默认值一致。
     */
    private fun comparisonSource() {
        Coroutine.async(scope = scope) {
            allSources.forEach {
                val source = appDb.txtTocRuleDao.get(it.id)
                checkSources.add(source)
                selectStatus.add(source == null || it != source)
            }
            _successState.tryEmit(allSources.size)
        }
    }
}
