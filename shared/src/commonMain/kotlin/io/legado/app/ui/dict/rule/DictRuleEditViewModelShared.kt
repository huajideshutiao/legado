package io.legado.app.ui.dict.rule

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toJson
import kotlinx.coroutines.CoroutineScope

/**
 * 字典规则编辑 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `DictRuleEditDialog.DictRuleEditViewModel(application: Application) :
 * BaseViewModel(application)` (内部类):
 * - 四个方法 (initData / save / copyRule / pasteRule) 仅依赖 DAO + 协程 + Toasters +
 *   剪贴板读写 + GSON, 可以下沉 commonMain 供多端复用 (Android / Desktop / iOS / 鸿蒙)。
 * - DAO 访问走 [AppDbProviders.get].dictRuleDao (宿主启动时由 app 端注册
 *   AppDbAccessorImpl, 已暴露 dictRuleDao)。
 * - 原 `execute { ... }.onFinally { ... }` / `execute { ... }.onSuccess { ... }
 *   .onError { ... }` (BaseViewModel 内委托 [Coroutine.async]) 下沉后直接调
 *   [Coroutine.async], 保留链式 onFinally/onSuccess/onError 回调结构, 行为等价
 *   (业务 context=IO, 回调 executeContext=mainDispatcher, 与 BaseViewModel.execute
 *   默认值一致)。
 *
 * # Android 专属依赖替换
 *
 * - **viewModelScope**: BaseViewModel 来自 Android ViewModel, 不能下沉,
 *   通过构造函数 [scope] 注入 (Android = `viewModelScope` / 桌面 = 应用主作用域)。
 * - **剪贴板读取**: 原 `getClipText()` (Android ClipboardManager) 不能下沉,
 *   通过构造函数 lambda [clipTextProvider] 注入:
 *   - app 端实现 `{ getClipText() }` (委托 utils.ContextExtensions.getClipText);
 *   - desktop 端实现用 `Toolkit.getDefaultToolkit().systemClipboard.getData(...)`;
 *   - iOS/鸿蒙留宿主自实现。
 * - **剪贴板写入**: 原 `context.sendToClip(text)` (Android ClipboardManager) 不能下沉,
 *   通过构造函数 lambda [clipTextSink] 注入:
 *   - app 端实现 `{ text -> context.sendToClip(text) }`;
 *   - desktop 端实现用 `Toolkit.getDefaultToolkit().systemClipboard.setContents(...)`。
 * - **Toast 提示**: 原 `context.toastOnUi(msg)` → [Toasters.get].toast(msg)
 *   (Toaster 接口已下沉 commonMain, androidMain 注册的实现内部切主线程,
 *   与 `context.toastOnUi` 行为等价)。
 * - **GSON**: shared 端 [GSON] 已是 KS_JSON 的别名 (见 GsonExtensions.kt),
 *   [fromJsonObject] / [toJson] 扩展也已下沉 commonMain, 直接复用, 与 app 端行为一致。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared]):
 * - app 端 `DictRuleEditDialog.DictRuleEditViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` + `{ getClipText() }` +
 *   `{ text -> context.sendToClip(text) }` 注入;
 * - desktop 端在 Compose `remember` 中构造本类, 注入应用 scope + AWT Clipboard lambda。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param clipTextProvider 剪贴板文本提供者 (替代 `getClipText()`):
 *   - app 端实现 `{ getClipText() }`
 *   - desktop 端实现 `Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String`
 * @param clipTextSink 剪贴板文本写入器 (替代 `context.sendToClip(text)`):
 *   - app 端实现 `{ text -> context.sendToClip(text) }`
 *   - desktop 端实现 `{ text -> ... systemClipboard.setContents(StringSelection(text), null) }`
 */
class DictRuleEditViewModelShared(
    private val scope: CoroutineScope,
    private val clipTextProvider: () -> String?,
    private val clipTextSink: (String) -> Unit,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前编辑的规则 (initData 后非空), 对应 app 端 `var dictRule: DictRule? = null`。
     *
     * setter 公开 (与 app 端默认 var 签名一致), 供宿主读取组装表单
     * (app 端 `getDictRule()` 中 `viewModel.dictRule?.copy()` 读)。
     */
    var dictRule: DictRule? = null

    /**
     * 初始化规则数据, 对应 app 端 `initData(name, onFinally)`。
     *
     * # 实现细节保持
     *
     * - 仅当 `dictRule == null && name != null` 时走 `appDb.dictRuleDao.getByName(name)`
     *   加载已有规则 (与 app 端完全一致, 避免重复加载覆盖已编辑内容);
     * - 加载完成后回调 [onFinally] (无参, 与 app 端 `onFinally.invoke()` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param name 规则名, null 表示新增
     * @param onFinally 加载完成回调 (无参)
     */
    fun initData(name: String?, onFinally: () -> Unit) {
        Coroutine.async(scope = this.scope) {
            if (dictRule == null && name != null) {
                dictRule = appDb.dictRuleDao.getByName(name)
            }
        }.onFinally {
            onFinally.invoke()
        }
    }

    /**
     * 保存规则, 对应 app 端 `save(newDictRule, onFinally)`。
     *
     * # 实现细节保持
     *
     * - 先 `dictRule?.let { appDb.dictRuleDao.delete(it) }` 删除旧规则
     *   (与 app 端完全一致, 编辑场景下 dictRule 非 null 时先删后插, 实现改名等场景);
     * - `appDb.dictRuleDao.insert(newDictRule)` 插入新规则;
     * - `dictRule = newDictRule` 更新当前引用 (供下次 save 再删旧);
     * - 完成回调 [onFinally] (无参, 与 app 端 `onFinally.invoke()` 等价,
     *   注意原 app 端用 onFinally 而非 onSuccess, 故失败也会回调)。
     *
     * 业务在 IO 跑 (DAO 写入必须 IO), 回调在 mainDispatcher 跑
     * (与 BaseViewModel.execute 默认值一致)。
     *
     * @param newDictRule 待保存规则 (会先删旧 dictRule 再插新)
     * @param onFinally 完成回调 (无参, 成功/失败均触发)
     */
    fun save(newDictRule: DictRule, onFinally: () -> Unit) {
        Coroutine.async(scope = this.scope) {
            dictRule?.let {
                appDb.dictRuleDao.delete(it)
            }
            appDb.dictRuleDao.insert(newDictRule)
            dictRule = newDictRule
        }.onFinally {
            onFinally.invoke()
        }
    }

    /**
     * 复制规则到剪贴板, 对应 app 端 `copyRule(dictRule)`。
     *
     * # 实现细节保持
     *
     * - 调 `GSON.toJson(dictRule)` 序列化为 JSON (与 app 端完全一致);
     * - 调 [clipTextSink] 写入剪贴板 (替代 `context.sendToClip(...)`);
     * - 同步执行 (与 app 端一致, 不进协程)。
     *
     * @param dictRule 待复制规则 (参数名遮蔽属性 dictRule, 与 app 端原签名保持一致)
     */
    fun copyRule(dictRule: DictRule) {
        clipTextSink(GSON.toJson(dictRule))
    }

    /**
     * 粘贴规则, 对应 app 端 `pasteRule(success)`。
     *
     * # 实现细节保持
     *
     * - **入口同步判空** (与 app 端完全一致, 不"偷懒"挪进协程):
     *   先调 [clipTextProvider]() 取剪贴板文本 (替代 `getClipText()`),
     *   空文本调 `Toasters.get().toast("剪贴板没有内容")` (替代
     *   `context.toastOnUi(...)`) 后 return, 不进协程;
     * - 非空则进协程, 用 `GSON.fromJsonObject<DictRule>(text).getOrThrow()` 解析
     *   (与 app 端完全一致, getOrThrow 而非 getOrNull);
     * - 成功回调 [success];
     * - 失败 `Toasters.get().toast("格式不对")` (替代 `context.toastOnUi(...)`,
     *   注意原 app 端 onError 仅 toast 不 printOnDebug, 保持一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param success 解析成功回调, 参数为从剪贴板文本反序列化的 DictRule
     */
    fun pasteRule(success: (DictRule) -> Unit) {
        // 入口同步判空 (与 app 端原逻辑一致, 在 execute 之外)
        val text = clipTextProvider()
        if (text.isNullOrBlank()) {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            Toasters.get().toast("剪贴板没有内容")
            return
        }
        Coroutine.async(scope = this.scope) {
            GSON.fromJsonObject<DictRule>(text).getOrThrow()
        }.onSuccess {
            success.invoke(it)
        }.onError {
            // 替代 context.toastOnUi(msg), 与 app 端原 onError 一致 (仅 toast, 不 printOnDebug)
            Toasters.get().toast("格式不对")
        }
    }
}
