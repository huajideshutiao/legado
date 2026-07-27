package io.legado.app.data.entities

/**
 * Book 的最小只读视图接口（shared commonMain）。
 *
 * 抽出 [name] 属性, 让 shared 模块中的解析逻辑 (如 AnalyzeUrlCore) 在不依赖 app 实体类的前提下,
 * 通过智能转换访问书名。
 *
 * app 端 [Book] 通过 [BaseBook] 间接实现本接口 (var 可 override val)。
 */
interface BookLike {
    val name: String
}
