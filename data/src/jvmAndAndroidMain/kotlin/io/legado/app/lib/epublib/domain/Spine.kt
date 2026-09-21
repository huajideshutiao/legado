package io.legado.app.lib.epublib.domain

import java.io.Serializable

/**
 * The spine sections are the sections of the book in the order in which the book should be read.
 * 
 * 
 * This contrasts with the Table of Contents sections which is an index into the Book's sections.
 * 
 * @author paul
 * @see TableOfContents
 */
class Spine : Serializable {
    /**
     * The resource containing the XML for the tableOfContents.
     * When saving an epub file this resource needs to be in this place.
     * 
     * @return The resource containing the XML for the tableOfContents.
     */
    /**
     * As per the epub file format the spine officially maintains a reference to the Table of Contents.
     * The epubwriter will look for it here first, followed by some clever tricks to find it elsewhere if not found.
     * Put it here to be sure of the expected behaviours.
     * 
     * @param tocResource tocResource
     */
    var tocResource: Resource? = null
    var spineReferences: MutableList<SpineReference>?

    /**
     * Creates a spine out of all the resources in the table of contents.
     * 
     * @param tableOfContents tableOfContents
     */
    constructor(tableOfContents: TableOfContents) {
        this.spineReferences = createSpineReferences(
            tableOfContents.allUniqueResources
        )
    }

    @JvmOverloads
    constructor(spineReferences: MutableList<SpineReference>? = ArrayList<SpineReference>()) {
        this.spineReferences = spineReferences
    }

    /**
     * Gets the resource at the given index.
     * Null if not found.
     * 
     * @param index index
     * @return the resource at the given index.
     */
    fun getResource(index: Int): Resource? {
        if (index < 0 || index >= spineReferences!!.size) {
            return null
        }
        return spineReferences!!.get(index).resource
    }

    /**
     * Finds the first resource that has the given resourceId.
     * 
     * 
     * Null if not found.
     * 
     * @param resourceId resourceId
     * @return the first resource that has the given resourceId.
     */
    fun findFirstResourceById(resourceId: String): Int {
        if (resourceId.isNullOrBlank()) {
            return -1
        }

        for (i in spineReferences!!.indices) {
            val spineReference = spineReferences!!.get(i)
            if (resourceId == spineReference.resourceId) {
                return i
            }
        }
        return -1
    }

    /**
     * Adds the given spineReference to the spine references and returns it.
     * 
     * @param spineReference spineReference
     * @return the given spineReference
     */
    fun addSpineReference(spineReference: SpineReference?): SpineReference? {
        if (spineReferences == null) {
            this.spineReferences = ArrayList<SpineReference>()
        }
        spineReferences!!.add(spineReference!!)
        return spineReference
    }

    /**
     * Adds the given resource to the spine references and returns it.
     * 
     * @return the given spineReference
     */
    @Suppress("unused")
    fun addResource(resource: Resource?): SpineReference? {
        return addSpineReference(SpineReference(resource))
    }

    /**
     * The number of elements in the spine.
     * 
     * @return The number of elements in the spine.
     */
    fun size(): Int {
        return spineReferences!!.size
    }

    /**
     * The position within the spine of the given resource.
     * 
     * @param currentResource currentResource
     * @return something &lt; 0 if not found.
     */
    fun getResourceIndex(currentResource: Resource?): Int {
        if (currentResource == null) {
            return -1
        }
        return getResourceIndex(currentResource.getHref())
    }

    /**
     * The first position within the spine of a resource with the given href.
     * 
     * @return something &lt; 0 if not found.
     */
    fun getResourceIndex(resourceHref: String): Int {
        var result = -1
        if (resourceHref.isNullOrBlank()) {
            return result
        }
        for (i in spineReferences!!.indices) {
            if (resourceHref == spineReferences!!.get(i).resource?.getHref()) {
                result = i
                break
            }
        }
        return result
    }

    val isEmpty: Boolean
        /**
         * Whether the spine has any references
         * 
         * @return Whether the spine has any references
         */
        get() = spineReferences!!.isEmpty()

    companion object {
        private const val serialVersionUID = 3878483958947357246L
        fun createSpineReferences(
            resources: MutableCollection<Resource?>
        ): MutableList<SpineReference> {
            val result: MutableList<SpineReference> = ArrayList<SpineReference>(
                resources.size
            )
            for (resource in resources) {
                result.add(SpineReference(resource))
            }
            return result
        }
    }
}
