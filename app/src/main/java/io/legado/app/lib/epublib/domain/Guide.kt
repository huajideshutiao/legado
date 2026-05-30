package io.legado.app.lib.epublib.domain

import java.io.Serializable

/**
 * The guide is a selection of special pages of the book.
 * Examples of these are the cover, list of illustrations, etc.
 * 
 * 
 * It is an optional part of an epub, and support for the various types
 * of references varies by reader.
 * 
 * 
 * The only part of this that is heavily used is the cover page.
 * 
 * @author paul
 */
class Guide : Serializable {
    private var references: MutableList<GuideReference> = ArrayList<GuideReference>()
    private var coverPageIndex = -1

    fun getReferences(): MutableList<GuideReference> {
        return references
    }

    fun setReferences(references: MutableList<GuideReference>) {
        this.references = references
        uncheckCoverPage()
    }

    private fun uncheckCoverPage() {
        coverPageIndex = COVERPAGE_UNITIALIZED
    }

    val coverReference: GuideReference?
        get() {
            checkCoverPage()
            if (coverPageIndex >= 0) {
                return references.get(coverPageIndex)
            }
            return null
        }

    fun setCoverReference(guideReference: GuideReference?): Int {
        if (coverPageIndex >= 0) {
            references.set(coverPageIndex, guideReference!!)
        } else {
            references.add(0, guideReference!!)
            coverPageIndex = 0
        }
        return coverPageIndex
    }

    private fun checkCoverPage() {
        if (coverPageIndex == COVERPAGE_UNITIALIZED) {
            initCoverPage()
        }
    }


    private fun initCoverPage() {
        var result: Int = COVERPAGE_NOT_FOUND
        for (i in references.indices) {
            val guideReference = references.get(i)
            if (guideReference.type == GuideReference.Companion.COVER) {
                result = i
                break
            }
        }
        coverPageIndex = result
    }

    var coverPage: Resource?
        /**
         * The coverpage of the book.
         * 
         * @return The coverpage of the book.
         */
        get() {
            val guideReference = this.coverReference
            if (guideReference == null) {
                return null
            }
            return guideReference.resolveResource()
        }
        set(coverPage) {
            val coverpageGuideReference = GuideReference(
                coverPage,
                GuideReference.Companion.COVER, DEFAULT_COVER_TITLE
            )
            setCoverReference(coverpageGuideReference)
        }

    fun addReference(reference: GuideReference?): ResourceReference? {
        this.references.add(reference!!)
        uncheckCoverPage()
        return reference
    }

    /**
     * A list of all GuideReferences that have the given
     * referenceTypeName (ignoring case).
     * 
     * @param referenceTypeName referenceTypeName
     * @return A list of all GuideReferences that have the given
     * referenceTypeName (ignoring case).
     */
    fun getGuideReferencesByType(
        referenceTypeName: String
    ): MutableList<GuideReference?> {
        val result: MutableList<GuideReference?> = ArrayList<GuideReference?>()
        for (guideReference in references) {
            if (referenceTypeName.equals(guideReference.type, ignoreCase = true)) {
                result.add(guideReference)
            }
        }
        return result
    }

    companion object {
        /**
         * 
         */
        private val serialVersionUID = -6256645339915751189L

        val DEFAULT_COVER_TITLE: String = GuideReference.Companion.COVER

        private val COVERPAGE_NOT_FOUND = -1
        private val COVERPAGE_UNITIALIZED = -2
    }
}
