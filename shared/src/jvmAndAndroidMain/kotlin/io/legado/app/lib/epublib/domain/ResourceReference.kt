package io.legado.app.lib.epublib.domain

import java.io.Serializable

open class ResourceReference(
    /**
     * Besides setting the resource it also sets the fragmentId to null.
     * 
     * @param resource resource
     */
    var resource: Resource?
) : Serializable {
    val resourceId: String?
        /**
         * The id of the reference referred to.
         * 
         * 
         * null of the reference is null or has a null id itself.
         * 
         * @return The id of the reference referred to.
         */
        get() {
            if (resource != null) {
                return resource!!.id
            }
            return null
        }

    companion object {
        private const val serialVersionUID = 2596967243557743048L
    }
}
