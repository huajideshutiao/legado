package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.Author
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Identifier
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.TOCReference
import io.legado.app.lib.epublib.domain.TableOfContents
import io.legado.app.lib.epublib.util.ResourceUtil
import io.legado.app.lib.epublib.util.StringUtil
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the ncx document as defined by namespace http://www.daisy.org/z3986/2005/ncx/
 * 
 * @author paul
 */
object NCXDocumentV2 {
    const val NAMESPACE_NCX: String = "http://www.daisy.org/z3986/2005/ncx/"

    @Suppress("unused")
    const val PREFIX_NCX: String = "ncx"
    const val NCX_ITEM_ID: String = "ncx"
    const val DEFAULT_NCX_HREF: String = "toc.ncx"
    const val PREFIX_DTB: String = "dtb"

    private val TAG: String = NCXDocumentV2::class.java.getName()

    @Suppress("unused")
    fun read(book: EpubBook, epubReader: EpubReader?): Resource? {
        var ncxResource: Resource? = null
        if (book.spine.tocResource == null) {
            Log.e(TAG, "Book does not contain a table of contents file")
            return null
        }
        try {
            ncxResource = book.spine.tocResource
            if (ncxResource == null) {
                return null
            }
            Log.d(TAG, ncxResource.getHref())
            val ncxDocument: Document = ResourceUtil.getAsDocument(ncxResource) ?: return null
            val navMapElement = DOMUtil.getFirstElementByTagNameNS(
                ncxDocument.getDocumentElement(),
                NAMESPACE_NCX, NCXTags.Companion.navMap
            )
            if (navMapElement == null) {
                return null
            }

            val tableOfContents: TableOfContents = TableOfContents(
                readTOCReferences(navMapElement.getChildNodes(), book).filterNotNull()
                    .toMutableList()
            )
            book.tableOfContents = tableOfContents
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
        return ncxResource
    }

    fun readTOCReferences(
        navpoints: NodeList?,
        book: EpubBook
    ): MutableList<TOCReference?> {
        if (navpoints == null) {
            return ArrayList<TOCReference?>()
        }
        val result: MutableList<TOCReference?> = ArrayList<TOCReference?>(
            navpoints.getLength()
        )
        for (i in 0..<navpoints.getLength()) {
            val node = navpoints.item(i)
            if (node.getNodeType() != Document.ELEMENT_NODE) {
                continue
            }
            if (!(node.getLocalName() == NCXTags.Companion.navPoint)) {
                continue
            }
            val tocReference: TOCReference = readTOCReference(node as Element, book)
            result.add(tocReference)
        }
        return result
    }

    fun readTOCReference(navpointElement: Element, book: EpubBook): TOCReference {
        val label = readNavLabel(navpointElement)
        //Log.d(TAG,"label:"+label);
        var tocResourceRoot: String = StringUtil
            .substringBeforeLast(book.spine.tocResource!!.getHref(), '/') ?: ""
        if (tocResourceRoot.length == book.spine.tocResource!!.getHref()
                .length
        ) {
            tocResourceRoot = ""
        } else {
            tocResourceRoot = tocResourceRoot + "/"
        }
        val reference: String? = StringUtil
            .collapsePathDots(tocResourceRoot + readNavReference(navpointElement))
        val href: String? = StringUtil
            .substringBefore(reference, Constants.FRAGMENT_SEPARATOR_CHAR)
        val fragmentId: String? = StringUtil
            .substringAfter(reference, Constants.FRAGMENT_SEPARATOR_CHAR)
        val resource: Resource? = book.resources.getByHref(href ?: "")
        if (resource == null) {
            Log.e(TAG, "Resource with href " + href + " in NCX document not found")
        }
        //Log.v(TAG, "label:" + label);
        //Log.v(TAG, "href:" + href);
        //Log.v(TAG, "fragmentId:" + fragmentId);
        val result: TOCReference = TOCReference(label, resource, fragmentId)
        val childTOCReferences: MutableList<TOCReference?> = readTOCReferences(
            navpointElement.getChildNodes(), book
        )
        result.children = childTOCReferences.filterNotNull().toMutableList()
        return result
    }

    private fun readNavReference(navpointElement: Element): String? {
        val contentElement = DOMUtil.getFirstElementByTagNameNS(
            navpointElement, NAMESPACE_NCX,
            NCXTags.Companion.content
        )
        if (contentElement == null) {
            return null
        }
        var result =
            DOMUtil.getAttribute(contentElement, NAMESPACE_NCX, NCXAttributes.Companion.src)
        try {
            result = URLDecoder.decode(result, Constants.CHARACTER_ENCODING)
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, e.message!!)
        }
        return result
    }

    private fun readNavLabel(navpointElement: Element): String? {
        //Log.d(TAG,navpointElement.getTagName());
        val navLabel: Element? = checkNotNull(
            DOMUtil.getFirstElementByTagNameNS(
                navpointElement, NAMESPACE_NCX,
                NCXTags.Companion.navLabel
            )
        )
        return DOMUtil.getTextChildrenContent(
            DOMUtil.getFirstElementByTagNameNS(
                navLabel!!,
                NAMESPACE_NCX,
                NCXTags.Companion.text
            ) ?: return null
        )
    }

    @Suppress("unused")
    @Throws(IOException::class)
    fun write(
        epubWriter: EpubWriter?, book: EpubBook,
        resultStream: ZipOutputStream
    ) {
        resultStream
            .putNextEntry(ZipEntry(book.spine.tocResource!!.getHref()))
        val out: XmlSerializer = EpubProcessorSupport.Companion.createXmlSerializer(resultStream)!!
        write(out, book)
        out.flush()
    }


    /**
     * Generates a resource containing an xml document containing the table of contents of the book in ncx format.
     * 
     * @param xmlSerializer the serializer used
     * @param book          the book to serialize
     * @throws IOException              IOException
     * @throws IllegalStateException    IllegalStateException
     * @throws IllegalArgumentException IllegalArgumentException
     */
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun write(xmlSerializer: XmlSerializer, book: EpubBook) {
        write(
            xmlSerializer,
            book.metadata.getIdentifiers().filterNotNull().toMutableList(),
            book.title,
            book.metadata.authors.filterNotNull().toMutableList(),
            book.tableOfContents
        )
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun createNCXResource(book: EpubBook): Resource {
        return createNCXResource(
            book.metadata.getIdentifiers().filterNotNull().toMutableList(),
            book.title, book.metadata.authors.filterNotNull().toMutableList(),
            book.tableOfContents
        )
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun createNCXResource(
        identifiers: MutableList<Identifier>,
        title: String?,
        authors: MutableList<Author>,
        tableOfContents: TableOfContents
    ): Resource {
        val data = ByteArrayOutputStream()
        val out: XmlSerializer = EpubProcessorSupport.Companion.createXmlSerializer(data)!!
        write(out, identifiers, title, authors, tableOfContents)
        return Resource(
            NCX_ITEM_ID, data.toByteArray(),
            DEFAULT_NCX_HREF, MediaTypes.NCX
        )
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun write(
        serializer: XmlSerializer,
        identifiers: MutableList<Identifier>,
        title: String?,
        authors: MutableList<Author>,
        tableOfContents: TableOfContents
    ) {
        serializer.startDocument(Constants.CHARACTER_ENCODING, false)
        serializer.setPrefix(EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NAMESPACE_NCX)
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.ncx)
        //		serializer.writeNamespace("ncx", NAMESPACE_NCX);
//		serializer.attribute("xmlns", NAMESPACE_NCX);
        serializer
            .attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.version,
                NCXAttributeValues.Companion.version
            )
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.head)

        for (identifier in identifiers) {
            writeMetaElement(
                identifier.scheme, identifier.value,
                serializer
            )
        }

        writeMetaElement("generator", Constants.EPUB_GENERATOR_NAME, serializer)
        writeMetaElement(
            "depth", tableOfContents.calculateDepth().toString(),
            serializer
        )
        writeMetaElement("totalPageCount", "0", serializer)
        writeMetaElement("maxPageNumber", "0", serializer)

        serializer.endTag(NAMESPACE_NCX, "head")

        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.docTitle)
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.text)
        // write the first title
        serializer.text(StringUtil.defaultIfNull(title))
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.text)
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.docTitle)

        for (author in authors) {
            serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.docAuthor)
            serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.text)
            serializer.text(author.lastname + ", " + author.firstname)
            serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.text)
            serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.docAuthor)
        }

        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.navMap)
        writeNavPoints(tableOfContents.tocReferences!!, 1, serializer)
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.navMap)

        serializer.endTag(NAMESPACE_NCX, "ncx")
        serializer.endDocument()
    }


    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeMetaElement(
        dtbName: kotlin.String?,
        content: kotlin.String?,
        serializer: XmlSerializer
    ) {
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.meta)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.name,
            PREFIX_DTB + ":" + dtbName
        )
        serializer
            .attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.content,
                content
            )
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.meta)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeNavPoints(
        tocReferences: MutableList<TOCReference>,
        playOrder: Int,
        serializer: XmlSerializer
    ): Int {
        var playOrder = playOrder
        for (tocReference in tocReferences) {
            if (tocReference.resource == null) {
                playOrder = writeNavPoints(
                    tocReference.children, playOrder,
                    serializer
                )
                continue
            }
            writeNavPointStart(tocReference, playOrder, serializer)
            playOrder++
            if (!tocReference.children.isEmpty()) {
                playOrder = writeNavPoints(
                    tocReference.children, playOrder,
                    serializer
                )
            }
            writeNavPointEnd(tocReference, serializer)
        }
        return playOrder
    }


    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeNavPointStart(
        tocReference: TOCReference,
        playOrder: Int,
        serializer: XmlSerializer
    ) {
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.navPoint)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.id,
            "navPoint-" + playOrder
        )
        serializer
            .attribute(
                EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.playOrder,
                playOrder.toString()
            )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.clazz,
            NCXAttributeValues.Companion.chapter
        )
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.navLabel)
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.text)
        serializer.text(tocReference.title)
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.text)
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.navLabel)
        serializer.startTag(NAMESPACE_NCX, NCXTags.Companion.content)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NCXAttributes.Companion.src,
            tocReference.completeHref
        )
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.content)
    }

    @Suppress("unused")
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    private fun writeNavPointEnd(
        tocReference: TOCReference?,
        serializer: XmlSerializer
    ) {
        serializer.endTag(NAMESPACE_NCX, NCXTags.Companion.navPoint)
    }

    private interface NCXTags {
        companion object {
            const val ncx: kotlin.String = "ncx"
            const val meta: kotlin.String = "meta"
            const val navPoint: kotlin.String = "navPoint"
            const val navMap: kotlin.String = "navMap"
            const val navLabel: kotlin.String = "navLabel"
            const val content: kotlin.String = "content"
            const val text: kotlin.String = "text"
            const val docTitle: kotlin.String = "docTitle"
            const val docAuthor: kotlin.String = "docAuthor"
            const val head: kotlin.String = "head"
        }
    }

    private interface NCXAttributes {
        companion object {
            const val src: kotlin.String = "src"
            const val name: kotlin.String = "name"
            const val content: kotlin.String = "content"
            const val id: kotlin.String = "id"
            const val playOrder: kotlin.String = "playOrder"
            const val clazz: kotlin.String = "class"
            const val version: kotlin.String = "version"
        }
    }

    private interface NCXAttributeValues {
        companion object {
            const val chapter: kotlin.String = "chapter"
            const val version: kotlin.String = "2005-1"
        }
    }
}
