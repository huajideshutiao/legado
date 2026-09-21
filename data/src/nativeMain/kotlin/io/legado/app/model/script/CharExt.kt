package io.legado.app.model.script

import kotlin.text.CharCategory

/**
 * K/N stdlib 未提供 Char.isJavaIdentifierStart/isJavaIdentifierPart (JVM-only API)
 * 的 native 自实现, 语义对齐 java.lang.Character.isJavaIdentifierStart/isJavaIdentifierPart
 * (Unicode 类别规则: 字母/货币符/连接符 + 数字/数字字母/其他数字/组合标记)。
 *
 * 放 nativeMain (非 .native.kt, 不参与 nativeInterop stage), 对 iosMain/ohosMain/leaf 均可见;
 * 与 NativeJsEngine 同包, 使用处无需显式 import。
 */
internal fun Char.isJavaIdentifierStart(): Boolean {
    if (this == '_' || this == '$') return true
    return when (category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER,
        CharCategory.TITLECASE_LETTER, CharCategory.MODIFIER_LETTER,
        CharCategory.OTHER_LETTER, CharCategory.CURRENCY_SYMBOL -> true

        else -> false
    }
}

internal fun Char.isJavaIdentifierPart(): Boolean {
    if (isJavaIdentifierStart()) return true
    return when (category) {
        CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER,
        CharCategory.OTHER_NUMBER, CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.COMBINING_SPACING_MARK, CharCategory.NON_SPACING_MARK,
        CharCategory.ENCLOSING_MARK -> true

        else -> false
    }
}
