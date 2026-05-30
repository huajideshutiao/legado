package io.legado.app.lib.epublib.browsersupport

import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Resource
import io.legado.app.lib.epublib.util.StringUtil
import java.util.EventObject

/**
 * Used to tell NavigationEventListener just what kind of navigation action
 * the user just did.
 * 
 * @author paul
 */
@Suppress("unused")
class NavigationEvent : EventObject {
    private var oldResource: Resource? = null
    var oldSpinePos: Int = 0
    private var navigator: Navigator? = null
    private var oldBook: EpubBook? = null

    /**
     * The previous position within the section.
     * 
     * @return The previous position within the section.
     */
    var oldSectionPos: Int = 0
        private set

    // package
    var oldFragmentId: String? = null

    constructor(source: Any?) : super(source)

    constructor(source: Any?, navigator: Navigator) : super(source) {
        this.navigator = navigator
        this.oldBook = navigator.getBook()
        this.oldFragmentId = navigator.currentFragmentId
        this.oldSectionPos = navigator.currentSectionPos
        this.oldResource = navigator.getCurrentResource()
        this.oldSpinePos = navigator.getCurrentSpinePos()
    }

    fun getNavigator(): Navigator {
        return navigator!!
    }

    fun getOldBook(): EpubBook? {
        return oldBook
    }

    // package
    fun setOldPagePos(oldPagePos: Int) {
        this.oldSectionPos = oldPagePos
    }

    val currentSectionPos: Int
        get() = navigator!!.currentSectionPos

    val currentSpinePos: Int
        get() = navigator!!.getCurrentSpinePos()

    val currentFragmentId: String?
        get() = navigator!!.currentFragmentId

    val isBookChanged: Boolean
        get() {
            if (oldBook == null) {
                return true
            }
            return oldBook !== navigator!!.getBook()
        }

    val isSpinePosChanged: Boolean
        get() = this.oldSpinePos != this.currentSpinePos

    val isFragmentChanged: Boolean
        get() = !StringUtil.equals(this.oldFragmentId, this.currentFragmentId)

    fun getOldResource(): Resource? {
        return oldResource
    }

    val currentResource: Resource?
        get() = navigator!!.getCurrentResource()

    fun setOldResource(oldResource: Resource?) {
        this.oldResource = oldResource
    }


    fun setNavigator(navigator: Navigator) {
        this.navigator = navigator
    }


    fun setOldBook(oldBook: EpubBook?) {
        this.oldBook = oldBook
    }

    val currentBook: EpubBook?
        get() = getNavigator().getBook()

    val isResourceChanged: Boolean
        get() = oldResource !== this.currentResource

    override fun toString(): String {
        return StringUtil.toString(
            "oldSectionPos", oldSectionPos,
            "oldResource", oldResource,
            "oldBook", oldBook,
            "oldFragmentId", oldFragmentId,
            "oldSpinePos", oldSpinePos,
            "currentPagePos", this.currentSectionPos,
            "currentResource", this.currentResource,
            "currentBook", this.currentBook,
            "currentFragmentId", this.currentFragmentId,
            "currentSpinePos", this.currentSpinePos
        )
    }

    val isSectionPosChanged: Boolean
        get() = oldSectionPos != this.currentSectionPos

    companion object {
        private val serialVersionUID = -6346750144308952762L
    }
}
