@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.log

import platform.Foundation.NSLog

/**
 * NSLog 整串输出: 消息在 Kotlin 侧拼好后作为唯一格式串参数传入。
 *
 * - 消息中的 % 必须转义为 %%, NSLog 首参是 C 格式串, 未转义会被当格式符解释;
 * - 严禁经 NSLog 变参传 %@ 对象: K/N 互操作下变参对象存活到 os_log 格式化时
 *   无保证, 系统回调线程上实测 EXC_BREAKPOINT (LiveContainer 内通知授权回调
 *   立即报错, 启动 0.26s 即崩于 objc_opt_respondsToSelector 野指针)。
 */
internal fun iosConsoleLog(message: String) {
    NSLog(message.replace("%", "%%"))
}
