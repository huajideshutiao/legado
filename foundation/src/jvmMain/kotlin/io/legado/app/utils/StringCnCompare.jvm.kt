package io.legado.app.utils

import java.text.Collator
import java.util.Locale

// 桌面 JVM 无 android.icu, 用 java.text.Collator (与 app 端 SDK<N 分支同源)
actual fun String.cnCompare(other: String): Int {
    return Collator.getInstance(Locale.CHINA).compare(this, other)
}
