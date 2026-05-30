package io.legado.app.lib.epublib.util

import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.domain.MediaType
import io.legado.app.lib.epublib.domain.MediaTypes
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.epub.EpubProcessorSupport
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.UnsupportedEncodingException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilder

/**
 * Various resource utility methods
 * 
 * @author paul
 */
object ResourceUtil {
    /**
     * 快速创建HTML类型的Resource
     * 
     * @param title 章节的标题
     * @param txt   章节的正文
     * @param model html模板
     * @return 返回Resource
     */
    fun createChapterResource(title: String, txt: String, model: String, href: String?): Resource {
        //String[] title_list = title.split("\\s+", 2);
        //String title_part1 = title_list[0];
        //String title_part2 = "";
        //if (title_list.length == 2) {
        //    title_part2 = title_list[1];
        //}
        var title = title
        val ori_title: String? = title
        title = title.replaceFirst("\\s+".toRegex(), "</span><br />")
        if (title.contains("</span>")) {
            title = "<span class=\"chapter-sequence-number\">" + title
        }
        val html = model.replace("{title}", title)
            .replace("{ori_title}", ori_title!!) //.replace("{title_part1}", title_part1)
            //.replace("{title_part2}", title_part2)
            .replace("{content}", StringUtil.formatHtml(txt))
        return Resource(html.toByteArray(), href)
    }

    fun createPublicResource(
        name: String,
        author: String,
        intro: String?,
        kind: String?,
        wordCount: String?,
        model: String,
        href: String?
    ): Resource {
        val html = model.replace("{name}", name)
            .replace("{author}", author)
            .replace("{kind}", if (kind == null) "" else kind)
            .replace("{wordCount}", if (wordCount == null) "" else wordCount)
            .replace("{intro}", StringUtil.formatHtml(if (intro == null) "" else intro))
        return Resource(html.toByteArray(), href)
    }

    /**
     * 快速从File创建Resource
     * 
     * @param file File
     * @return Resource
     * @throws IOException IOException
     */
    @Suppress("unused")
    @Throws(IOException::class)
    fun createResource(file: File?): Resource? {
        if (file == null) {
            return null
        }
        val mediaType: MediaType? = MediaTypes.determineMediaType(file.getName())
        val data = IOUtil.toByteArray(FileInputStream(file))
        return Resource(data, mediaType)
    }


    /**
     * 创建一个只带标题的HTMl类型的Resource,常用于封面页，大卷页
     * 
     * @param title v
     * @param href  v
     * @return a resource with as contents a html page with the given title.
     */
    @Suppress("unused")
    fun createResource(title: String, href: String?): Resource {
        val content =
            ("<html><head><title>" + title + "</title></head><body><h1>" + title
                + "</h1></body></html>")
        return Resource(
            null, content.toByteArray(), href ?: "", MediaTypes.XHTML,
            Constants.CHARACTER_ENCODING
        )
    }

    /**
     * Creates a resource out of the given zipEntry and zipInputStream.
     * 
     * @param name           v
     * @param zipInputStream v
     * @return a resource created out of the given zipEntry and zipInputStream.
     * @throws IOException v
     */
    @Throws(IOException::class)
    fun createResource(
        name: String?,
        zipInputStream: ZipInputStream?
    ): Resource {
        return Resource(zipInputStream, name)
    }

    @Throws(IOException::class)
    fun createResource(
        name: String?,
        zipInputStream: InputStream?
    ): Resource {
        return Resource(zipInputStream, name)
    }

    /**
     * Converts a given string from given input character encoding to the requested output character encoding.
     * 
     * @param inputEncoding  v
     * @param outputEncoding v
     * @param input          v
     * @return the string from given input character encoding converted to the requested output character encoding.
     * @throws UnsupportedEncodingException v
     */
    @Suppress("unused")
    @Throws(UnsupportedEncodingException::class)
    fun recode(
        inputEncoding: String, outputEncoding: String,
        input: ByteArray?
    ): ByteArray? {
        return kotlin.text.String(input!!, charset(inputEncoding))
            .toByteArray(charset(outputEncoding))
    }

    /**
     * Gets the contents of the Resource as an InputSource in a null-safe manner.
     */
    @Suppress("unused")
    @Throws(IOException::class)
    fun getInputSource(resource: Resource?): InputSource? {
        if (resource == null) {
            return null
        }
        val reader: Reader? = resource.reader
        if (reader == null) {
            return null
        }
        return InputSource(reader)
    }


    /**
     * Reads parses the xml therein and returns the result as a Document
     */
    @Throws(SAXException::class, IOException::class)
    fun getAsDocument(resource: Resource?): Document? {
        return getAsDocument(
            resource,
            EpubProcessorSupport.createDocumentBuilder() ?: return null
        )
    }

    /**
     * Reads the given resources inputstream, parses the xml therein and returns the result as a Document
     * 
     * @param resource        v
     * @param documentBuilder v
     * @return the document created from the given resource
     * @throws UnsupportedEncodingException v
     * @throws SAXException                 v
     * @throws IOException                  v
     */
    @Throws(UnsupportedEncodingException::class, SAXException::class, IOException::class)
    fun getAsDocument(
        resource: Resource?,
        documentBuilder: DocumentBuilder
    ): Document? {
        val inputSource = getInputSource(resource)
        if (inputSource == null) {
            return null
        }
        return documentBuilder.parse(inputSource)
    }
}
