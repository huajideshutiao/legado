package io.legado.app.data

import android.icu.text.Collator
import android.icu.util.ULocale

// Android actual: 保持与原 app 端 String.cnCompare 的 SDK>=N 分支完全一致 (minSdk 26 恒成立)。
// 不复用 app 端 cnCompare 扩展 (跨模块同包名同签名扩展会冲突), 独立 internal actual。
internal actual fun String.cnCompareGroups(other: String): Int {
    return Collator.getInstance(ULocale.SIMPLIFIED_CHINESE).compare(this, other)
}
