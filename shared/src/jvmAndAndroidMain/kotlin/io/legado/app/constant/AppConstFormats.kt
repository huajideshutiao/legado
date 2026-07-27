package io.legado.app.constant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ThreadSafeDateFormat 的 JVM 半区 actual (android + jvm 共用)。
 *
 * 委托 java.text.SimpleDateFormat + ThreadLocal 实现线程安全 (与原 jvmAndAndroidMain 行为一致)。
 * 类名 [ThreadSafeDateFormat] 与三个 AppConst 扩展属性 (timeFormat/dateFormat/fileNameFormat)
 * 已下沉 commonMain 见同名 expect 件; 此处仅保留 actual 实现。
 */
actual class ThreadSafeDateFormat actual constructor(pattern: String) {
    private val tl = ThreadLocal.withInitial {
        SimpleDateFormat(pattern, Locale.getDefault())
    }

    actual fun format(millis: Long): String = tl.get()!!.format(Date(millis))
}
