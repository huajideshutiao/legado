package io.legado.app.ui.widget.text

import io.legado.app.ui.platform.stringRes

data class EditEntity(
    var key: String,
    var value: String?,
    var hint: String,
    val viewType: Int = ViewType.text,
    val selections: List<Pair<String, String?>>? = null,
    val span: Int = 2,
    /**
     * CodeView 语法高亮 pattern 位掩码，仅 [ViewType.code] 类型生效。
     * 取值见 [CodePattern]，默认 0 表示无 pattern。
     */
    val codePatterns: Int = 0
) {

    constructor(
        key: String,
        value: String?,
        hint: Int,
        viewType: Int = ViewType.text,
        selections: List<Pair<String, String?>>? = null,
        span: Int = 2,
        codePatterns: Int = 0
    ) : this(
        key,
        value,
        stringRes(hint),
        viewType,
        selections,
        span,
        codePatterns
    )

    /** 文本字段值：trim 后空串视为 null */
    val text: String? get() = value?.takeIf { it.isNotBlank() }

    /** 复选框值：value == "true" */
    val boolValue: Boolean get() = value == "true"

    /** 下拉框选中下标：value.toIntOrNull() ?: 0 */
    val intValue: Int get() = value?.toIntOrNull() ?: 0

    object ViewType {

        const val text = 0
        const val checkBox = 1
        const val spinner = 2
        /** 需要语法高亮的代码字段，使用 [CodeView] 渲染 */
        const val code = 3

    }

    /**
     * CodeView 语法高亮 pattern 位掩码常量。
     * 用法：`codePatterns = CodePattern.legado or CodePattern.js`
     */
    object CodePattern {

        const val legado = 1
        const val json = 2
        const val js = 4
        const val all = legado or json or js

    }

}
