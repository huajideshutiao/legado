package io.legado.app.model.fileBook

/** zip 结构错误；JVM/Android 上即 java.io.IOException，保持既有异常类型 */
expect open class ZipIOException(message: String) : Exception
