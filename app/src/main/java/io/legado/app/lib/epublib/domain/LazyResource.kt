package io.legado.app.lib.epublib.domain

import android.util.Log
import io.legado.app.lib.epublib.util.IOUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A Resource that loads its data only on-demand from a EPUB book file.
 * This way larger books can fit into memory and can be opened faster.
 */
class LazyResource : Resource {
    private val TAG: String = javaClass.name

    private val resourceProvider: LazyResourceProvider?
    private val cachedSize: Long

    /**
     * Creates a lazy resource, when the size is unknown.
     * 
     * @param resourceProvider The resource provider loads data on demand.
     * @param href             The resource's href within the epub.
     */
    constructor(resourceProvider: LazyResourceProvider?, href: String?) : this(
        resourceProvider,
        -1,
        href
    )

    constructor(
        resourceProvider: LazyResourceProvider?,
        href: String?,
        originalHref: String?
    ) : this(resourceProvider, -1, href, originalHref)

    /**
     * Creates a Lazy resource, by not actually loading the data for this entry.
     * 
     * 
     * The data will be loaded on the first call to getData()
     * 
     * @param resourceProvider The resource provider loads data on demand.
     * @param size             The size of this resource.
     * @param href             The resource's href within the epub.
     */
    constructor(resourceProvider: LazyResourceProvider?, size: Long, href: String?) : super(
        null,
        null,
        href ?: "",
        MediaTypes.determineMediaType(href)
    ) {
        this.resourceProvider = resourceProvider
        this.cachedSize = size
    }

    constructor(
        resourceProvider: LazyResourceProvider?,
        size: Long,
        href: String?,
        originalHref: String?
    ) : super(null, null, href ?: "", originalHref, MediaTypes.determineMediaType(href)) {
        this.resourceProvider = resourceProvider
        this.cachedSize = size
    }

    /**
     * Gets the contents of the Resource as an InputStream.
     * 
     * @return The contents of the Resource.
     * @throws IOException IOException
     */
    @get:Throws(IOException::class)
    override val inputStream: InputStream?
        get() = if (isInitialized) ByteArrayInputStream(data) else resourceProvider!!.getResourceStream(
            this.originalHref
        )

    /**
     * Initializes the resource by loading its data into memory.
     * 
     * @throws IOException IOException
     */
    @Throws(IOException::class)
    fun initialize() {
        @Suppress("UNUSED_EXPRESSION")
        data
    }

    /**
     * The contents of the resource as a byte[]
     * 
     * 
     * If this resource was lazy-loaded and the data was not yet loaded,
     * it will be loaded into memory at this point.
     * This included opening the zip file, so expect a first load to be slow.
     * 
     * @return The contents of the resource
     */
    @get:Throws(IOException::class)
    override var data: ByteArray?
        get() {
            if (super.data == null) {
                Log.d(TAG, "Initializing lazy resource: ${this.getHref()}")
                resourceProvider!!.getResourceStream(this.originalHref).use { stream ->
                    super.data = IOUtil.toByteArray(stream, this.cachedSize.toInt())
                        ?: throw IOException("Could not load the contents of resource: ${this.getHref()}")
                }
            }
            return super.data
        }
        set(value) {
            super.data = value
        }

    /**
     * Tells this resource to release its cached data.
     * 
     * 
     * If this resource was not lazy-loaded, this is a no-op.
     */
    override fun close() {
        if (resourceProvider != null) super.data = null
    }

    val isInitialized: Boolean
        get() = super.data != null

    /**
     * Returns the size of this resource in bytes.
     * 
     * @return the size.
     */
    override val size: Long get() = if (super.data != null) super.data!!.size.toLong() else cachedSize

    companion object {
        private const val serialVersionUID = 5089400472352002866L
    }
}
