package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.epub.PackageDocumentBase
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A Date used by the book's metadata.
 *
 *
 * Examples: creation-date, modification-date, etc
 *
 * @author paul
 */
class Date @JvmOverloads constructor(var value: String, var event: Event? = null) :
    Serializable {
    enum class Event(private val value: String) {
        PUBLICATION("publication"),
        MODIFICATION("modification"),
        CREATION("creation");

        override fun toString(): String {
            return value
        }

        companion object {
            fun fromValue(v: String?): Event? {
                for (c in entries) {
                    if (c.value == v) {
                        return c
                    }
                }
                return null
            }
        }
    }

    @JvmOverloads
    constructor(date: java.util.Date = java.util.Date(), event: Event? = Event.CREATION) : this(
        SimpleDateFormat(PackageDocumentBase.dateFormat, Locale.US).format(date),
        event
    )

    constructor(date: java.util.Date, event: String?) : this(
        SimpleDateFormat(PackageDocumentBase.dateFormat, Locale.US).format(date),
        Event.fromValue(event)
    )

    constructor(dateString: String, event: String?) : this(
        dateString,
        Event.fromValue(event)
    )

    override fun toString(): String {
        if (event == null) {
            return this.value
        }
        return "" + event + ":" + this.value
    }

    companion object {
        private const val serialVersionUID = 7533866830395120136L
    }
}
