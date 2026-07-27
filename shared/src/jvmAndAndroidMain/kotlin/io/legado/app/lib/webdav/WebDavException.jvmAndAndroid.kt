package io.legado.app.lib.webdav

actual open class WebDavException actual constructor(msg: String) : Exception(msg) {

    override fun fillInStackTrace(): Throwable {
        return this
    }

}
