package io.legado.app.ui.replace.edit

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.getClipText

/**
 * 替换规则编辑 VM (Android 端组合委托包装)。
 *
 * # 背景
 *
 * 对照 [ReplaceEditViewModelShared] (commonMain 共享核心), 本类仅做 Android 专属适配:
 * - **Intent 解析**: 原 `initData(intent, finally)` 用 `intent.getLongExtra("id", -1)` /
 *   `getStringExtra("pattern")` / `getBooleanExtra("isRegex", false)` /
 *   `getStringExtra("scope")` 解析 Bundle, commonMain 不能直接下沉, 留在本类
 *   解析后转发到 [shared.initData] 的显式参数;
 * - **剪贴板访问**: `getClipText()` 是 Android ClipboardManager 扩展, 不能下沉,
 *   通过 lambda `{ getClipText() }` 注入 [shared] 的 `clipTextProvider`;
 * - **scope 注入**: `viewModelScope` (BaseViewModel 来自 AndroidViewModel) 传入 [shared]。
 *
 * # 行为等价性
 *
 * - [initData] 解析 intent 后转发, `replaceRule` 在回调内同步赋值 (与原 `replaceRule = ...`
 *   在 execute block 内赋值语义等价, 主线程回调保证 UI 读取安全);
 * - [pasteRule] / [save] 直接转发到 [shared], Toast / 错误日志在 shared 内通过
 *   `Toasters.get().toast(...)` + `printOnDebug()` 完成 (替代原 `context.toastOnUi` +
 *   `it.printOnDebug()`), 调用方接口不变;
 * - `var replaceRule: ReplaceRule?` 公开字段保留, Activity 通过 `viewModel.replaceRule`
 *   读取组装表单, 与原 API 一致。
 *
 * # 设计参考
 *
 * 对照同模块 [io.legado.app.ui.replace.ReplaceRuleViewModel] 的组合委托模式
 * (持有 `ReplaceRuleViewModelShared`), 以及
 * [io.legado.app.ui.dict.rule.DictRuleViewModel] (持有 `DictRuleViewModelShared`)。
 */
class ReplaceEditViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心, 注入 `viewModelScope` + `getClipText()` lambda。
     *
     * - `viewModelScope`: AndroidViewModel 提供, Activity 销毁时自动取消;
     * - `{ getClipText() }`: 替代原 `pasteRule` 内的 `getClipText()` 直接调用,
     *   commonMain 端通过 `clipTextProvider()` 间接访问 (Android ClipboardManager
     *   为 Binder 调用, 可跨线程, 与 shared 端业务在 IO 跑兼容)。
     */
    private val shared: ReplaceEditViewModelShared = ReplaceEditViewModelShared(
        scope = viewModelScope,
        clipTextProvider = { getClipText() },
    )

    /**
     * 当前编辑的规则 (initData 后非空), 由 [shared] 同步赋值。
     *
     * Activity 通过 `viewModel.replaceRule` 读取组装表单 (见
     * `ReplaceEditActivity.getReplaceRule`)。原 `var replaceRule: ReplaceRule? = null`
     * 字段下沉后改为委托 [shared.replaceRule] (private set), app 端只读访问。
     * Activity 实际只读不写, 行为等价。
     */
    val replaceRule: ReplaceRule? get() = shared.replaceRule

    /**
     * 初始化规则数据, 对应原 `initData(intent, finally)`。
     *
     * 解析 intent 的 4 个字段 (id/pattern/isRegex/scope) 后转发到 [shared.initData],
     * 加载完成回调 [finally] 由 shared 内部 onFinally 触发 (replaceRule 为 null 时不回调,
     * 与原 `onFinally { replaceRule?.let { finally(it) } }` 行为一致)。
     */
    fun initData(intent: Intent, finally: (replaceRule: ReplaceRule) -> Unit) {
        val id = intent.getLongExtra("id", -1)
        val pattern = intent.getStringExtra("pattern")
        val isRegex = intent.getBooleanExtra("isRegex", false)
        val scope = intent.getStringExtra("scope")
        shared.initData(
            id = id,
            pattern = pattern,
            isRegex = isRegex,
            scope = scope,
            finally = finally,
        )
    }

    /**
     * 粘贴规则, 对应原 `pasteRule(success)`。
     *
     * 直接转发到 [shared.pasteRule], 由 shared 内部调 `clipTextProvider()` (即
     * `getClipText()`) 取剪贴板文本并解析, 成功回调 [success], 失败 toast + printOnDebug。
     */
    fun pasteRule(success: (ReplaceRule) -> Unit) {
        shared.pasteRule(success)
    }

    /**
     * 保存规则, 对应原 `save(replaceRule, success)`。
     *
     * 直接转发到 [shared.save], 由 shared 内部 checkValid + insert, 成功回调 [success],
     * 失败 toast。
     */
    fun save(replaceRule: ReplaceRule, success: () -> Unit) {
        shared.save(replaceRule, success)
    }

}
