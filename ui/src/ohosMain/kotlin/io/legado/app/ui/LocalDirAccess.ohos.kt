package io.legado.app.ui

import io.legado.app.constant.DirUsage
import io.legado.app.help.file.OhosDirAuthorizations

/**
 * 鸿蒙实现: 访问前按需激活目录授权 (对照 iOS security-scoped 租约)。
 *
 * 授权按 [usage] 匹配: 只有用户经同一用途入口选定的目录才激活; 已登记目录激活失败抛
 * SecurityException (明确错误码, 不降级成文件不存在), 由调用方处理。
 * 沙盒内/手机降级路径不在授权集合内, 原行为直通。
 */
internal actual fun <T> withLocalDirAccess(path: String, usage: DirUsage, block: () -> T): T {
    OhosDirAuthorizations.ensureActivatedFor(path, usage)
    return block()
}
