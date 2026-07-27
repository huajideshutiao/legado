package io.legado.app.lib.epublib.domain

import java.io.Serializable

/**
 * An item in the Table of Contents.
 * 
 * @author paul
 * @see TableOfContents
 */
class TOCReference(
    title: String?, resource: Resource?, fragmentId: String?,
    var children: MutableList<TOCReference>
) : TitledResourceReference(resource, title, fragmentId), Serializable {
    @Deprecated("")
    constructor() : this(null, null, null)

    @JvmOverloads
    constructor(name: String?, resource: Resource?, fragmentId: String? = null) : this(
        name,
        resource,
        fragmentId,
        ArrayList<TOCReference>()
    )

    fun addChildSection(childSection: TOCReference): TOCReference {
        this.children.add(childSection)
        return childSection
    }

    companion object {
        private const val serialVersionUID = 5787958246077042456L

        @get:Suppress("unused")
        val comparatorByTitleIgnoreCase: Comparator<TOCReference?> =
            compareBy(String.CASE_INSENSITIVE_ORDER) { it?.title ?: "" }
    }
}
