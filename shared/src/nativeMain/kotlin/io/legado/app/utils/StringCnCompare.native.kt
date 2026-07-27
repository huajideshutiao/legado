package io.legado.app.utils

/**
 * iOS/鸿蒙 actual: Kotlin/Native 无 ICU 绑定 (android.icu / java.text.Collator 均不可用),
 * 退化为 Unicode 码点序——中文按码点而非拼音排序, 排序稳定不崩, 仅顺序观感与其他端不同。
 *
 * 平台真约束, 非简化实现。iOS 后续可用 `NSString.compare(options:range:locale:)`
 * 传 zh_CN NSLocale 拿到真拼音序, 但需把 actual 从 nativeMain 拆到 iosMain/ohosMain 两份。
 */
actual fun String.cnCompare(other: String): Int {
    return this.compareTo(other)
}
