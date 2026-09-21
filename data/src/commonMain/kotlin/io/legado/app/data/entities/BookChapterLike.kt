package io.legado.app.data.entities

import io.legado.app.model.analyzeRule.RuleDataInterface

/**
 * BookChapter 的最小视图接口（shared commonMain）。
 *
 * 在 [RuleDataInterface] 之上扩展 [title] 属性, 让 shared 模块中的解析逻辑 (如 AnalyzeUrlCore)
 * 在不依赖 app 实体类的前提下, 通过智能转换访问章节标题和变量存取。
 *
 * app 端 [BookChapter] 实现本接口 (var 可 override val)。
 */
interface BookChapterLike : RuleDataInterface {
    val title: String
}
