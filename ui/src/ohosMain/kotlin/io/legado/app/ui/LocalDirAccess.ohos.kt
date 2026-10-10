package io.legado.app.ui

import io.legado.app.constant.DirUsage
import io.legado.app.help.file.OhosDirAuthorizations

/**
 * 鸿蒙实现: 应用私有目录 (filesDir/cacheDir 及子目录) 直通; 容器外路径经 [OhosDirAuthorizations]
 * 按 [usage] 激活其持久化目录授权后放行。
 *
 * 容器外路径在该用途下无登记、或登记激活失败时抛
 * SecurityException (明确错误码, 不降级成文件不存在), 由调用方处理; 设备无目录授权持久化能力时
 * 该登记是路径在本用途下的唯一授权, 直接放行。
 *
 * 块内收到的是授权表判定使用的规范化路径 (登记与匹配都按它比较)。
 */
internal actual fun <T> withLocalDirAccess(path: String, usage: DirUsage, block: (String) -> T): T =
    block(OhosDirAuthorizations.ensureActivatedFor(path, usage))
