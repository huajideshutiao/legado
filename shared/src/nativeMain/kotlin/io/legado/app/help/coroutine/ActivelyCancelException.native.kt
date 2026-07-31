package io.legado.app.help.coroutine

import kotlinx.coroutines.CancellationException

// native 无 fillInStackTrace 可覆写, 普通类即可
// null 字面量对 (message: String?) / (cause: Throwable?) 两个构造器歧义, 显式指定 message 形参
actual class ActivelyCancelException : CancellationException(null as String?)
