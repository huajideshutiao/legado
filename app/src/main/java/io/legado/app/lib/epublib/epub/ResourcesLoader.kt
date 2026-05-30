package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.domain.EpubResourceProvider
import io.legado.app.lib.epublib.domain.LazyResource
import io.legado.app.lib.epublib.domain.LazyResourceProvider
import io.legado.app.lib.epublib.domain.MediaType
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.Resources
import io.legado.app.lib.epublib.util.ResourceUtil
import io.legado.app.lib.epublib.util.zip.ZipEntryWrapper
import io.legado.app.lib.epublib.util.zip.ZipFileWrapper
import java.io.IOException
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Loads Resources from inputStreams, ZipFiles, etc
 * 
 * @author paul
 */
object ResourcesLoader {
    private val TAG: String = ResourcesLoader::class.java.name


    /**
     * Loads the entries of the zipFileWrapper as resources.
     * 
     * 
     * The MediaTypes that are in the lazyLoadedTypes will not get their
     * contents loaded, but are stored as references to entries into the
     * AndroidZipFile and are loaded on demand by the Resource system.
     * 
     * @param zipFileWrapper      import epub zipfile
     * @param defaultHtmlEncoding epub xhtml default encoding
     * @param lazyLoadedTypes     lazyLoadedTypes
     * @return Resources
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun loadResources(
        zipFileWrapper: ZipFileWrapper,
        defaultHtmlEncoding: String?,
        lazyLoadedTypes: MutableList<MediaType?>
    ): Resources {
        val resourceProvider: LazyResourceProvider =
            EpubResourceProvider(zipFileWrapper)

        val result: Resources = Resources()
        val entries: Enumeration<*> = zipFileWrapper.entries() ?: return result

        while (entries.hasMoreElements()) {
            val zipEntry: ZipEntryWrapper = ZipEntryWrapper(entries.nextElement())

            if (zipEntry.isDirectory) continue

            val href = zipEntry.name
            val resource: Resource = if (shouldLoadLazy(href, lazyLoadedTypes)) {
                LazyResource(resourceProvider, zipEntry.size, href)
            } else {
                ResourceUtil.createResource(zipEntry.name, zipFileWrapper.getInputStream(zipEntry))
            }

            if (resource.mediaType === MediaTypes.XHTML) {
                resource.inputEncoding = defaultHtmlEncoding
            }
            result.add(resource)
        }

        return result
    }

    /**
     * Whether the given href will load a mediaType that is in the
     * collection of lazilyLoadedMediaTypes.
     * 
     * @param href                   href
     * @param lazilyLoadedMediaTypes lazilyLoadedMediaTypes
     * @return Whether the given href will load a mediaType that is
     * in the collection of lazilyLoadedMediaTypes.
     */
    private fun shouldLoadLazy(
        href: String?,
        lazilyLoadedMediaTypes: MutableCollection<MediaType?>
    ): Boolean {
        if (lazilyLoadedMediaTypes.isEmpty()) {
            return false
        }
        val mediaType: MediaType? = MediaTypes.determineMediaType(href)
        return lazilyLoadedMediaTypes.contains(mediaType)
    }

    /**
     * Loads all entries from the ZipInputStream as Resources.
     * 
     * 
     * Loads the contents of all ZipEntries into memory.
     * Is fast, but may lead to memory problems when reading large books
     * on devices with small amounts of memory.
     * 
     * @param zipInputStream      zipInputStream
     * @param defaultHtmlEncoding defaultHtmlEncoding
     * @return Resources
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun loadResources(
        zipInputStream: ZipInputStream,
        defaultHtmlEncoding: String?
    ): Resources {
        val result: Resources = Resources()
        var zipEntry: ZipEntry?
        do {
            // get next valid zipEntry
            zipEntry = getNextZipEntry(zipInputStream)
            if ((zipEntry == null) || zipEntry.isDirectory) {
                continue
            }

            //String href = zipEntry.getName();

            // store resource
            val resource: Resource = ResourceUtil.createResource(zipEntry.name, zipInputStream)
            if (resource.mediaType === MediaTypes.XHTML) {
                resource.inputEncoding = defaultHtmlEncoding
            }
            result.add(resource)
        } while (zipEntry != null)

        return result
    }


    @Throws(IOException::class)
    private fun getNextZipEntry(zipInputStream: ZipInputStream): ZipEntry? {
        try {
            return zipInputStream.nextEntry
        } catch (e: ZipException) {
            //see <a href="https://github.com/psiegman/epublib/issues/122">Issue #122 Infinite loop</a>.
            //when reading a file that is not a real zip archive or a zero length file, zipInputStream.getNextEntry()
            //throws an exception and does not advance, so loadResources enters an infinite loop
            //log.error("Invalid or damaged zip file.", e);
            Log.e(TAG, e.localizedMessage)
            try {
                zipInputStream.closeEntry()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    /**
     * Loads all entries from the ZipInputStream as Resources.
     * 
     * 
     * Loads the contents of all ZipEntries into memory.
     * Is fast, but may lead to memory problems when reading large books
     * on devices with small amounts of memory.
     * 
     * @param zipFile             zipFile
     * @param defaultHtmlEncoding defaultHtmlEncoding
     * @return Resources
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun loadResources(zipFile: ZipFileWrapper, defaultHtmlEncoding: String?): Resources {
        val ls: MutableList<MediaType?> = ArrayList<MediaType?>()
        return loadResources(zipFile, defaultHtmlEncoding, ls)
    }
}
