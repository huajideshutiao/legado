package io.legado.app.lib.epublib.util.commons.io

/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import io.legado.app.lib.epublib.util.IOUtil
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.charset.Charset
import java.text.MessageFormat
import java.util.Objects
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Character stream that handles all the necessary Voodoo to figure out the
 * charset encoding of the XML document within the stream.
 * 
 * 
 * IMPORTANT: This class is not related in any way to the org.xml.sax.XMLReader.
 * This one IS a character stream.
 * 
 * 
 * 
 * All this has to be done without consuming characters from the stream, if not
 * the XML parser will not recognized the document as a valid XML. This is not
 * 100% true, but it's close enough (UTF-8 BOM is not handled by all parsers
 * right now, XmlStreamReader handles it and things work in all parsers).
 * 
 * 
 * 
 * The XmlStreamReader class handles the charset encoding of XML documents in
 * Files, raw streams and HTTP streams by offering a wide set of constructors.
 * 
 * 
 * 
 * By default the charset encoding detection is lenient, the constructor with
 * the lenient flag can be used for a script (following HTTP MIME and XML
 * specifications). All this is nicely explained by Mark Pilgrim in his blog, [
 * Determining the character encoding of a feed](http://diveintomark.org/archives/2004/02/13/xml-media-types).
 * 
 * 
 * 
 * Originally developed for [ROME](http://rome.dev.java.net) under
 * Apache License 2.0.
 * 
 * 
 * 
 * //@seerr XmlStreamWriter
 * 
 * @since 2.0
 */
class XmlStreamReader : Reader {
    private val reader: Reader

    /**
     * Returns the charset encoding of the XmlStreamReader.
     * 
     * @return charset encoding.
     */
    val encoding: String?

    /**
     * Returns the default encoding to use if none is set in HTTP content-type,
     * XML prolog and the rules based on content-type are not adequate.
     * 
     * 
     * If it is NULL the content-type based rules are used.
     * 
     * @return the default encoding to use.
     */
    val defaultEncoding: String?

    /**
     * Creates a Reader for a File.
     * 
     * 
     * It looks for the UTF-8 BOM first, if none sniffs the XML prolog charset,
     * if this is also missing defaults to UTF-8.
     * 
     * 
     * It does a lenient charset encoding detection, check the constructor with
     * the lenient parameter for details.
     * 
     * @param file File to create a Reader from.
     * @throws IOException thrown if there is a problem reading the file.
     */
    @Suppress("unused")
    constructor(file: File?) : this(FileInputStream(Objects.requireNonNull<File?>(file)))

    /**
     * Creates a Reader for a raw InputStream.
     * 
     * 
     * It follows the same logic used for files.
     * 
     * 
     * If lenient detection is indicated and the detection above fails as per
     * specifications it then attempts the following:
     * 
     * 
     * If the content type was 'text/html' it replaces it with 'text/xml' and
     * tries the detection again.
     * 
     * 
     * Else if the XML prolog had a charset encoding that encoding is used.
     * 
     * 
     * Else if the content type had a charset encoding that encoding is used.
     * 
     * 
     * Else 'UTF-8' is used.
     * 
     * 
     * If lenient detection is indicated an XmlStreamReaderException is never
     * thrown.
     * 
     * @param inputStream     InputStream to create a Reader from.
     * @param lenient         indicates if the charset encoding detection should be
     * relaxed.
     * @param defaultEncoding The default encoding
     * @throws IOException              thrown if there is a problem reading the stream.
     * @throws XmlStreamReaderException thrown if the charset encoding could not
     * be determined according to the specs.
     */
    /**
     * Creates a Reader for a raw InputStream.
     * 
     * 
     * It follows the same logic used for files.
     * 
     * 
     * If lenient detection is indicated and the detection above fails as per
     * specifications it then attempts the following:
     * 
     * 
     * If the content type was 'text/html' it replaces it with 'text/xml' and
     * tries the detection again.
     * 
     * 
     * Else if the XML prolog had a charset encoding that encoding is used.
     * 
     * 
     * Else if the content type had a charset encoding that encoding is used.
     * 
     * 
     * Else 'UTF-8' is used.
     * 
     * 
     * If lenient detection is indicated an XmlStreamReaderException is never
     * thrown.
     * 
     * @param inputStream InputStream to create a Reader from.
     * @param lenient     indicates if the charset encoding detection should be
     * relaxed.
     * @throws IOException              thrown if there is a problem reading the stream.
     * @throws XmlStreamReaderException thrown if the charset encoding could not
     * be determined according to the specs.
     */
    /**
     * Creates a Reader for a raw InputStream.
     * 
     * 
     * It follows the same logic used for files.
     * 
     * 
     * It does a lenient charset encoding detection, check the constructor with
     * the lenient parameter for details.
     * 
     * @param inputStream InputStream to create a Reader from.
     * @throws IOException thrown if there is a problem reading the stream.
     */
    @JvmOverloads
    constructor(
        inputStream: InputStream?,
        lenient: Boolean = true,
        defaultEncoding: String? = null
    ) {
        Objects.requireNonNull<InputStream?>(inputStream, "inputStream")
        this.defaultEncoding = defaultEncoding
        val bom = BOMInputStream(BufferedInputStream(inputStream, BUFFER_SIZE), false, *BOMS)
        val pis = BOMInputStream(bom, true, *XML_GUESS_BYTES)
        this.encoding = doRawStream(bom, pis, lenient)
        this.reader = InputStreamReader(pis, encoding)
    }

    /**
     * Creates a Reader using the InputStream of a URL.
     * 
     * 
     * If the URL is not of type HTTP and there is not 'content-type' header in
     * the fetched data it uses the same logic used for Files.
     * 
     * 
     * If the URL is a HTTP Url or there is a 'content-type' header in the
     * fetched data it uses the same logic used for an InputStream with
     * content-type.
     * 
     * 
     * It does a lenient charset encoding detection, check the constructor with
     * the lenient parameter for details.
     * 
     * @param url URL to create a Reader from.
     * @throws IOException thrown if there is a problem reading the stream of
     * the URL.
     */
    @Suppress("unused")
    constructor(url: URL?) : this(Objects.requireNonNull<URL?>(url, "url").openConnection(), null)

    /**
     * Creates a Reader using the InputStream of a URLConnection.
     * 
     * 
     * If the URLConnection is not of type HttpURLConnection and there is not
     * 'content-type' header in the fetched data it uses the same logic used for
     * files.
     * 
     * 
     * If the URLConnection is a HTTP Url or there is a 'content-type' header in
     * the fetched data it uses the same logic used for an InputStream with
     * content-type.
     * 
     * 
     * It does a lenient charset encoding detection, check the constructor with
     * the lenient parameter for details.
     * 
     * @param conn            URLConnection to create a Reader from.
     * @param defaultEncoding The default encoding
     * @throws IOException thrown if there is a problem reading the stream of
     * the URLConnection.
     */
    constructor(conn: URLConnection?, defaultEncoding: String?) {
        Objects.requireNonNull<URLConnection?>(conn, "conm")
        this.defaultEncoding = defaultEncoding
        val lenient = true
        val contentType = conn!!.getContentType()
        val inputStream = conn.getInputStream()
        val bom = BOMInputStream(BufferedInputStream(inputStream, BUFFER_SIZE), false, *BOMS)
        val pis = BOMInputStream(bom, true, *XML_GUESS_BYTES)
        if (conn is HttpURLConnection || contentType != null) {
            this.encoding = processHttpStream(bom, pis, contentType, lenient)
        } else {
            this.encoding = doRawStream(bom, pis, lenient)
        }
        this.reader = InputStreamReader(pis, encoding)
    }

    /**
     * Creates a Reader using an InputStream and the associated content-type
     * header. This constructor is lenient regarding the encoding detection.
     * 
     * 
     * First it checks if the stream has BOM. If there is not BOM checks the
     * content-type encoding. If there is not content-type encoding checks the
     * XML prolog encoding. If there is not XML prolog encoding uses the default
     * encoding mandated by the content-type MIME type.
     * 
     * 
     * If lenient detection is indicated and the detection above fails as per
     * specifications it then attempts the following:
     * 
     * 
     * If the content type was 'text/html' it replaces it with 'text/xml' and
     * tries the detection again.
     * 
     * 
     * Else if the XML prolog had a charset encoding that encoding is used.
     * 
     * 
     * Else if the content type had a charset encoding that encoding is used.
     * 
     * 
     * Else 'UTF-8' is used.
     * 
     * 
     * If lenient detection is indicated an XmlStreamReaderException is never
     * thrown.
     * 
     * @param inputStream     InputStream to create the reader from.
     * @param httpContentType content-type header to use for the resolution of
     * the charset encoding.
     * @param lenient         indicates if the charset encoding detection should be
     * relaxed.
     * @param defaultEncoding The default encoding
     * @throws IOException              thrown if there is a problem reading the file.
     * @throws XmlStreamReaderException thrown if the charset encoding could not
     * be determined according to the specs.
     */
    /**
     * Creates a Reader using an InputStream and the associated content-type
     * header. This constructor is lenient regarding the encoding detection.
     * 
     * 
     * First it checks if the stream has BOM. If there is not BOM checks the
     * content-type encoding. If there is not content-type encoding checks the
     * XML prolog encoding. If there is not XML prolog encoding uses the default
     * encoding mandated by the content-type MIME type.
     * 
     * 
     * If lenient detection is indicated and the detection above fails as per
     * specifications it then attempts the following:
     * 
     * 
     * If the content type was 'text/html' it replaces it with 'text/xml' and
     * tries the detection again.
     * 
     * 
     * Else if the XML prolog had a charset encoding that encoding is used.
     * 
     * 
     * Else if the content type had a charset encoding that encoding is used.
     * 
     * 
     * Else 'UTF-8' is used.
     * 
     * 
     * If lenient detection is indicated an XmlStreamReaderException is never
     * thrown.
     * 
     * @param inputStream     InputStream to create the reader from.
     * @param httpContentType content-type header to use for the resolution of
     * the charset encoding.
     * @param lenient         indicates if the charset encoding detection should be
     * relaxed.
     * @throws IOException              thrown if there is a problem reading the file.
     * @throws XmlStreamReaderException thrown if the charset encoding could not
     * be determined according to the specs.
     */
    /**
     * Creates a Reader using an InputStream and the associated content-type
     * header.
     * 
     * 
     * First it checks if the stream has BOM. If there is not BOM checks the
     * content-type encoding. If there is not content-type encoding checks the
     * XML prolog encoding. If there is not XML prolog encoding uses the default
     * encoding mandated by the content-type MIME type.
     * 
     * 
     * It does a lenient charset encoding detection, check the constructor with
     * the lenient parameter for details.
     * 
     * @param inputStream     InputStream to create the reader from.
     * @param httpContentType content-type header to use for the resolution of
     * the charset encoding.
     * @throws IOException thrown if there is a problem reading the file.
     */
    @JvmOverloads
    constructor(
        inputStream: InputStream?, httpContentType: String?,
        lenient: Boolean = true, defaultEncoding: String? = null
    ) {
        Objects.requireNonNull<InputStream?>(inputStream, "inputStream")
        this.defaultEncoding = defaultEncoding
        val bom = BOMInputStream(BufferedInputStream(inputStream, BUFFER_SIZE), false, *BOMS)
        val pis = BOMInputStream(bom, true, *XML_GUESS_BYTES)
        this.encoding = processHttpStream(bom, pis, httpContentType, lenient)
        this.reader = InputStreamReader(pis, encoding)
    }

    /**
     * Invokes the underlying reader's `read(char[], int, int)` method.
     * 
     * @param buf    the buffer to read the characters into
     * @param offset The start offset
     * @param len    The number of bytes to read
     * @return the number of characters read or -1 if the end of stream
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    override fun read(buf: CharArray?, offset: Int, len: Int): Int {
        return reader.read(buf, offset, len)
    }

    /**
     * Closes the XmlStreamReader stream.
     * 
     * @throws IOException thrown if there was a problem closing the stream.
     */
    @Throws(IOException::class)
    override fun close() {
        reader.close()
    }

    /**
     * Process the raw stream.
     * 
     * @param bom     BOMInputStream to detect byte order marks
     * @param pis     BOMInputStream to guess XML encoding
     * @param lenient indicates if the charset encoding detection should be
     * relaxed.
     * @return the encoding to be used
     * @throws IOException thrown if there is a problem reading the stream.
     */
    @Throws(IOException::class)
    private fun doRawStream(bom: BOMInputStream, pis: BOMInputStream, lenient: Boolean): String? {
        val bomEnc = bom.bOMCharsetName
        val xmlGuessEnc = pis.bOMCharsetName
        val xmlEnc: String? = getXmlProlog(pis, xmlGuessEnc)
        try {
            return calculateRawEncoding(bomEnc, xmlGuessEnc, xmlEnc)
        } catch (ex: XmlStreamReaderException) {
            if (lenient) {
                return doLenientDetection(null, ex)
            }
            throw ex
        }
    }

    /**
     * Process a HTTP stream.
     * 
     * @param bom             BOMInputStream to detect byte order marks
     * @param pis             BOMInputStream to guess XML encoding
     * @param httpContentType The HTTP content type
     * @param lenient         indicates if the charset encoding detection should be
     * relaxed.
     * @return the encoding to be used
     * @throws IOException thrown if there is a problem reading the stream.
     */
    @Throws(IOException::class)
    private fun processHttpStream(
        bom: BOMInputStream, pis: BOMInputStream, httpContentType: String?,
        lenient: Boolean
    ): String? {
        val bomEnc = bom.bOMCharsetName
        val xmlGuessEnc = pis.bOMCharsetName
        val xmlEnc: String? = getXmlProlog(pis, xmlGuessEnc)
        try {
            return calculateHttpEncoding(httpContentType, bomEnc, xmlGuessEnc, xmlEnc, lenient)
        } catch (ex: XmlStreamReaderException) {
            if (lenient) {
                return doLenientDetection(httpContentType, ex)
            }
            throw ex
        }
    }

    /**
     * Do lenient detection.
     * 
     * @param httpContentType content-type header to use for the resolution of
     * the charset encoding.
     * @param ex              The thrown exception
     * @return the encoding
     * @throws IOException thrown if there is a problem reading the stream.
     */
    @Throws(IOException::class)
    private fun doLenientDetection(
        httpContentType: String?,
        ex: XmlStreamReaderException
    ): String? {
        var httpContentType = httpContentType
        var ex = ex
        if (httpContentType != null && httpContentType.startsWith("text/html")) {
            httpContentType = httpContentType.substring("text/html".length)
            httpContentType = "text/xml" + httpContentType
            try {
                return calculateHttpEncoding(
                    httpContentType, ex.bomEncoding,
                    ex.xmlGuessEncoding, ex.xmlEncoding, true
                )
            } catch (ex2: XmlStreamReaderException) {
                ex = ex2
            }
        }
        var encoding = ex.xmlEncoding
        if (encoding == null) {
            encoding = ex.contentTypeEncoding
        }
        if (encoding == null) {
            encoding = if (defaultEncoding == null) UTF_8 else defaultEncoding
        }
        return encoding
    }

    /**
     * Calculate the raw encoding.
     * 
     * @param bomEnc      BOM encoding
     * @param xmlGuessEnc XML Guess encoding
     * @param xmlEnc      XML encoding
     * @return the raw encoding
     * @throws IOException thrown if there is a problem reading the stream.
     */
    @Throws(IOException::class)
    fun calculateRawEncoding(
        bomEnc: String?, xmlGuessEnc: String?,
        xmlEnc: String?
    ): String {
        // BOM is Null

        if (bomEnc == null) {
            if (xmlGuessEnc == null || xmlEnc == null) {
                return if (defaultEncoding == null) UTF_8 else defaultEncoding
            }
            if (xmlEnc == UTF_16 &&
                (xmlGuessEnc == UTF_16BE || xmlGuessEnc == UTF_16LE)
            ) {
                return xmlGuessEnc
            }
            return xmlEnc
        }

        // BOM is UTF-8
        if (bomEnc == UTF_8) {
            if (xmlGuessEnc != null && xmlGuessEnc != UTF_8) {
                val msg = MessageFormat.format(RAW_EX_1, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
            }
            if (xmlEnc != null && xmlEnc != UTF_8) {
                val msg = MessageFormat.format(RAW_EX_1, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
            }
            return bomEnc
        }

        // BOM is UTF-16BE or UTF-16LE
        if (bomEnc == UTF_16BE || bomEnc == UTF_16LE) {
            if (xmlGuessEnc != null && xmlGuessEnc != bomEnc) {
                val msg = MessageFormat.format(RAW_EX_1, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
            }
            if (xmlEnc != null && (xmlEnc != UTF_16) && (xmlEnc != bomEnc)) {
                val msg = MessageFormat.format(RAW_EX_1, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
            }
            return bomEnc
        }

        // BOM is UTF-32BE or UTF-32LE
        if (bomEnc == UTF_32BE || bomEnc == UTF_32LE) {
            if (xmlGuessEnc != null && xmlGuessEnc != bomEnc) {
                val msg = MessageFormat.format(RAW_EX_1, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
            }
            if (xmlEnc != null && (xmlEnc != UTF_32) && (xmlEnc != bomEnc)) {
                val msg = MessageFormat.format(RAW_EX_1, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
            }
            return bomEnc
        }

        // BOM is something else
        val msg = MessageFormat.format(RAW_EX_2, bomEnc, xmlGuessEnc, xmlEnc)
        throw XmlStreamReaderException(msg, bomEnc, xmlGuessEnc, xmlEnc)
    }


    /**
     * Calculate the HTTP encoding.
     * 
     * @param httpContentType The HTTP content type
     * @param bomEnc          BOM encoding
     * @param xmlGuessEnc     XML Guess encoding
     * @param xmlEnc          XML encoding
     * @param lenient         indicates if the charset encoding detection should be
     * relaxed.
     * @return the HTTP encoding
     * @throws IOException thrown if there is a problem reading the stream.
     */
    @Throws(IOException::class)
    fun calculateHttpEncoding(
        httpContentType: String?,
        bomEnc: String?, xmlGuessEnc: String?, xmlEnc: String?,
        lenient: Boolean
    ): String? {
        // Lenient and has XML encoding

        if (lenient && xmlEnc != null) {
            return xmlEnc
        }

        // Determine mime/encoding content types from HTTP Content Type
        val cTMime: String? = getContentTypeMime(httpContentType)
        val cTEnc: String? = getContentTypeEncoding(httpContentType)
        val appXml: Boolean = isAppXml(cTMime)
        val textXml: Boolean = isTextXml(cTMime)

        // Mime type NOT "application/xml" or "text/xml"
        if (!appXml && !textXml) {
            val msg = MessageFormat.format(HTTP_EX_3, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
            throw XmlStreamReaderException(msg, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
        }

        // No content type encoding
        if (cTEnc == null) {
            if (appXml) {
                return calculateRawEncoding(bomEnc, xmlGuessEnc, xmlEnc)
            }
            return if (defaultEncoding == null) US_ASCII else defaultEncoding
        }

        // UTF-16BE or UTF-16LE content type encoding
        if (cTEnc == UTF_16BE || cTEnc == UTF_16LE) {
            if (bomEnc != null) {
                val msg =
                    MessageFormat.format(HTTP_EX_1, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
            }
            return cTEnc
        }

        // UTF-16 content type encoding
        if (cTEnc == UTF_16) {
            if (bomEnc != null && bomEnc.startsWith(UTF_16)) {
                return bomEnc
            }
            val msg = MessageFormat.format(HTTP_EX_2, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
            throw XmlStreamReaderException(msg, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
        }

        // UTF-32BE or UTF-132E content type encoding
        if (cTEnc == UTF_32BE || cTEnc == UTF_32LE) {
            if (bomEnc != null) {
                val msg =
                    MessageFormat.format(HTTP_EX_1, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
                throw XmlStreamReaderException(msg, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
            }
            return cTEnc
        }

        // UTF-32 content type encoding
        if (cTEnc == UTF_32) {
            if (bomEnc != null && bomEnc.startsWith(UTF_32)) {
                return bomEnc
            }
            val msg = MessageFormat.format(HTTP_EX_2, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
            throw XmlStreamReaderException(msg, cTMime, cTEnc, bomEnc, xmlGuessEnc, xmlEnc)
        }

        return cTEnc
    }

    companion object {
        private val BUFFER_SIZE: Int = IOUtil.DEFAULT_BUFFER_SIZE

        private const val UTF_8 = "UTF-8"

        private const val US_ASCII = "US-ASCII"

        private const val UTF_16BE = "UTF-16BE"

        private const val UTF_16LE = "UTF-16LE"

        private const val UTF_32BE = "UTF-32BE"

        private const val UTF_32LE = "UTF-32LE"

        private const val UTF_16 = "UTF-16"

        private const val UTF_32 = "UTF-32"

        private const val EBCDIC = "CP1047"

        private val BOMS: Array<ByteOrderMark> = arrayOf<ByteOrderMark>(
            ByteOrderMark.Companion.UTF_8,
            ByteOrderMark.Companion.UTF_16BE,
            ByteOrderMark.Companion.UTF_16LE,
            ByteOrderMark.Companion.UTF_32BE,
            ByteOrderMark.Companion.UTF_32LE
        )

        // UTF_16LE and UTF_32LE have the same two starting BOM bytes.
        private val XML_GUESS_BYTES: Array<ByteOrderMark> = arrayOf<ByteOrderMark>(
            ByteOrderMark(
                UTF_8, 0x3C, 0x3F, 0x78, 0x6D
            ),
            ByteOrderMark(UTF_16BE, 0x00, 0x3C, 0x00, 0x3F),
            ByteOrderMark(UTF_16LE, 0x3C, 0x00, 0x3F, 0x00),
            ByteOrderMark(
                UTF_32BE, 0x00, 0x00, 0x00, 0x3C,
                0x00, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x00, 0x78, 0x00, 0x00, 0x00, 0x6D
            ),
            ByteOrderMark(
                UTF_32LE, 0x3C, 0x00, 0x00, 0x00,
                0x3F, 0x00, 0x00, 0x00, 0x78, 0x00, 0x00, 0x00, 0x6D, 0x00, 0x00, 0x00
            ),
            ByteOrderMark(EBCDIC, 0x4C, 0x6F, 0xA7, 0x94)
        )

        /**
         * Returns MIME type or NULL if httpContentType is NULL.
         * 
         * @param httpContentType the HTTP content type
         * @return The mime content type
         */
        fun getContentTypeMime(httpContentType: String?): String? {
            var mime: String? = null
            if (httpContentType != null) {
                val i = httpContentType.indexOf(";")
                if (i >= 0) {
                    mime = httpContentType.substring(0, i)
                } else {
                    mime = httpContentType
                }
                mime = mime.trim()
            }
            return mime
        }

        private val CHARSET_PATTERN: Pattern = Pattern
            .compile("charset=[\"']?([.[^; \"']]*)[\"']?")

        /**
         * Returns charset parameter value, NULL if not present, NULL if
         * httpContentType is NULL.
         * 
         * @param httpContentType the HTTP content type
         * @return The content type encoding (upcased)
         */
        fun getContentTypeEncoding(httpContentType: String?): String? {
            var encoding: String? = null
            if (httpContentType != null) {
                val i = httpContentType.indexOf(";")
                if (i > -1) {
                    val postMime = httpContentType.substring(i + 1)
                    val m: Matcher = CHARSET_PATTERN.matcher(postMime)
                    encoding = if (m.find()) m.group(1) else null
                    encoding = if (encoding != null) encoding.uppercase() else null
                }
            }
            return encoding
        }

        /**
         * Pattern capturing the encoding of the "xml" processing instruction.
         */
        val ENCODING_PATTERN: Pattern = Pattern.compile(
            "<\\?xml.*encoding[\\s]*=[\\s]*((?:\".[^\"]*\")|(?:'.[^']*'))",
            Pattern.MULTILINE
        )

        /**
         * Returns the encoding declared in the , NULL if none.
         * 
         * @param inputStream InputStream to create the reader from.
         * @param guessedEnc  guessed encoding
         * @return the encoding declared in the 
         * @throws IOException thrown if there is a problem reading the stream.
         */
        @Throws(IOException::class)
        private fun getXmlProlog(inputStream: InputStream, guessedEnc: String?): String? {
            var encoding: String? = null
            if (guessedEnc != null) {
                val bytes = ByteArray(BUFFER_SIZE)
                inputStream.mark(BUFFER_SIZE)
                var offset = 0
                var max: Int = BUFFER_SIZE
                var c = inputStream.read(bytes, offset, max)
                var firstGT = -1
                var xmlProlog =
                    "" // avoid possible NPE warning (cannot happen; this just silences the warning)
                while (c != -1 && firstGT == -1 && offset < BUFFER_SIZE) {
                    offset += c
                    max -= c
                    c = inputStream.read(bytes, offset, max)
                    xmlProlog = String(bytes, 0, offset, charset(guessedEnc))
                    firstGT = xmlProlog.indexOf('>')
                }
                if (firstGT == -1) {
                    if (c == -1) {
                        throw IOException("Unexpected end of XML stream")
                    }
                    throw IOException(
                        ("XML prolog or ROOT element not found on first "
                            + offset + " bytes")
                    )
                }
                val bytesRead = offset
                if (bytesRead > 0) {
                    inputStream.reset()
                    val bReader = BufferedReader(
                        StringReader(
                            xmlProlog.substring(0, firstGT + 1)
                        )
                    )
                    val prolog = StringBuffer()
                    var line: String?
                    while ((bReader.readLine().also { line = it }) != null) {
                        prolog.append(line)
                    }
                    val m: Matcher = ENCODING_PATTERN.matcher(prolog)
                    if (m.find()) {
                        encoding = Objects.requireNonNull<String?>(m.group(1)).uppercase()
                        encoding = encoding.substring(1, encoding.length - 1)
                    }
                }
            }
            val isSupportedEncoding: Boolean
            try {
                isSupportedEncoding = Charset.isSupported(encoding)
            } catch (e: Exception) {
                return null
            }
            if (isSupportedEncoding) {
                return encoding
            } else {
                return null
            }
        }

        /**
         * Indicates if the MIME type belongs to the APPLICATION XML family.
         * 
         * @param mime The mime type
         * @return true if the mime type belongs to the APPLICATION XML family,
         * otherwise false
         */
        fun isAppXml(mime: String?): Boolean {
            return mime != null &&
                (mime == "application/xml" ||
                    mime == "application/xml-dtd" ||
                    mime == "application/xml-external-parsed-entity" || mime.startsWith("application/") && mime.endsWith(
                    "+xml"
                ))
        }

        /**
         * Indicates if the MIME type belongs to the TEXT XML family.
         * 
         * @param mime The mime type
         * @return true if the mime type belongs to the TEXT XML family,
         * otherwise false
         */
        fun isTextXml(mime: String?): Boolean {
            return mime != null &&
                (mime == "text/xml" ||
                    mime == "text/xml-external-parsed-entity" || mime.startsWith("text/") && mime.endsWith(
                    "+xml"
                ))
        }

        private const val RAW_EX_1 =
            "Invalid encoding, BOM [{0}] XML guess [{1}] XML prolog [{2}] encoding mismatch"

        private const val RAW_EX_2 =
            "Invalid encoding, BOM [{0}] XML guess [{1}] XML prolog [{2}] unknown BOM"

        private const val HTTP_EX_1 =
            "Invalid encoding, CT-MIME [{0}] CT-Enc [{1}] BOM [{2}] XML guess [{3}] XML prolog [{4}], BOM must be NULL"

        private const val HTTP_EX_2 =
            "Invalid encoding, CT-MIME [{0}] CT-Enc [{1}] BOM [{2}] XML guess [{3}] XML prolog [{4}], encoding mismatch"

        private const val HTTP_EX_3 =
            "Invalid encoding, CT-MIME [{0}] CT-Enc [{1}] BOM [{2}] XML guess [{3}] XML prolog [{4}], Invalid MIME"
    }
}