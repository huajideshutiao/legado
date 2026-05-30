package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.util.zip.ZipFileWrapper
import java.io.IOException
import java.io.InputStream

class EpubResourceProvider(private val zipFileWrapper: ZipFileWrapper) : LazyResourceProvider {
    @Throws(IOException::class)
    override fun getResourceStream(href: String?): InputStream? {
        val zipEntry = zipFileWrapper.getEntry(href) ?: return null
        return zipFileWrapper.getInputStream(zipEntry)
    }
}
