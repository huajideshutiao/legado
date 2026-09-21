package io.legado.app.lib.webdav

// expect/actual: fillInStackTrace 抑制栈捕获是 JVM-only override, native actual 为普通类
expect open class WebDavException(msg: String) : Exception

class ObjectNotFoundException(msg: String) : WebDavException(msg)