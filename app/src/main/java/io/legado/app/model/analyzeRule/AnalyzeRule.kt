package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import com.script.jsdispatch.JsApi
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.BookInfoRefreshers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 解析规则获取结果
 *
 * app 端薄子类: 继承 shared 的 [AnalyzeRuleCore],
 * 仅保留 android-only 方法 ([refreshTocUrl] override, 依赖 [BookSource]/[Book]).
 *
 * KSP @JsApi 分派表由本类生成, 通过 getAllFunctions() 继承链
 * 自动包含 [AnalyzeRuleCore] 的 public 方法, 方法名集合与原 AnalyzeRule 完全一致 (零 diff).
 *
 * - [getSource] / [evalJS] / [getString] / [getStringList] / [getElement] / [getElements]
 *   / [put] / [get] / [close] / [setContent] / [setBaseUrl] / [setRedirectUrl] / [splitSourceRule]
 *   等均继承自 [AnalyzeRuleCore], 无需 override.
 */
@Keep
@JsApi
@Suppress("unused", "RegExpRedundantEscape")
class AnalyzeRule(
    ruleData: RuleDataInterface? = null,
    source: BaseSource? = null,
    preUpdateJs: Boolean = false
) : AnalyzeRuleCore(ruleData, source, preUpdateJs) {

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
