package io.legado.app.ui.book.read.config

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.ReadAloud
import io.legado.app.utils.getClipText

/**
 * HttpTTS 编辑 VM (Android 端组合委托包装)。
 *
 * # 背景
 *
 * 对照 [HttpTtsEditViewModelShared] (commonMain 共享核心), 本类仅做 Android 专属适配:
 * - **Bundle 解析**: 原 `initData(arguments: Bundle?, success)` 用
 *   `arguments?.getLong("id")` 解析 Bundle, commonMain 不能直接下沉, 留在本类
 *   解析后转发到 [shared.initData] 的显式参数 `id`;
 * - **剪贴板访问**: `getClipText()` 是 Android ClipboardManager 扩展, 不能下沉,
 *   通过 lambda `{ getClipText() }` 注入 [shared] 的 `clipTextProvider`;
 * - **TTS 引擎刷新**: `ReadAloud.upReadAloudClass()` 依赖 app 端 `ReadAloud` 单例
 *   (未下沉), 通过 lambda 注入 [shared] 的 `onTtsChanged`; 原 save 内的判断条件
 *   `if (ReadAloud.ttsEngine == httpTTS.id.toString())` 也留本类 lambda 内,
 *   通过 [lastSavedTts] 字段访问刚保存的 HttpTTS (shared 端 onTtsChanged 无参,
 *   无法直接传入 httpTTS);
 * - **scope 注入**: `viewModelScope` (BaseViewModel 来自 AndroidViewModel) 传入 [shared]。
 *
 * # 行为等价性
 *
 * - [initData] 解析 Bundle 后转发, 行为与原完全一致;
 * - [save] 转发前先记录 [lastSavedTts], 供 `onTtsChanged` lambda 判断条件
 *   (时序: lastSavedTts 赋值在 main 线程, shared.save 内 Coroutine.async 在 IO
 *   跑 insert, onTtsChanged 在 mainDispatcher 回调, lastSavedTts 已就绪, 行为等价);
 * - [importFromClip] / [importSource] 直接转发到 [shared], Toast 在 shared 内通过
 *   `Toasters.get().toast(...)` 完成 (替代原 `context.toastOnUi`), 调用方接口不变;
 * - `id` 字段保留 public 可读 (原 `var id: Long? = null` 外部只读, 见
 *   `HttpTtsEditDialog.dataFromView` 的 `viewModel.id ?: System.currentTimeMillis()`),
 *   下沉后改为委托 `shared.id` (private set), app 端只读访问, 行为等价。
 *
 * # 设计参考
 *
 * 对照同模块 [io.legado.app.ui.replace.edit.ReplaceEditViewModel] 的组合委托模式
 * (持有 `ReplaceEditViewModelShared`), 以及
 * [io.legado.app.ui.book.toc.rule.TxtTocRuleEditDialog.ViewModel] (持有
 * `TxtTocRuleEditViewModelShared`)。
 */
class HttpTtsEditViewModel(app: Application) : BaseViewModel(app) {

    /**
     * 最近一次保存的 HttpTTS, 供 [shared] 的 `onTtsChanged` lambda 判断条件用。
     *
     * shared 端 `onTtsChanged: () -> Unit` 无参, 无法直接传入 httpTTS; app 端在
     * [save] 转发前同步赋值本字段, `onTtsChanged` lambda 内通过本字段读取刚保存的
     * HttpTTS, 判断 `ReadAloud.ttsEngine == it.id.toString()` 后决定是否调
     * `ReadAloud.upReadAloudClass()` (与原 save 内 `if (...) ReadAloud.upReadAloudClass()`
     * 行为等价)。
     *
     * 时序保证: [save] 在 main 线程同步赋值本字段后再调 `shared.save`,
     * `shared.save` 内 `Coroutine.async` 在 IO 跑 insert, `onTtsChanged` 在
     * mainDispatcher 回调, 此时本字段已就绪 (赋值先于回调), 行为等价。
     */
    private var lastSavedTts: HttpTTS? = null

    /**
     * 共享核心, 注入 `viewModelScope` + `getClipText()` lambda + onTtsChanged lambda。
     *
     * - `viewModelScope`: AndroidViewModel 提供, Activity/Dialog 销毁时自动取消;
     * - `{ getClipText() }`: 替代原 `importFromClip` 内的 `getClipText()` 直接调用,
     *   commonMain 端通过 `clipTextProvider()` 间接访问 (Android ClipboardManager
     *   为 Binder 调用, 可跨线程, 与 shared 端业务在 IO 跑兼容);
     * - `onTtsChanged` lambda: 替代原 `save` 内
     *   `if (ReadAloud.ttsEngine == httpTTS.id.toString()) ReadAloud.upReadAloudClass()`,
     *   通过 [lastSavedTts] 字段读取刚保存的 HttpTTS 判断条件。
     */
    private val shared: HttpTtsEditViewModelShared = HttpTtsEditViewModelShared(
        scope = viewModelScope,
        clipTextProvider = { getClipText() },
        onTtsChanged = {
            // 替代原 save 内 if (ReadAloud.ttsEngine == httpTTS.id.toString()) ReadAloud.upReadAloudClass()
            // shared 端 onTtsChanged 无参, 通过 lastSavedTts 字段读取刚保存的 HttpTTS
            val saved = lastSavedTts
            if (saved != null && ReadAloud.ttsEngine == saved.id.toString()) {
                ReadAloud.upReadAloudClass()
            }
        },
    )

    /**
     * 当前编辑的 HttpTTS id (initData/save 后赋值), 由 [shared] 同步赋值。
     *
     * 原 `var id: Long? = null` 公开字段, 外部 (HttpTtsEditDialog.dataFromView)
     * 只读访问 `viewModel.id ?: System.currentTimeMillis()`。下沉后改为委托
     * [shared.id] (private set), app 端只读访问, 行为等价 (外部从未写入, 见
     * HttpTtsEditDialog 调用方)。
     */
    val id: Long? get() = shared.id

    /**
     * 初始化 HttpTTS 数据, 对应原 `initData(arguments: Bundle?, success)`。
     *
     * 解析 Bundle 的 "id" 字段后转发到 [shared.initData], 加载完成回调 [success]
     * 由 shared 内部 onSuccess 触发 (参数为查询结果, null 时用空 HttpTTS 回调,
     * 与原 `success.invoke(it ?: HttpTTS())` 行为一致)。
     *
     * @param arguments Bundle, 含 "id" key (null/0L 表示新建)
     * @param success 加载完成回调, 参数为最终 HttpTTS (新建时为空 HttpTTS)
     */
    fun initData(arguments: Bundle?, success: (httpTTS: HttpTTS) -> Unit) {
        val id = arguments?.getLong("id")
        shared.initData(id, success)
    }

    /**
     * 保存 HttpTTS, 对应原 `save(httpTTS, success)`。
     *
     * 先记录 [lastSavedTts] (供 `onTtsChanged` lambda 判断条件), 再转发到
     * [shared.save], 由 shared 内部 insert + onTtsChanged + success 回调。
     *
     * @param httpTTS 待保存规则 (id 已存在则更新, 否则插入)
     * @param success 保存成功回调 (可空, 与原 `success: (() -> Unit)? = null` 一致)
     */
    fun save(httpTTS: HttpTTS, success: (() -> Unit)? = null) {
        // 供 shared.onTtsChanged lambda 读取, 判断 ReadAloud.ttsEngine == httpTTS.id.toString()
        lastSavedTts = httpTTS
        shared.save(httpTTS, success)
    }

    /**
     * 从剪贴板导入 HttpTTS, 对应原 `importFromClip(onSuccess)`。
     *
     * 直接转发到 [shared.importFromClip], 由 shared 内部调 `clipTextProvider()` (即
     * `getClipText()`) 取剪贴板文本, 空文本 toast "剪贴板为空", 非空转发到
     * `importSource`, 成功回调 [onSuccess], 失败 toast 错误信息。
     *
     * @param onSuccess 解析成功回调, 参数为从剪贴板文本反序列化的 HttpTTS
     */
    fun importFromClip(onSuccess: (httpTTS: HttpTTS) -> Unit) {
        shared.importFromClip(onSuccess)
    }

    /**
     * 从文本导入 HttpTTS, 对应原 `importSource(text, onSuccess)`。
     *
     * 直接转发到 [shared.importSource], 由 shared 内部解析 JSON (JsonObject 单条 /
     * JsonArray 列表取首条), 成功回调 [onSuccess], 失败 toast 错误信息。
     *
     * @param text 待解析文本 (JsonObject 单条 / JsonArray 列表)
     * @param onSuccess 解析成功回调, 参数为反序列化的 HttpTTS
     */
    fun importSource(text: String, onSuccess: (httpTTS: HttpTTS) -> Unit) {
        shared.importSource(text, onSuccess)
    }

}
