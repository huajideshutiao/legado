package io.legado.app.help.coroutine

import kotlinx.coroutines.CancellationException

// native 无 fillInStackTrace 可覆写, 普通类即可
actual class ActivelyCancelException : CancellationException(null)
