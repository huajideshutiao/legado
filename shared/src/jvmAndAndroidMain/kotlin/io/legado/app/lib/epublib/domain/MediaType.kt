package io.legado.app.lib.epublib.domain

import java.io.Serializable
import java.util.Arrays

/**
 * MediaType is used to tell the type of content a resource is.
 * 
 * 
 * Examples of mediatypes are image/gif, text/css and application/xhtml+xml
 * 
 * 
 * All allowed mediaTypes are maintained bye the MediaTypeService.
 * 
 * @author paul
 * @see MediaTypes
 */
class MediaType(
    val name: String?, val defaultExtension: String?,
    val extensions: MutableCollection<String?>?
) : Serializable {
    @JvmOverloads
    constructor(
        name: String?, defaultExtension: String?,
        extensions: Array<String?> = arrayOf<String?>(defaultExtension)
    ) : this(name, defaultExtension, Arrays.asList<String?>(*extensions))

    override fun hashCode(): Int {
        if (name == null) {
            return 0
        }
        return name.hashCode()
    }


    override fun equals(otherMediaType: Any?): Boolean {
        if (otherMediaType !is MediaType) {
            return false
        }
        return name == otherMediaType.name
    }

    override fun toString(): String {
        return name!!
    }

    companion object {
        private val serialVersionUID = -7256091153727506788L
    }
}
