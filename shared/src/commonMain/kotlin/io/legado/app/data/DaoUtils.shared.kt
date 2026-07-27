package io.legado.app.data

import io.legado.app.constant.AppPattern
import io.legado.app.utils.splitNotBlank

/*
 * K5-c Phase 2: DAO 接口下沉前置——dealGroups 由 app 端 DaoUtils.kt 迁入 shared。
 *
 * 公共逻辑放 commonMain (仅依赖 splitNotBlank + AppPattern.splitGroupRegex, 均 commonMain-ready);
 * 排序所用的 Collator 因平台差异 (android.icu.text.Collator vs java.text.Collator)
 * 走 expect/actual, 见 androidMain/jvmMain 各自 actual。
 *
 * 跨平台 dealGroups 仅供 shared 模块内 DAO 接口 (jvmAndAndroidMain) 调用, internal 跨模块不可见,
 * app 端历史调用方均经 DAO 间接访问, 无破坏。
 */
internal fun dealGroups(list: List<String>): List<String> {
    val groups = linkedSetOf<String>()
    list.forEach {
        it.splitNotBlank(AppPattern.splitGroupRegex).forEach { group ->
            groups.add(group)
        }
    }
    return groups.sortedWith { o1, o2 ->
        o1.cnCompareGroups(o2)
    }
}

// 平台分派的中文字符串排序; androidMain 走 android.icu (与原 app 端 cnCompare SDK>=N 分支一致),
// jvmMain 走 java.text.Collator (JVM 兼容). 命名独立于 app 端 public String.cnCompare 扩展避免跨模块同包同签名冲突。
internal expect fun String.cnCompareGroups(other: String): Int
