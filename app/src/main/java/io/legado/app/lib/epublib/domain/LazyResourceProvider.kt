package io.legado.app.lib.epublib.domain

import java.io.IOException
import java.io.InputStream

/**
 * @author jake
 */
interface LazyResourceProvider {
    @Throws(IOException::class)
    fun getResourceStream(href: String?): InputStream?
}
