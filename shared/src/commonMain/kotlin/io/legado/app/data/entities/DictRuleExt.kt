package io.legado.app.data.entities

import io.legado.app.constant.AppConst
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import kotlinx.coroutines.currentCoroutineContext

/**
 * K5-c Phase 4: DictRule.search 扩展函数。
 *
 * DictRule 下沉 commonMain 后, search 方法依赖 AnalyzeUrlCore/AnalyzeRuleCore (已 commonMain 化),
 * 抽取为扩展函数。调用方式不变, 仅需 import 本包。
 *
 * commonMain 下沉说明:
 * - 原 jvmAndAndroidMain 用 kotlin.io.use 扩展 (java.io.Closeable.use)。
 *   commonMain 中 AnalyzeRuleCore 实现的是 expect interface Closeable
 *   (actual typealias 到 java.io.Closeable), 但 commonMain 编译期不可见 java.io.Closeable.use。
 *   改用 try-finally 等价改写: AnalyzeRuleCore() 无 source, close() 为空操作
 *   (见 AnalyzeRuleCore.close 注释), 行为与原 .use 完全一致。
 */
suspend fun DictRule.search(word: String): String {
    val analyzeUrl = AnalyzeUrlCore(
        urlRule,
        coroutineContext = currentCoroutineContext(),
        variables = mapOf(AppConst.JsVarName.KEY to word)
    )
    val body = analyzeUrl.getStrResponseAwait().body
    if (showRule.isBlank()) {
        return body!!
    }
    val analyzeRule = AnalyzeRuleCore().apply {
        coroutineContext = currentCoroutineContext()
    }
    return try {
        analyzeRule.getString(showRule, mContent = body)
    } finally {
        analyzeRule.close()
    }
}
