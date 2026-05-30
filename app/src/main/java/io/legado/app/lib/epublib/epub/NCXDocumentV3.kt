package io.legado.app.lib.epublib.epub

import android.util.Log
import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.Author
import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Identifier
import io.legado.app.lib.epublib.domain.MediaType
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.domain.TOCReference
import io.legado.app.lib.epublib.domain.TableOfContents
import io.legado.app.lib.epublib.util.ResourceUtil
import io.legado.app.lib.epublib.util.StringUtil
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * Writes the ncx document as defined by namespace http://www.daisy.org/z3986/2005/ncx/
 * 
 * @author Ag2S20150909
 */
object NCXDocumentV3 {
    const val NAMESPACE_XHTML: String = "http://www.w3.org/1999/xhtml"
    const val NAMESPACE_EPUB: String = "http://www.idpf.org/2007/ops"
    const val LANGUAGE: String = "en"

    @Suppress("unused")
    const val PREFIX_XHTML: String = "html"
    const val NCX_ITEM_ID: String = "htmltoc"
    const val DEFAULT_NCX_HREF: String = "toc.xhtml"
    const val V3_NCX_PROPERTIES: String = "nav"
    val V3_NCX_MEDIATYPE: MediaType? = MediaTypes.XHTML

    private val TAG: String = NCXDocumentV3::class.java.getName()

    /**
     * 解析epub的目录文件
     * 
     * @param book       Book
     * @param epubReader epubreader
     * @return Resource
     */
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
            //一些epub 3 文件没有按照epub3的标准使用删除掉ncx目录文件
            if (ncxResource.getHref().endsWith(".ncx")) {
                Log.v(TAG, "该epub文件不标准，使用了epub2的目录文件")
                return NCXDocumentV2.read(book, epubReader)
            }
            Log.d(TAG, ncxResource.getHref())

            val ncxDocument: Document = ResourceUtil.getAsDocument(ncxResource) ?: return null
            Log.d(TAG, ncxDocument.getNodeName())

            var navMapElement =
                ncxDocument.getElementsByTagName(XHTMLTgs.Companion.nav).item(0) as Element?
            if (navMapElement == null) {
                Log.d(TAG, "epub3目录文件未发现nav节点，尝试使用epub2的规则解析")
                return NCXDocumentV2.read(book, epubReader)
            }
            navMapElement =
                navMapElement.getElementsByTagName(XHTMLTgs.Companion.ol).item(0) as Element?
            Log.d(TAG, navMapElement!!.getTagName())

            val tableOfContents: TableOfContents = TableOfContents(
                readTOCReferences(navMapElement.getChildNodes(), book).filterNotNull()
                    .toMutableList()
            )
            Log.d(TAG, tableOfContents.toString())
            book.tableOfContents = tableOfContents
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
        return ncxResource
    }

    private fun doToc(n: Node?, book: EpubBook): MutableList<TOCReference?> {
        if (n == null || n.getNodeType() != Document.ELEMENT_NODE) {
            return ArrayList<TOCReference?>()
        }

        val el = n as Element
        val node = el.getElementsByTagName(XHTMLTgs.Companion.ol).item(0)

        if (node == null || node.getNodeType() != Document.ELEMENT_NODE) {
            return ArrayList<TOCReference?>()
        }

        return readTOCReferences(node.getChildNodes(), book)
    }


    fun readTOCReferences(
        navpoints: NodeList?,
        book: EpubBook
    ): MutableList<TOCReference?> {
        if (navpoints == null) {
            return ArrayList<TOCReference?>()
        }
        //Log.d(TAG, "readTOCReferences:navpoints.getLength()" + navpoints.getLength());
        val result: MutableList<TOCReference?> = ArrayList<TOCReference?>(navpoints.getLength())
        for (i in 0..<navpoints.getLength()) {
            val node = navpoints.item(i)
            //如果该node是null,或者不是Element,跳出本次循环
            if (node == null || node.getNodeType() != Document.ELEMENT_NODE) {
                continue
            }

            val el = node as Element
            //如果该Element的name为”li“,将其添加到目录结果
            if (el.getTagName() == XHTMLTgs.Companion.li) {
                result.add(readTOCReference(el, book))
            }
        }


        return result
    }


    fun readTOCReference(navpointElement: Element, book: EpubBook): TOCReference {
        //章节的名称
        val label = readNavLabel(navpointElement)
        //Log.d(TAG, "label:" + label);
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

        //        Log.v(TAG, "label:" + label);
//        Log.v(TAG, "href:" + href);
//        Log.v(TAG, "fragmentId:" + fragmentId);

        //父级目录
        val result: TOCReference = TOCReference(label, resource, fragmentId)
        //解析子级目录
        val childTOCReferences: MutableList<TOCReference?> = doToc(navpointElement, book)
        //readTOCReferences(
        //navpointElement.getChildNodes(), book);
        result.children = childTOCReferences.filterNotNull().toMutableList()
        return result
    }

    /**
     * 获取目录节点的href
     * 
     * @param navpointElement navpointElement
     * @return String
     */
    private fun readNavReference(navpointElement: Element): String? {
        //https://www.w3.org/publishing/epub/epub-packages.html#sec-package-nav
        //父级节点必须是 "li"
        //Log.d(TAG, "readNavReference:" + navpointElement.getTagName());

        val contentElement =
            DOMUtil.getFirstElementByTagNameNS(navpointElement, "", XHTMLTgs.Companion.a)
        if (contentElement == null) {
            return null
        }
        var result = DOMUtil.getAttribute(contentElement, "", XHTMLAttributes.Companion.href)
        try {
            result = URLDecoder.decode(result, Constants.CHARACTER_ENCODING)
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, e.message!!)
        }

        return result
    }

    /**
     * 获取目录节点里面的章节名
     * 
     * @param navpointElement navpointElement
     * @return String
     */
    private fun readNavLabel(navpointElement: Element): String? {
        //https://www.w3.org/publishing/epub/epub-packages.html#sec-package-nav
        //父级节点必须是 "li"
        //Log.d(TAG, "readNavLabel:" + navpointElement.getTagName());
        var label: String?
        var labelElement: Element? =
            checkNotNull(DOMUtil.getFirstElementByTagNameNS(navpointElement, "", "a"))
        label = labelElement!!.getTextContent()
        if (StringUtil.isNotBlank(label)) {
            return label
        } else {
            labelElement = DOMUtil.getFirstElementByTagNameNS(navpointElement, "", "span")
        }
        checkNotNull(labelElement)
        label = labelElement.getTextContent()
        //如果通过 a 标签无法获取章节列表,则是无href章节名
        return label
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun createNCXResource(book: EpubBook): Resource {
        return createNCXResource(
            book.metadata.getIdentifiers(),
            book.title, book.metadata.authors,
            book.tableOfContents
        )
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun createNCXResource(
        identifiers: MutableList<Identifier?>?,
        title: String?,
        authors: MutableList<Author?>?,
        tableOfContents: TableOfContents
    ): Resource {
        val data = ByteArrayOutputStream()
        val out: XmlSerializer = EpubProcessorSupport.Companion.createXmlSerializer(data)!!
        write(out, identifiers, title, authors, tableOfContents)

        val resource: Resource = Resource(
            NCX_ITEM_ID, data.toByteArray(),
            DEFAULT_NCX_HREF, V3_NCX_MEDIATYPE
        )
        resource.properties = V3_NCX_PROPERTIES
        return resource
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
            xmlSerializer, book.metadata.getIdentifiers(), book.title,
            book.metadata.authors, book.tableOfContents
        )
    }

    /**
     * 写入
     * 
     * @param serializer      serializer
     * @param identifiers     identifiers
     * @param title           title
     * @param authors         authors
     * @param tableOfContents tableOfContents
     */
    @Suppress("unused")
    @Throws(IllegalArgumentException::class, IllegalStateException::class, IOException::class)
    fun write(
        serializer: XmlSerializer,
        identifiers: MutableList<Identifier?>?,
        title: String?,
        authors: MutableList<Author?>?,
        tableOfContents: TableOfContents
    ) {
        serializer.startDocument(Constants.CHARACTER_ENCODING, false)
        serializer.setPrefix(EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX, NAMESPACE_XHTML)
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.html)
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
            XHTMLAttributes.Companion.xmlns_epub,
            NAMESPACE_EPUB
        )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
            XHTMLAttributes.Companion.xml_lang,
            XHTMLAttributeValues.Companion.lang
        )
        serializer.attribute(
            EpubWriter.Companion.EMPTY_NAMESPACE_PREFIX,
            XHTMLAttributes.Companion.lang,
            LANGUAGE
        )
        //写入头部head标签
        writeHead(title, serializer)
        //body开始
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.body)
        //h1开始
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.h1)
        serializer.text(title)
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.h1)
        //h1关闭
        //nav开始
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.nav)
        serializer.attribute(
            "",
            XHTMLAttributes.Companion.epub_type,
            XHTMLAttributeValues.Companion.epub_type
        )
        serializer.attribute(
            "",
            XHTMLAttributes.Companion.id,
            XHTMLAttributeValues.Companion.epub_type
        )
        serializer.attribute(
            "",
            XHTMLAttributes.Companion.role,
            XHTMLAttributeValues.Companion.role_toc
        )
        //h2开始
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.h2)
        serializer.text("目录")
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.h2)


        writeNavPoints(tableOfContents.tocReferences!!, 1, serializer)


        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.nav)

        //body关闭
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.body)


        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.html)
        serializer.endDocument()
    }

    @Throws(IOException::class)
    private fun writeNavPoints(
        tocReferences: MutableList<TOCReference>,
        playOrder: Int,
        serializer: XmlSerializer
    ): Int {
        var playOrder = playOrder
        writeOlStart(serializer)
        for (tocReference in tocReferences) {
            if (tocReference.resource == null) {
                playOrder = writeNavPoints(
                    tocReference.children, playOrder,
                    serializer
                )
                continue
            }


            writeNavPointStart(tocReference, serializer)

            playOrder++
            if (!tocReference.children.isEmpty()) {
                playOrder = writeNavPoints(
                    tocReference.children, playOrder,
                    serializer
                )
            }

            writeNavPointEnd(tocReference, serializer)
        }
        writeOlSEnd(serializer)
        return playOrder
    }

    @Throws(IOException::class)
    private fun writeNavPointStart(tocReference: TOCReference, serializer: XmlSerializer) {
        writeLiStart(serializer)
        val title: String? = tocReference.title
        val href: String? = tocReference.completeHref
        if (StringUtil.isNotBlank(href)) {
            writeLabel(title, href, serializer)
        } else {
            writeLabel(title, serializer)
        }
    }

    @Suppress("unused")
    @Throws(IOException::class)
    private fun writeNavPointEnd(
        tocReference: TOCReference?,
        serializer: XmlSerializer
    ) {
        writeLiEnd(serializer)
    }

    @Throws(IOException::class)
    internal fun writeLabel(title: String?, href: String?, serializer: XmlSerializer) {
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.a)
        serializer.attribute("", XHTMLAttributes.Companion.href, href)
        //attribute必须在Text之前设置。
        serializer.text(title)
        //serializer.attribute(NAMESPACE_XHTML, XHTMLAttributes.href, href);
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.a)
    }

    @Throws(IOException::class)
    internal fun writeLabel(title: String?, serializer: XmlSerializer) {
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.span)
        serializer.text(title)
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.span)
    }

    @Throws(IOException::class)
    private fun writeLiStart(serializer: XmlSerializer) {
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.li)
        Log.d(TAG, "writeLiStart")
    }

    @Throws(IOException::class)
    private fun writeLiEnd(serializer: XmlSerializer) {
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.li)
        Log.d(TAG, "writeLiEND")
    }

    @Throws(IOException::class)
    private fun writeOlStart(serializer: XmlSerializer) {
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.ol)
        Log.d(TAG, "writeOlStart")
    }

    @Throws(IOException::class)
    private fun writeOlSEnd(serializer: XmlSerializer) {
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.ol)
        Log.d(TAG, "writeOlEnd")
    }

    @Throws(IOException::class)
    private fun writeHead(title: String?, serializer: XmlSerializer) {
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.head)
        //title
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.title)
        serializer.text(StringUtil.defaultIfNull(title))
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.title)
        //link
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.link)
        serializer.attribute("", XHTMLAttributes.Companion.rel, "stylesheet")
        serializer.attribute("", XHTMLAttributes.Companion.type, "text/css")
        serializer.attribute("", XHTMLAttributes.Companion.href, "css/style.css")
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.link)

        //meta
        serializer.startTag(NAMESPACE_XHTML, XHTMLTgs.Companion.meta)
        serializer.attribute(
            "",
            XHTMLAttributes.Companion.http_equiv,
            XHTMLAttributeValues.Companion.Content_Type
        )
        serializer.attribute(
            "",
            XHTMLAttributes.Companion.content,
            XHTMLAttributeValues.Companion.HTML_UTF8
        )
        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.meta)

        serializer.endTag(NAMESPACE_XHTML, XHTMLTgs.Companion.head)
    }


    private interface XHTMLTgs {
        companion object {
            const val html: String = "html"
            const val head: String = "head"
            const val title: String = "title"
            const val meta: String = "meta"
            const val link: String = "link"
            const val body: String = "body"
            const val h1: String = "h1"
            const val h2: String = "h2"
            const val nav: String = "nav"
            const val ol: String = "ol"
            const val li: String = "li"
            const val a: String = "a"
            const val span: String = "span"
        }
    }

    private interface XHTMLAttributes {
        companion object {
            const val xmlns: String = "xmlns"
            const val xmlns_epub: String = "xmlns:epub"
            const val lang: String = "lang"
            const val xml_lang: String = "xml:lang"
            const val rel: String = "rel"
            const val type: String = "type"
            const val epub_type: String = "epub:type" //nav的必须属性
            const val id: String = "id"
            const val role: String = "role"
            const val href: String = "href"
            const val http_equiv: String = "http-equiv"
            const val content: String = "content"
        }
    }

    private interface XHTMLAttributeValues {
        companion object {
            const val Content_Type: String = "Content-Type"
            const val HTML_UTF8: String = "text/html; charset=utf-8"
            const val lang: String = "en"
            const val epub_type: String = "toc"
            const val role_toc: String = "doc-toc"
        }
    }
}
