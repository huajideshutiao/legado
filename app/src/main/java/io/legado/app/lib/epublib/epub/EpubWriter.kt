package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.util.IOUtil
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates an epub file. Not thread-safe, single use object.
 * 
 * @author paul
 */
class EpubWriter(@set:Suppress("unused") @get:Suppress("unused") var bookProcessor: BookProcessor?) {
    private var epubWriterProcessor: EpubWriterProcessor? = null

    constructor() : this(BookProcessor.Companion.IDENTITY_BOOKPROCESSOR) {
        this.epubWriterProcessor = EpubWriterProcessor()
        // 写入MimeType、Container，初始化TOCResource整体为1
        // 写入PackageDocument 为1
        // 关闭流 为1
        this.epubWriterProcessor!!.totalProgress = 3
    }


    fun setCallback(callback: EpubWriterProcessor.Callback?): EpubWriter {
        epubWriterProcessor!!.setCallback(callback ?: return this)
        return this
    }

    @Throws(IOException::class)
    fun write(book: EpubBook, out: OutputStream?) {
        var book: EpubBook = book
        epubWriterProcessor?.getCallback()?.onStart(book)
        epubWriterProcessor?.let { it.totalProgress += book.resources.size() }
        book = processBook(book) ?: book
        val resultStream = ZipOutputStream(out)
        writeMimeType(resultStream)
        writeContainer(resultStream)
        initTOCResource(book)
        epubWriterProcessor?.updateCurrentProgress(1)
        writeResources(book, resultStream)
        writePackageDocument(book, resultStream)
        epubWriterProcessor?.updateCurrentProgress(epubWriterProcessor!!.currentProgress + 1)
        resultStream.close()
        epubWriterProcessor?.updateCurrentProgress(epubWriterProcessor!!.currentProgress + 1)
        epubWriterProcessor?.getCallback()?.onEnd(book)
    }

    private fun processBook(book: EpubBook?): EpubBook? {
        var book: EpubBook? = book
        if (bookProcessor != null) {
            book = bookProcessor!!.processBook(book)
        }
        return book
    }

    private fun initTOCResource(book: EpubBook) {
        val tocResource: Resource?
        try {
            if (book.isEpub3) {
                tocResource = NCXDocumentV3.createNCXResource(book)
            } else {
                tocResource = NCXDocumentV2.createNCXResource(book)
            }

            val currentTocResource: Resource? = book.spine.tocResource
            if (currentTocResource != null) {
                book.resources.remove(currentTocResource.getHref())
            }
            book.spine.tocResource = tocResource
            book.resources.add(tocResource ?: return)
        } catch (ex: Exception) {
            Log.e(
                TAG,
                "Error writing table of contents: " + ex.javaClass.getName() + ": " + ex.message,
                ex
            )
        }
    }


    private fun writeResources(book: EpubBook, resultStream: ZipOutputStream) {
        for (resource in book.resources.all) {
            writeResource(resource, resultStream)
            epubWriterProcessor!!.updateCurrentProgress(epubWriterProcessor!!.currentProgress + 1)
        }
    }

    /**
     * Writes the resource to the resultStream.
     * 
     * @param resource     resource
     * @param resultStream resultStream
     */
    private fun writeResource(resource: Resource?, resultStream: ZipOutputStream) {
        if (resource == null) {
            return
        }
        try {
            resultStream.putNextEntry(ZipEntry("OEBPS/" + resource.getHref()))
            val inputStream: InputStream = resource.inputStream!!

            IOUtil.copy(inputStream, resultStream)
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
    }


    @Throws(IOException::class)
    private fun writePackageDocument(book: EpubBook, resultStream: ZipOutputStream) {
        resultStream.putNextEntry(ZipEntry("OEBPS/content.opf"))
        val xmlSerializer: XmlSerializer =
            EpubProcessorSupport.Companion.createXmlSerializer(resultStream)!!
        PackageDocumentWriter.write(this, xmlSerializer, book)
        xmlSerializer.flush()
        //		String resultAsString = result.toString();
//		resultStream.write(resultAsString.getBytes(Constants.ENCODING));
    }

    /**
     * Writes the META-INF/container.xml file.
     * 
     * @param resultStream resultStream
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    private fun writeContainer(resultStream: ZipOutputStream) {
        resultStream.putNextEntry(ZipEntry("META-INF/container.xml"))
        val out: Writer = OutputStreamWriter(resultStream)
        out.write("<?xml version=\"1.0\"?>\n")
        out.write("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n")
        out.write("\t<rootfiles>\n")
        out.write("\t\t<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n")
        out.write("\t</rootfiles>\n")
        out.write("</container>")
        out.flush()
    }

    /**
     * Stores the mimetype as an uncompressed file in the ZipOutputStream.
     * 
     * @param resultStream resultStream
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    private fun writeMimeType(resultStream: ZipOutputStream) {
        val mimetypeZipEntry = ZipEntry("mimetype")
        mimetypeZipEntry.setMethod(ZipEntry.STORED)
        val mimetypeBytes: ByteArray =
            (MediaTypes.EPUB.name ?: "application/epub+zip").toByteArray()
        mimetypeZipEntry.setSize(mimetypeBytes.size.toLong())
        mimetypeZipEntry.setCrc(calculateCrc(mimetypeBytes))
        resultStream.putNextEntry(mimetypeZipEntry)
        resultStream.write(mimetypeBytes)
    }

    private fun calculateCrc(data: ByteArray?): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.getValue()
    }

    val ncxId: String
        get() = "ncx"

    val ncxHref: String
        get() = "toc.ncx"

    val ncxMediaType: String
        get() = MediaTypes.NCX.name ?: "application/x-dtbncx+xml"


    companion object {
        // package
        const val EMPTY_NAMESPACE_PREFIX: String = ""
        private val TAG: String = EpubWriter::class.java.getName()
    }
}
