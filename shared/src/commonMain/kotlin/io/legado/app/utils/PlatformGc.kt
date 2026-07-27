package io.legado.app.utils

/**
 * GC 提示门面: 原 TextFile.analyze 尾部 System.gc()+runFinalization() (JVM-only)。
 * jvmAndAndroid actual 直通保持原行为; native actual 空实现 (Kotlin/Native GC 自动)。
 */
internal expect fun platformGcAndFinalize()
