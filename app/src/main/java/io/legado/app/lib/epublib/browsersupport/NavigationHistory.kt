package io.legado.app.lib.epublib.browsersupport

import io.legado.app.lib.epublib.domain.EpubBook
import io.legado.app.lib.epublib.domain.Resource

/**
 * A history of the user's locations with the epub.
 * 
 * @author paul.siegmann
 */
class NavigationHistory(private val navigator: Navigator) : NavigationEventListener {
    private class Location(@set:Suppress("unused") var href: String?)

    private var lastUpdateTime: Long = 0
    private var locations: MutableList<Location?> = ArrayList<Location?>()
    var currentPos: Int = -1
        private set
    var currentSize: Int = 0
        private set
    var maxHistorySize: Int = DEFAULT_MAX_HISTORY_SIZE

    /**
     * If the time between a navigation event is less than the historyWaitTime
     * then the new location is not added to the history.
     * 
     * 
     * When a user is rapidly viewing many pages using the slider we do not
     * want all of them to be added to the history.
     * 
     * @return the time we wait before adding the page to the history
     */
    var historyWaitTime: Long = DEFAULT_HISTORY_WAIT_TIME

    init {
        navigator.addNavigationEventListener(this)
        initBook(navigator.getBook())
    }


    fun initBook(book: EpubBook?) {
        if (book == null) {
            return
        }
        locations = ArrayList<Location?>()
        currentPos = -1
        currentSize = 0
        if (navigator.getCurrentResource() != null) {
            addLocation(navigator.getCurrentResource()?.getHref())
        }
    }

    fun addLocation(resource: Resource?) {
        if (resource == null) {
            return
        }
        addLocation(resource.getHref())
    }

    /**
     * Adds the location after the current position.
     * If the currentposition is not the end of the list then the elements
     * between the current element and the end of the list will be discarded.
     * 
     * 
     * Does nothing if the new location matches the current location.
     * <br></br>
     * If this nr of locations becomes larger then the historySize then the
     * first item(s) will be removed.
     * v
     * 
     * @param location d
     */
    private fun addLocation(location: Location) {
        // do nothing if the new location matches the current location
        if (!(locations.isEmpty()) &&
            location.href == locations.get(currentPos)!!.href
        ) {
            return
        }
        currentPos++
        if (currentPos != currentSize) {
            locations.set(currentPos, location)
        } else {
            locations.add(location)
            checkHistorySize()
        }
        currentSize = currentPos + 1
    }

    /**
     * Removes all elements that are too much for the maxHistorySize
     * out of the history.
     */
    private fun checkHistorySize() {
        while (locations.size > maxHistorySize) {
            locations.removeAt(0)
            currentSize--
            currentPos--
        }
    }

    fun addLocation(href: String?) {
        addLocation(Location(href))
    }

    private fun getLocationHref(pos: Int): String? {
        if (pos < 0 || pos >= locations.size) {
            return null
        }
        return locations.get(currentPos)!!.href
    }

    /**
     * Moves the current positions delta positions.
     * 
     * 
     * move(-1) to go one position back in history.<br></br>
     * move(1) to go one position forward.<br></br>发
     * 
     * @param delta f
     * @return Whether we actually moved. If the requested value is illegal
     * it will return false, true otherwise.
     */
    fun move(delta: Int): Boolean {
        if (((currentPos + delta) < 0)
            || ((currentPos + delta) >= currentSize)
        ) {
            return false
        }
        currentPos += delta
        navigator.gotoResource(getLocationHref(currentPos), this)
        return true
    }


    /**
     * If this is not the source of the navigationEvent then the addLocation
     * will be called with the href of the currentResource in the navigationEvent.
     */
    override fun navigationPerformed(navigationEvent: NavigationEvent?) {
        if (navigationEvent == null) return
        if (this === navigationEvent.getSource()) {
            return
        }
        if (navigationEvent.currentResource == null) {
            return
        }

        if ((System.currentTimeMillis() - this.lastUpdateTime) > historyWaitTime) {
            addLocation(navigationEvent.getOldResource())
            addLocation(navigationEvent.currentResource?.getHref())
        }
        lastUpdateTime = System.currentTimeMillis()
    }

    val currentHref: String?
        get() {
            if (currentPos < 0 || currentPos >= locations.size) {
                return null
            }
            return locations.get(currentPos)!!.href
        }

    companion object {
        const val DEFAULT_MAX_HISTORY_SIZE: Int = 1000
        private const val DEFAULT_HISTORY_WAIT_TIME: Long = 1000
    }
}
