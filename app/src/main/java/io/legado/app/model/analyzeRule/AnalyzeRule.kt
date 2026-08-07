package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import com.script.jsdispatch.JsApi
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensionsJvm
import io.legado.app.model.webBook.BookInfoRefreshers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 解析规则获取结果
 *
 * app 端薄子类: 继承 shared 的 [AnalyzeRuleCore] 并实现 [JsExtensionsJvm],
 * 仅保留 android-only 方法 ([refreshTocUrl] override, 依赖 [BookSource]/[Book]).
 *
 * KSP @JsApi 分派表由本类生成, 通过 getAllFunctions() 继承链
 * 自动包含 [AnalyzeRuleCore] 的 public 方法, 方法名集合与原 AnalyzeRule 完全一致 (零 diff).
 *
 * - [getSource] / [evalJS] / [getString] / [getStringList] / [getElement] / [getElements]
 *   / [put] / [get] / [close] / [setContent] / [setBaseUrl] / [setRedirectUrl] / [splitSourceRule]
 *   等均继承自 [AnalyzeRuleCore], 无需 override.
 * - [io.legado.app.help.JsExtensionsJvm.getSource] 契约由 [AnalyzeRuleCore.getSource] (open fun) 满足,
 *   因 [io.legado.app.help.JsExtensionsCommon] 未声明 getSource, 无 diamond 冲突.
 * - [io.legado.app.help.JsExtensionsJvm.ajax] 与 [AnalyzeRuleCore.ajax] 形成 diamond 继承
 *   (JsExtensionsJvm.ajax 有默认实现, AnalyzeRuleCore.ajax 也是具体方法),
 *   需在子类显式 [ajax] override 转发到 super.ajax 选择 Core 实现 (传递 ruleData, 行为更完整).
 */
@Keep
@JsApi
@Suppress("unused", "RegExpRedundantEscape")
class AnalyzeRule(
    ruleData: RuleDataInterface? = null,
    source: BaseSource? = null,
    preUpdateJs: Boolean = false
) : AnalyzeRuleCore(ruleData, source, preUpdateJs), JsExtensionsJvm {

    /**
     * 解析 diamond 继承冲突:
     * - [AnalyzeRuleCore.ajax] (shared 具体方法, 使用 [AnalyzeUrlCore])
     * - [JsExtensionsJvm.ajax] (接口默认实现, 使用 [AnalyzeUrl])
     *
     * 显式选择 [AnalyzeRuleCore.ajax] (传递 ruleData 给 [AnalyzeUrlCore], 行为更完整),
     * 用 super<AnalyzeRuleCore>.ajax 转发到父类实现 (因 JsExtensionsJvm.ajax 也提供默认实现, 需指定父类).
     */
    override fun ajax(url: Any): String? = super<AnalyzeRuleCore>.ajax(url)

    /**
     * 更新tocUrl,有些书源目录url定期更新,可以在js调用更新
     *
     * P2 Step 2: 经 [BookInfoRefreshers] provider 反向调用 app 端 WebBook.getBookInfoAwait,
     * 解除 AnalyzeRule→WebBook 直接依赖, 为 AnalyzeRule 主体下沉 shared 做前置。
     *
     * 依赖 app 端 [BookSource]/[Book] 类型, 无法在 shared 中实现,
     * 由本类 override 提供 app 端原逻辑。
     */
    override fun refreshTocUrl() {
        val bookSource = getSource() as? BookSource
        val book = ruleData as? Book
        if (bookSource == null || book == null) return
        val refresher = BookInfoRefreshers.getOrNull() ?: return
        runBlocking(coroutineContext) {
            withTimeout(1800000) {
                refresher.refreshBookInfo(bookSource, book, false)
            }
        }
    }

}
