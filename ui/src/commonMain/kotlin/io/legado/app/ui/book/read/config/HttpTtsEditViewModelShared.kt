package io.legado.app.ui.book.read.config

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.CoroutineScope

/**
 * HttpTTS 编辑 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `HttpTtsEditViewModel(app) : BaseViewModel(app)`: 四个方法
 * (initData/save/importFromClip/importSource) 仅依赖 DAO + 协程 + Toasters + 剪贴板 +
 * KS_JSON + ReadAloud.upReadAloudClass() 通知, 可下沉多端复用。DAO 走
 * [AppDbProviders.get].httpTTSDao; `execute{...}` 链式回调下沉为直接调 [Coroutine.async]。
 *
 * Android 专属依赖替换: Bundle 解析留 app 端 (转发到显式参数 [id]); 剪贴板经
 * [clipTextProvider] 注入; TTS 引擎刷新通知经 [onTtsChanged] 注入——shared 端 insert 后
 * 无条件调用, 宿主内部自行判断 `ReadAloud.ttsEngine == httpTTS.id.toString()` 再决定
 * 是否 upReadAloudClass (判断逻辑放宿主端, shared 只负责通知时机, 行为等价);
 * Toast → [Toasters.get]。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承)。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 * @param clipTextProvider 剪贴板文本提供者 (替代 `getClipText()`)
 * @param onTtsChanged TTS 引擎刷新通知 (替代 `ReadAloud.upReadAloudClass()`)
 */
class HttpTtsEditViewModelShared(
    private val scope: CoroutineScope,
    private val clipTextProvider: () -> String?,
    private val onTtsChanged: () -> Unit,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前编辑的 HttpTTS id (initData/save 后赋值), 对应 app 端 `var id: Long? = null`。
     *
     * setter 私有, 仅在 [initData] / [save] 内赋值 (与 app 端原行为一致)。
     * app 端通过 `val id: Long? get() = shared.id` 委托读取, 外部只读访问
     * (见 HttpTtsEditDialog.dataFromView `id = viewModel.id ?: ...`)。
     */
    var id: Long? = null
        private set

    /**
     * 初始化 HttpTTS 数据, 对应 app 端 `initData(arguments: Bundle?, success)`。
     *
     * # 实现细节保持
     *
     * - `id == null` (本类字段): 走参数 [id] 判断, 与 app 端 `if (id == null)` 完全一致
     *   (避免重复加载覆盖已编辑内容);
     * - 参数 [id] != null 且 != 0L: 走 `appDb.httpTTSDao.get(id)` 加载已有规则;
     * - 参数 [id] == null 或 == 0L (新建规则), 或本类 id 已有值 (编辑已保存过的规则):
     *   返回 null, 与 app 端 `return@execute null` 完全一致;
     * - 加载完成后回调 [success], 参数为查询结果 (null 时用空 HttpTTS 回调以显示表单,
     *   与 app 端 `success.invoke(it ?: HttpTTS())` 行为一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param id 规则 id (app 端从 Bundle 解析 "id" key 后传入), null/0L 表示新建
     * @param success 加载完成回调, 参数为最终 HttpTTS (新建时为空 HttpTTS)
     */
    fun initData(id: Long?, success: (httpTTS: HttpTTS) -> Unit) {
        Coroutine.async(scope = this.scope) {
            if (this@HttpTtsEditViewModelShared.id == null) {
                if (id != null && id != 0L) {
                    this@HttpTtsEditViewModelShared.id = id
                    return@async appDb.httpTTSDao.get(id)
                }
            }
            // id==null 且无有效参数 id（新建规则），或 id 已有值（编辑已保存过的规则），返回 null
            return@async null
        }.onSuccess {
            // 新建规则时查询结果为 null，需要用空 HttpTTS 回调以显示表单
            success.invoke(it ?: HttpTTS())
        }
    }

    /**
     * 保存 HttpTTS, 对应 app 端 `save(httpTTS, success)`。
     *
     * # 实现细节保持
     *
     * - `id = httpTTS.id` 同步赋值 (与 app 端完全一致, 在 execute/Couroutine 之前);
     * - `appDb.httpTTSDao.insert(httpTTS)` 写入;
     * - 调 [onTtsChanged]() 通知宿主刷新 TTS 引擎 (替代 app 端
     *   `if (ReadAloud.ttsEngine == httpTTS.id.toString()) ReadAloud.upReadAloudClass()`,
     *   判断逻辑由宿主 lambda 内部完成, shared 端只负责在 insert 后触发通知);
     * - 成功回调 [success];
     * - 无 onError 回调 (与 app 端原 `save` 一致, save 失败静默, 不 toast)。
     *
     * 业务在 IO 跑 (DAO 写入必须 IO), 回调在 mainDispatcher 跑
     * (与 BaseViewModel.execute 默认值一致)。
     *
     * @param httpTTS 待保存规则 (id 已存在则更新, 否则插入)
     * @param success 保存成功回调 (可空, 与 app 端 `success: (() -> Unit)? = null` 一致)
     */
    fun save(httpTTS: HttpTTS, success: (() -> Unit)? = null) {
        id = httpTTS.id
        Coroutine.async(scope = this.scope) {
            appDb.httpTTSDao.insert(httpTTS)
            // 替代 app 端 if (ReadAloud.ttsEngine == httpTTS.id.toString()) ReadAloud.upReadAloudClass()
            // 判断逻辑由宿主 onTtsChanged lambda 内部完成 (app 端读 lastSavedTts 字段判断)
            onTtsChanged()
        }.onSuccess {
            success?.invoke()
        }
    }

    /**
     * 从剪贴板导入 HttpTTS, 对应 app 端 `importFromClip(onSuccess)`。
     *
     * # 实现细节保持
     *
     * - 调 [clipTextProvider]() 取剪贴板文本 (替代 app 端 `getClipText()`);
     * - 空文本 `Toasters.get().toast("剪贴板为空")` (替代 `context.toastOnUi(...)`,
     *   同步调用, 安卓 Toaster 实现内部切主线程, 行为等价);
     * - 非空转发到 [importSource]。
     *
     * 注意: `clipTextProvider()` 与 toast 均为同步调用 (在调用方线程, 通常 main),
     * 与 app 端原 `getClipText()` + `context.toastOnUi(...)` 同步行为一致
     * (Android ClipboardManager.primaryClip 为 Binder 调用可跨线程,
     * 安卓 Toaster 实现内部切主线程)。
     *
     * @param onSuccess 解析成功回调, 参数为从剪贴板文本反序列化的 HttpTTS
     */
    fun importFromClip(onSuccess: (httpTTS: HttpTTS) -> Unit) {
        val text = clipTextProvider()
        if (text.isNullOrBlank()) {
            // 替代 context.toastOnUi("剪贴板为空"), Toasters.get() 已下沉 commonMain
            Toasters.get().toast("剪贴板为空")
        } else {
            importSource(text, onSuccess)
        }
    }

    /**
     * 从文本导入 HttpTTS, 对应 app 端 `importSource(text, onSuccess)`。
     *
     * # 实现细节保持
     *
     * - `text1 = text.trim()` 预处理 (与 app 端完全一致);
     * - JsonObject 分支: `KS_JSON.decodeFromString<HttpTTS>(text1)` (与 app 端一致,
     *   KS_JSON 已下沉 commonMain, 行为对齐原 `io.legado.app.utils.KS_JSON`);
     * - JsonArray 分支: `KS_JSON.decodeFromString<List<HttpTTS>>(text1).first()`
     *   (与 app 端一致, 取数组首个元素);
     * - 其他格式: 抛 `NoStackTraceException("格式不对")` (与 app 端完全一致);
     * - 成功回调 [onSuccess];
     * - 失败 `Toasters.get().toast(it.message ?: "Error")`
     *   (替代 `context.toastOnUi(it.message)`, 下沉惯例 null 回落 "Error")。
     *
     * 业务在 IO 跑 (JSON 解析可重), 回调在 mainDispatcher 跑
     * (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 待解析文本 (JsonObject 单条 / JsonArray 列表)
     * @param onSuccess 解析成功回调, 参数为反序列化的 HttpTTS
     */
    fun importSource(text: String, onSuccess: (httpTTS: HttpTTS) -> Unit) {
        val text1 = text.trim()
        Coroutine.async(scope = this.scope) {
            when {
                text1.isJsonObject() -> {
                    KS_JSON.decodeFromString<HttpTTS>(text1)
                }
                text1.isJsonArray() -> {
                    KS_JSON.decodeFromString<List<HttpTTS>>(text1).first()
                }
                else -> {
                    throw NoStackTraceException("格式不对")
                }
            }
        }.onSuccess {
            onSuccess.invoke(it)
        }.onError {
            // 替代 context.toastOnUi(it.message), Toasters.get() 已下沉 commonMain
            Toasters.get().toast(it.message ?: "Error")
        }
    }
}
