package io.legado.app.lib.webdav

// native 无 fillInStackTrace 可覆写, 普通类即可
actual open class WebDavException actual constructor(msg: String) : Exception(msg)
