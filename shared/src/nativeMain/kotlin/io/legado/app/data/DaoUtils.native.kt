package io.legado.app.data

/**
 * DaoUtils 中文字符串排序的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/data/DaoUtils.shared.kt expect 注释。
 * 纯 Kotlin 实现: 无 android.icu.text.Collator / java.text.Collator,
 * 退化用 Unicode 码点比较 (功能受限: 中文按 Unicode 序而非拼音序, 但不崩, 排序稳定)。
 *
 * 行为对比:
 * - jvmMain: java.text.Collator.getInstance(Locale.CHINA) - 中文按拼音序
 * - androidMain: android.icu.text.Collator - 中文按拼音序
 * - iOS/鸿蒙 (本 actual): Unicode 码点序 - 中文按 Unicode 序 (与拼音序不一致)
 *
 * 调用方 dealGroups 仅用于书源分组排序展示, 顺序差异不影响功能, 仅视觉体验略降。
 */
internal actual fun String.cnCompareGroups(other: String): Int {
    return this.compareTo(other)
}
