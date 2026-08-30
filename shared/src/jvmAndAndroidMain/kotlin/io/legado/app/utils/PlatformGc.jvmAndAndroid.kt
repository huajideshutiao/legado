package io.legado.app.utils

/** GC 提示 actual: 与原 app 端 TextFile.analyze 尾部行为逐句一致。 */
@Suppress("DEPRECATION")
internal actual fun platformGcAndFinalize() {
    System.gc()
    System.runFinalization()
}
