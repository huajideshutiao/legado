package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.MediaType
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.Resources
import io.legado.app.lib.epublib.util.ResourceUtil
import io.legado.app.lib.epublib.util.zip.AndroidZipFile
import io.legado.app.lib.epublib.util.zip.ZipFileWrapper
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads an epub file.
 * 
 * @author paul
 */
class EpubReader {
    private val bookProcessor: BookProcessor? = BookProcessor.Companion.IDENTITY_BOOKPROCESSOR

    @Throws(IOException::class)
    fun readEpub(`in`: InputStream?): EpubBook? {
        return readEpub(`in`, Constants.CHARACTER_ENCODING)
    }

    @Throws(IOException::class)
    fun readEpub(`in`: ZipInputStream?): EpubBook? {
        return readEpub(`in`, Constants.CHARACTER_ENCODING)
    }

    @Throws(IOException::class)
    fun readEpub(zipfile: ZipFile?): EpubBook? {
        return readEpub(zipfile, Constants.CHARACTER_ENCODING)
    }

    /**
     * Read epub from inputstream
     * 
     * @param in       the inputstream from which to read the epub
     * @param encoding the encoding to use for the html files within the epub
     * @return the Book as read from the inputstream
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun readEpub(`in`: InputStream?, encoding: String): EpubBook? {
        return readEpub(ZipInputStream(`in`), encoding)
    }


    /**
     * Reads this EPUB without loading any resources into memory.
     * 
     * @param zipFile  the file to load
     * @param encoding the encoding for XHTML files
     * @return this Book without loading all resources into memory.
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun readEpubLazy(zipFile: ZipFile, encoding: String): EpubBook? {
        return readEpubLazy(
            zipFile,
            encoding,
            MediaTypes.mediaTypes.toMutableList()
        )
    }

    @Throws(IOException::class)
    fun readEpubLazy(zipFile: AndroidZipFile, encoding: String): EpubBook? {
        return readEpubLazy(
            zipFile,
            encoding,
            MediaTypes.mediaTypes.toMutableList()
        )
    }

    @Throws(IOException::class)
    fun readEpub(`in`: ZipInputStream, encoding: String): EpubBook? {
        return readEpub(ResourcesLoader.loadResources(`in`, encoding))
    }

    @Throws(IOException::class)
    fun readEpub(`in`: ZipFile?, encoding: String?): EpubBook? {
        return readEpub(ResourcesLoader.loadResources(ZipFileWrapper(`in`!!), encoding))
    }

    /**
     * Reads this EPUB without loading all resources into memory.
     * 
     * @param zipFile         the file to load
     * @param encoding        the encoding for XHTML files
     * @param lazyLoadedTypes a list of the MediaType to load lazily
     * @return this Book without loading all resources into memory.
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun readEpubLazy(
        zipFile: ZipFile, encoding: String,
        lazyLoadedTypes: MutableList<MediaType?>
    ): EpubBook? {
        val resources: Resources =
            ResourcesLoader.loadResources(ZipFileWrapper(zipFile), encoding, lazyLoadedTypes)
        return readEpub(resources)
    }

    @Throws(IOException::class)
    fun readEpubLazy(
        zipFile: AndroidZipFile, encoding: String,
        lazyLoadedTypes: MutableList<MediaType?>
    ): EpubBook? {
        val resources: Resources =
            ResourcesLoader.loadResources(ZipFileWrapper(zipFile), encoding, lazyLoadedTypes)
        return readEpub(resources)
    }

    fun readEpub(resources: Resources): EpubBook? {
        return readEpub(resources, EpubBook())
    }

    fun readEpub(resources: Resources, result: EpubBook?): EpubBook? {
        var result: EpubBook? = result
        if (result == null) {
            result = EpubBook()
        }
        handleMimeType(result, resources)
        val packageResourceHref = getPackageResourceHref(resources)
        val packageResource: Resource =
            processPackageResource(packageResourceHref, result, resources)
        result.opfResource = packageResource
        val ncxResource: Resource? = processNcxResource(packageResource, result)
        result.ncxResource = ncxResource
        result = postProcessBook(result)
        return result
    }

    private fun postProcessBook(book: EpubBook?): EpubBook? {
        var book: EpubBook? = book
        if (bookProcessor != null) {
            book = bookProcessor.processBook(book)
        }
        return book
    }

    private fun processNcxResource(packageResource: Resource, book: EpubBook): Resource? {
        Log.d(TAG, "OPF:getHref()" + packageResource.getHref())
        if (book.isEpub3) {
            return NCXDocumentV3.read(book, this)
        } else {
            return NCXDocumentV2.read(book, this)
        }
    }

    private fun processPackageResource(
        packageResourceHref: String?, book: EpubBook?,
        resources: Resources
    ): Resource {
        val packageResource: Resource = resources.remove(packageResourceHref)
            ?: error("Package resource not found: $packageResourceHref")
        try {
            PackageDocumentReader.read(
                packageResource,
                this,
                book ?: return packageResource,
                resources
            )
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
        return packageResource
    }

    private fun getPackageResourceHref(resources: Resources): String? {
        val defaultResult = "OEBPS/content.opf"
        var result: String? = defaultResult

        val containerResource: Resource? = resources.remove("META-INF/container.xml")
        if (containerResource == null) {
            return result
        }
        try {
            val document: Document = ResourceUtil.getAsDocument(containerResource) ?: return result
            val rootFileElement = (document
                .getDocumentElement().getElementsByTagName("rootfiles").item(0) as Element)
                .getElementsByTagName("rootfile").item(0) as Element
            result = rootFileElement.getAttribute("full-path")
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
        if (result.isNullOrBlank()) {
            result = defaultResult
        }
        return result
    }

    private fun handleMimeType(result: EpubBook?, resources: Resources) {
        resources.remove("mimetype")
        //result.setResources(resources);
    }

    companion object {
        private val TAG: String = EpubReader::class.java.getName()
    }
}
