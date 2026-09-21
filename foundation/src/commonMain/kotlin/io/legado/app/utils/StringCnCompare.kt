package io.legado.app.utils

/**
 * 中文字符串排序 (拼音序), 由 app 端 `StringExtensions.cnCompare` 下沉。
 *
 * app 端原实现按 `Build.VERSION.SDK_INT >= N` 分支取 android.icu / java.text Collator,
 * minSdk 26 下 else 分支恒不可达, 故 androidMain actual 只保留 android.icu 分支。
 *
 * - androidMain: `android.icu.text.Collator(SIMPLIFIED_CHINESE)`
 * - jvmMain: `java.text.Collator(Locale.CHINA)`
 * - nativeMain (iOS/鸿蒙): 无 ICU 可用, 退化为 Unicode 码点序 (见该 actual 注释)
 */
expect fun String.cnCompare(other: String): Int
