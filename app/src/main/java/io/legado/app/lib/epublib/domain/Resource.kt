package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.util.IOUtil
import io.legado.app.lib.epublib.util.StringUtil
import io.legado.app.lib.epublib.util.commons.io.XmlStreamReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.Serializable

/**
 * Represents a resource that is part of the epub.
 * A resource can be a html file, image, xml, etc.
 * 
 * @author paul
 */
open class Resource : Serializable {
    /**
     * The resources Id.
     * 
     * 
     * Must be both unique within all the resources of this book and a valid identifier.
     * 
     * @return The resources Id.
     */
    /**
     * Sets the Resource's id: Make sure it is unique and a valid identifier.
     * 
     * @param id Resource's id
     */
    var id: String? = null

    /**
     * If the title is found by scanning the underlying html document then it is cached here.
     * 
     * @return the title
     */
    var title: String? = null
    private var href: String? = null


    var properties: String? = null
    protected val originalHref: String?

    /**
     * This resource's mediaType.
     * 
     * @return This resource's mediaType.
     */
    var mediaType: MediaType? = null
    /**
     * The character encoding of the resource.
     * Is allowed to be null for non-text resources like images.
     * 
     * @return The character encoding of the resource.
     */
    /**
     * Sets the Resource's input character encoding.
     * 
     * @param encoding Resource's input character encoding.
     */
    var inputEncoding: String? = null
    /**
     * The contents of the resource as a byte[]
     * 
     * @return The contents of the resource
     */
    /**
     * Sets the data of the Resource.
     * If the data is a of a different type then the original data then make sure to change the MediaType.
     * 
     * @param data the data of the Resource
     */
    @get:Throws(IOException::class)
    open var data: ByteArray? = null

    /**
     * Creates an empty Resource with the given href.
     * 
     * 
     * Assumes that if the data is of a text type (html/css/etc) then the encoding will be UTF-8
     * 
     * @param href The location of the resource within the epub. Example: "chapter1.html".
     */
    constructor(href: String?) : this(null, ByteArray(0), href, MediaTypes.determineMediaType(href))

    /**
     * Creates a Resource with the given data and MediaType.
     * The href will be automatically generated.
     * 
     * 
     * Assumes that if the data is of a text type (html/css/etc) then the encoding will be UTF-8
     * 
     * @param data      The Resource's contents
     * @param mediaType The MediaType of the Resource
     */
    constructor(data: ByteArray?, mediaType: MediaType?) : this(null, data, null, mediaType)

    /**
     * Creates a resource with the given data at the specified href.
     * The MediaType will be determined based on the href extension.
     * 
     * 
     * Assumes that if the data is of a text type (html/css/etc) then the encoding will be UTF-8
     * 
     * @param data The Resource's contents
     * @param href The location of the resource within the epub. Example: "chapter1.html".
     * @see MediaTypes.determineMediaType
     */
    constructor(data: ByteArray?, href: String?) : this(
        null, data, href!!, MediaTypes.determineMediaType(href),
        Constants.CHARACTER_ENCODING
    )

    /**
     * Creates a resource with the data from the given Reader at the specified href.
     * The MediaType will be determined based on the href extension.
     * 
     * @param in   The Resource's contents
     * @param href The location of the resource within the epub. Example: "cover.jpg".
     * @see MediaTypes.determineMediaType
     */
    constructor(`in`: Reader?, href: String?) : this(
        null, IOUtil.toByteArray(`in`!!, Constants.CHARACTER_ENCODING), href!!,
        MediaTypes.determineMediaType(href),
        Constants.CHARACTER_ENCODING
    )

    /**
     * Creates a resource with the data from the given InputStream at the specified href.
     * The MediaType will be determined based on the href extension.
     * 
     * @param in   The Resource's contents
     * @param href The location of the resource within the epub. Example: "cover.jpg".
     * @see MediaTypes.determineMediaType
     */
    constructor(`in`: InputStream?, href: String?) : this(
        null, IOUtil.toByteArray(`in`), href,
        MediaTypes.determineMediaType(href)
    )

    /**
     * Creates a resource with the given id, data, mediatype at the specified href.
     * Assumes that if the data is of a text type (html/css/etc) then the encoding will be UTF-8
     * 
     * @param id        The id of the Resource. Internal use only. Will be auto-generated if it has a null-value.
     * @param data      The Resource's contents
     * @param href      The location of the resource within the epub. Example: "chapter1.html".
     * @param mediaType The resources MediaType
     */
    constructor(id: String?, data: ByteArray?, href: String?, mediaType: MediaType?) : this(
        id,
        data,
        href!!,
        mediaType,
        Constants.CHARACTER_ENCODING
    )

    /**
     * Creates a resource with the given id, data, mediatype at the specified href.
     * If the data is of a text type (html/css/etc) then it will use the given inputEncoding.
     * 
     * @param id            The id of the Resource. Internal use only. Will be auto-generated if it has a null-value.
     * @param data          The Resource's contents
     * @param href          The location of the resource within the epub. Example: "chapter1.html".
     * @param mediaType     The resources MediaType
     * @param inputEncoding If the data is of a text type (html/css/etc) then it will use the given inputEncoding.
     */
    constructor(
        id: String?, data: ByteArray?, href: String, mediaType: MediaType?,
        inputEncoding: String?
    ) {
        this.id = id
        this.href = href
        this.originalHref = href
        this.mediaType = mediaType
        this.inputEncoding = inputEncoding
        this.data = data
    }

    @JvmOverloads
    constructor(
        id: String?, data: ByteArray?, href: String, originalHref: String?, mediaType: MediaType?,
        inputEncoding: String? = Constants.CHARACTER_ENCODING
    ) {
        this.id = id
        this.href = href
        this.originalHref = originalHref
        this.mediaType = mediaType
        this.inputEncoding = inputEncoding
        this.data = data
    }


    @get:Throws(IOException::class)
    open val inputStream: InputStream?
        /**
         * Gets the contents of the Resource as an InputStream.
         * 
         * @return The contents of the Resource.
         * @throws IOException IOException
         */
        get() = ByteArrayInputStream(this.data)

    /**
     * Tells this resource to release its cached data.
     * 
     * 
     * If this resource was not lazy-loaded, this is a no-op.
     */
    open fun close() {
    }

    open val size: Long
        /**
         * Returns the size of this resource in bytes.
         * 
         * @return the size.
         */
        get() = data!!.size.toLong()

    /**
     * The location of the resource within the contents folder of the epub file.
     * 
     * 
     * Example:<br></br>
     * images/cover.jpg<br></br>
     * content/chapter1.xhtml<br></br>
     * 
     * @return The location of the resource within the contents folder of the epub file.
     */
    fun getHref(): String {
        return href!!
    }

    /**
     * Sets the Resource's href.
     * 
     * @param href Resource's href.
     */
    fun setHref(href: String) {
        this.href = href
    }

    @get:Throws(IOException::class)
    val reader: Reader
        /**
         * Gets the contents of the Resource as Reader.
         * 
         * 
         * Does all sorts of smart things (courtesy of apache commons io XMLStreamREader) to handle encodings, byte order markers, etc.
         * 
         * @return the contents of the Resource as Reader.
         * @throws IOException IOException
         */
        get() = XmlStreamReader(
            ByteArrayInputStream(this.data),
            this.inputEncoding
        )

    /**
     * Gets the hashCode of the Resource's href.
     */
    override fun hashCode(): Int {
        return href.hashCode()
    }

    /**
     * Checks to see of the given resourceObject is a resource and whether its href is equal to this one.
     * 
     * @return whether the given resourceObject is a resource and whether its href is equal to this one.
     */
    override fun equals(resourceObject: Any?): Boolean {
        if (resourceObject !is Resource) {
            return false
        }
        return href == resourceObject.getHref()
    }

    override fun toString(): String {
        return StringUtil.toString(
            "id", id,
            "title", title,
            "encoding", inputEncoding,
            "mediaType", mediaType,
            "href", href,
            "size", (if (data == null) 0 else data!!.size)
        )
    }

    companion object {
        private const val serialVersionUID = 1043946707835004037L
    }
}
