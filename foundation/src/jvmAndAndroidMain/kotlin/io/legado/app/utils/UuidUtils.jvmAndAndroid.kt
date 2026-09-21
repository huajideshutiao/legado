package io.legado.app.utils

import java.util.UUID

/**
 * UuidUtils 的 JVM 半区 actual（android + jvm 共用）。
 *
 * 委托 java.util.UUID.randomUUID().toString()，与原直接调用行为一致。
 */

actual fun randomUUIDString(): String = UUID.randomUUID().toString()
