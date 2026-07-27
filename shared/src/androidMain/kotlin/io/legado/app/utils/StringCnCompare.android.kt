package io.legado.app.utils

import android.icu.text.Collator
import android.icu.util.ULocale

// 与原 app 端 String.cnCompare 的 SDK>=N 分支完全一致 (minSdk 26 恒成立)
actual fun String.cnCompare(other: String): Int {
    return Collator.getInstance(ULocale.SIMPLIFIED_CHINESE).compare(this, other)
}
