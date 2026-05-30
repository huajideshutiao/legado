package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.Constants
import io.legado.app.lib.epublib.util.StringUtil
import java.io.Serializable

open class TitledResourceReference @JvmOverloads constructor(
    resource: Resource?, title: String?,
    fragmentId: String? = null
) : ResourceReference(resource), Serializable {
    var fragmentId: String? = null
    var title: String? = null

    /**
     * 这会使title为null
     * 
     * @param resource resource
     */
    @Deprecated("")
    @Suppress("unused")
    constructor(resource: Resource?) : this(resource, null)

    init {
        this.title = title
        this.fragmentId = fragmentId
    }


    val completeHref: String?
        /**
         * If the fragmentId is blank it returns the resource href, otherwise
         * it returns the resource href + '#' + the fragmentId.
         * 
         * @return If the fragmentId is blank it returns the resource href,
         * otherwise it returns the resource href + '#' + the fragmentId.
         */
        get() {
            if (StringUtil.isBlank(fragmentId)) {
                return resource?.getHref()
            } else {
                return (resource?.getHref() + Constants.FRAGMENT_SEPARATOR_CHAR
                    + fragmentId)
            }
        }

    fun resolveResource(): Resource? {
        val r = this.resource
        if (r != null && this.title != null) {
            r.title = title
        }
        return resource
    }

    fun setResource(resource: Resource?, fragmentId: String?) {
        this.resource = resource
        this.fragmentId = fragmentId
    }

    companion object {
        private const val serialVersionUID = 3918155020095190080L
    }
}
