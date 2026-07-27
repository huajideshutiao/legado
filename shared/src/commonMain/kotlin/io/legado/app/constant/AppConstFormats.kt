package io.legado.app.constant

/**
 * AppConst 日期格式化半区(commonMain expect)。
 *
 * 原 jvmAndAndroidMain 件 SimpleDateFormat 绑定, 进不了 commonMain; 现下沉为 expect/actual:
 * - commonMain 暴露 [ThreadSafeDateFormat] 类与 [AppConst] 同包扩展属性, 调用处 AppConst.xxx 写法不变;
 * - jvmAndAndroidMain 用 SimpleDateFormat + ThreadLocal 实现 actual (android + jvm 共用)。
 *
 * 公开 API 兼容性: 类名 ThreadSafeDateFormat 不变; 原 format(Date) 重载因 java.util.Date 不可见于 commonMain
 * 已删除, 调用方一律改传 Long (原调用均为 Date(Long) 包装, 行为不变)。
 */
expect class ThreadSafeDateFormat(pattern: String) {
    /**
     * 将 epoch 毫秒时间戳按构造 pattern 格式化为本地时区字符串。
     * 线程安全: actual 实现使用 ThreadLocal 缓存 SimpleDateFormat 实例。
     */
    fun format(millis: Long): String
}

private val timeFormatInternal by lazy { ThreadSafeDateFormat("HH:mm") }
private val dateFormatInternal by lazy { ThreadSafeDateFormat("yyyy/MM/dd HH:mm") }
private val fileNameFormatInternal by lazy { ThreadSafeDateFormat("yy-MM-dd-HH-mm-ss") }

val AppConst.timeFormat: ThreadSafeDateFormat get() = timeFormatInternal

val AppConst.dateFormat: ThreadSafeDateFormat get() = dateFormatInternal

val AppConst.fileNameFormat: ThreadSafeDateFormat get() = fileNameFormatInternal
