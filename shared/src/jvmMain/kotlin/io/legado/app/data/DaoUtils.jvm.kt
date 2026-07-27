package io.legado.app.data

import java.text.Collator
import java.util.Locale

// JVM actual: 无 android.icu, 退回 java.text.Collator (Locale.CHINA)。
// desktop jvm 端为新增平台, 无历史行为可对照。
internal actual fun String.cnCompareGroups(other: String): Int {
    return Collator.getInstance(Locale.CHINA).compare(this, other)
}
