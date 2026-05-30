package io.legado.app.lib.epublib.domain

import android.util.Base64
import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.util.StringUtil
import java.io.Serializable

/**
 * All the resources that make up the book.
 * XHTML files, images and epub xml documents must be here.
 * 
 * @author paul
 */
class Resources : Serializable {
    private var lastId = 1

    /**
     * The resources that make up this book.
     * Resources can be xhtml pages, images, xml documents, etc.
     * 
     * @return The resources that make up this book.
     */
    @get:Suppress("unused")
    var resourceMap: MutableMap<String?, Resource> = HashMap<String?, Resource>()
        private set

    private val resourcesById: MutableMap<String?, Resource?> = HashMap<String?, Resource?>()

    /**
     * Adds a resource to the resources.
     * 
     * 
     * Fixes the resources id and href if necessary.
     * 
     * @param resource resource
     * @return the newly added resource
     */
    fun add(resource: Resource): Resource {
        fixResourceHref(resource)
        fixResourceId(resource)
        this.resourceMap[resource.getHref()] = resource
        resourcesById[resource.id] = resource
        return resource
    }

    /**
     * Checks the id of the given resource and changes to a unique identifier if it isn't one already.
     * 
     * @param resource resource
     */
    fun fixResourceId(resource: Resource) {
        var resourceId = resource.id

        // first try and create a unique id based on the resource's href
        if (StringUtil.isBlank(resource.id)) {
            resourceId = StringUtil.substringBeforeLast(resource.getHref(), '.')
            resourceId = StringUtil.substringAfterLast(resourceId, '/')
        }

        resourceId = makeValidId(resourceId ?: "", resource)

        // check if the id is unique. if not: create one from scratch
        if (StringUtil.isBlank(resourceId) || containsId(resourceId)) {
            resourceId = createUniqueResourceId(resource)
        }
        resource.id = resourceId
    }

    /**
     * Check if the id is a valid identifier. if not: prepend with valid identifier
     * 
     * @param resource resource
     * @return a valid id
     */
    private fun makeValidId(resourceId: String, resource: Resource): String {
        var resourceId = resourceId
        if (StringUtil.isNotBlank(resourceId) && !Character.isJavaIdentifierStart(resourceId[0])) {
            resourceId = getResourceItemPrefix(resource) + resourceId
        }
        return resourceId
    }

    private fun getResourceItemPrefix(resource: Resource): String {
        val result: String
        if (MediaTypes.isBitmapImage(resource.mediaType)) {
            result = IMAGE_PREFIX
        } else {
            result = ITEM_PREFIX
        }
        return result
    }

    /**
     * Creates a new resource id that is guaranteed to be unique for this set of Resources
     * 
     * @param resource resource
     * @return a new resource id that is guaranteed to be unique for this set of Resources
     */
    private fun createUniqueResourceId(resource: Resource): String {
        var counter = lastId
        if (counter == Int.Companion.MAX_VALUE) {
            require(resourceMap.size != Int.Companion.MAX_VALUE) { ("Resources contains " + Int.Companion.MAX_VALUE + " elements: no new elements can be added") }
            counter = 1
        }
        val prefix = getResourceItemPrefix(resource)
        var result = prefix + counter
        while (containsId(result)) {
            result = prefix + (++counter)
        }
        lastId = counter
        return result
    }

    /**
     * Whether the map of resources already contains a resource with the given id.
     * 
     * @param id id
     * @return Whether the map of resources already contains a resource with the given id.
     */
    fun containsId(id: String?): Boolean {
        if (StringUtil.isBlank(id)) {
            return false
        }
        return resourcesById.containsKey(id)
    }

    /**
     * Gets the resource with the given id.
     * 
     * @param id id
     * @return null if not found
     */
    fun getById(id: String?): Resource? {
        if (StringUtil.isBlank(id)) {
            return null
        }
        return resourcesById[id]
    }

    fun getByProperties(properties: String): Resource? {
        if (StringUtil.isBlank(properties)) {
            return null
        }
        for (resource in resourceMap.values) {
            if (properties == resource.properties) {
                return resource
            }
        }
        return null
    }

    /**
     * Remove the resource with the given href.
     * 
     * @param href href
     * @return the removed resource, null if not found
     */
    fun remove(href: String?): Resource? {
        return resourceMap.remove(href)
    }

    private fun fixResourceHref(resource: Resource) {
        if (StringUtil.isNotBlank(resource.getHref())
            && !resourceMap.containsKey(resource.getHref())
        ) {
            return
        }
        if (StringUtil.isBlank(resource.getHref())) {
            requireNotNull(resource.mediaType) { "Resource must have either a MediaType or a href" }
            var i = 1
            var href = createHref(resource.mediaType!!, i)
            while (resourceMap.containsKey(href)) {
                href = createHref(resource.mediaType!!, (++i))
            }
            resource.setHref(href)
        }
    }

    private fun createHref(mediaType: MediaType, counter: Int): String {
        if (MediaTypes.isBitmapImage(mediaType)) {
            return IMAGE_PREFIX + counter + mediaType.defaultExtension
        } else {
            return ITEM_PREFIX + counter + mediaType.defaultExtension
        }
    }


    val isEmpty: Boolean
        get() = resourceMap.isEmpty()

    /**
     * The number of resources
     * 
     * @return The number of resources
     */
    fun size(): Int {
        return resourceMap.size
    }

    val all: MutableCollection<Resource>
        get() = resourceMap.values


    /**
     * Whether there exists a resource with the given href
     * 
     * @param href href
     * @return Whether there exists a resource with the given href
     */
    fun notContainsByHref(href: String?): Boolean {
        if (StringUtil.isBlank(href)) {
            return true
        } else {
            return !resourceMap.containsKey(
                StringUtil.substringBefore(href, Constants.FRAGMENT_SEPARATOR_CHAR)
            )
        }
    }

    /**
     * Whether there exists a resource with the given href
     * 
     * @param href href
     * @return Whether there exists a resource with the given href
     */
    @Suppress("unused")
    fun containsByHref(href: String?): Boolean {
        return !notContainsByHref(href)
    }

    /**
     * Sets the collection of Resources to the given collection of resources
     * 
     * @param resources resources
     */
    fun set(resources: MutableCollection<Resource>) {
        this.resourceMap.clear()
        resourcesById.clear()
        addAll(resources)
    }

    /**
     * Adds all resources from the given Collection of resources to the existing collection.
     * 
     * @param resources resources
     */
    fun addAll(resources: MutableCollection<Resource>) {
        for (resource in resources) {
            add(resource)
        }
    }

    /**
     * Sets the collection of Resources to the given collection of resources
     * 
     * @param resources A map with as keys the resources href and as values the Resources
     */
    fun set(resources: MutableMap<String?, Resource>) {
        this.resourceMap = HashMap<String?, Resource>(resources)
        resourcesById.clear()
        for (resource in resources.values) {
            resourcesById[resource.id] = resource
        }
    }


    /**
     * First tries to find a resource with as id the given idOrHref, if that
     * fails it tries to find one with the idOrHref as href.
     * 
     * @param idOrHref idOrHref
     * @return the found Resource
     */
    fun getByIdOrHref(idOrHref: String): Resource? {
        var resource = getById(idOrHref)
        if (resource == null) {
            resource = getByHref(idOrHref)
        }
        return resource
    }


    /**
     * Gets the resource with the given href.
     * If the given href contains a fragmentId then that fragment id will be ignored.
     * 
     * @param href href
     * @return null if not found.
     */
    fun getByHref(href: String): Resource? {
        var href = href
        if (StringUtil.isBlank(href)) {
            return null
        }
        href = StringUtil.substringBefore(href, Constants.FRAGMENT_SEPARATOR_CHAR) ?: href

        if (!StringUtil.startsWithIgnoreCase(href, "data")) {
            return resourceMap[href]
        }

        val match = dataUriRegex.find(href)
        if (match != null) {
            val dataUriMediaTypeString = match.groupValues[1]
            val dataUriMediaType = MediaType(
                dataUriMediaTypeString,
                "." + StringUtil.substringAfterLast(dataUriMediaTypeString, '/')
            )
            val dataUriData = Base64.decode(match.groupValues[2], Base64.DEFAULT)
            return Resource(dataUriData, dataUriMediaType)
        } else {
            return resourceMap[href]
        }
    }

    /**
     * Gets the first resource (random order) with the give mediatype.
     * 
     * 
     * Useful for looking up the table of contents as it's supposed to be the only resource with NCX mediatype.
     * 
     * @param mediaType mediaType
     * @return the first resource (random order) with the give mediatype.
     */
    fun findFirstResourceByMediaType(mediaType: MediaType?): Resource? {
        return findFirstResourceByMediaType(
            resourceMap.values, mediaType
        )
    }

    /**
     * All resources that have the given MediaType.
     * 
     * @param mediaType mediaType
     * @return All resources that have the given MediaType.
     */
    fun getResourcesByMediaType(mediaType: MediaType?): MutableList<Resource?> {
        val result: MutableList<Resource?> = ArrayList<Resource?>()
        if (mediaType == null) {
            return result
        }
        for (resource in this.all) {
            if (resource.mediaType === mediaType) {
                result.add(resource)
            }
        }
        return result
    }

    /**
     * All Resources that match any of the given list of MediaTypes
     * 
     * @param mediaTypes mediaType
     * @return All Resources that match any of the given list of MediaTypes
     */
    @Suppress("unused")
    fun getResourcesByMediaTypes(mediaTypes: Array<MediaType?>?): MutableList<Resource?> {
        val result: MutableList<Resource?> = ArrayList<Resource?>()
        if (mediaTypes == null) {
            return result
        }

        val mediaTypesList = mediaTypes.toList()
        for (resource in this.all) {
            if (mediaTypesList.contains(resource.mediaType)) {
                result.add(resource)
            }
        }
        return result
    }


    val allHrefs: MutableCollection<String?>
        /**
         * All resource hrefs
         * 
         * @return all resource hrefs
         */
        get() = resourceMap.keys

    companion object {
        private const val serialVersionUID = 2450876953383871451L
        private const val IMAGE_PREFIX = "image_"
        private const val ITEM_PREFIX = "item_"
        private val dataUriRegex = Regex("data:([\\w/\\-.]+);base64,(.*)")

        /**
         * Gets the first resource (random order) with the give mediatype.
         * 
         * 
         * Useful for looking up the table of contents as it's supposed to be the only resource with NCX mediatype.
         * 
         * @param mediaType mediaType
         * @return the first resource (random order) with the give mediatype.
         */
        fun findFirstResourceByMediaType(
            resources: MutableCollection<Resource>, mediaType: MediaType?
        ): Resource? {
            for (resource in resources) {
                if (resource.mediaType === mediaType) {
                    return resource
                }
            }
            return null
        }
    }
}
