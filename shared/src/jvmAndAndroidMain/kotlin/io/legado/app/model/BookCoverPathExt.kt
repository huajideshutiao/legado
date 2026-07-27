package io.legado.app.model

import java.io.File

/**
 * [resolveChildAbsolutePath] 的 jvmAndAndroid 实现。
 *
 * 与原 app 端 `File(coversDir, child).absolutePath` 完全一致, 无行为变化。
 */
actual fun resolveChildAbsolutePath(parent: String, child: String): String =
    File(parent, child).absolutePath
