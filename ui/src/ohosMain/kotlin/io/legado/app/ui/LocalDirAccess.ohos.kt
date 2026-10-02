package io.legado.app.ui

import io.legado.app.help.file.OhosDirAuthorizations

/**
 * 鸿蒙实现: 访问前按需激活目录授权 (对照 iOS security-scoped 租约)。
 * 已登记目录激活失败抛 SecurityException (明确错误码, 不降级成文件不存在);
 * 沙盒内/手机降级路径不在授权集合内, 原行为直通。
 */
internal actual fun <T> withLocalDirAccess(path: String, block: () -> T): T {
    OhosDirAuthorizations.ensureActivatedFor(path)
    return block()
}
