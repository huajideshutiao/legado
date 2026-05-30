package io.legado.app.lib.epublib.domain

import io.legado.app.lib.epublib.util.StringUtil
import java.io.Serializable
import java.util.UUID

/**
 * A Book's identifier.
 * 
 * 
 * Defaults to a random UUID and scheme "UUID"
 * 
 * @author paul
 */
class Identifier
/**
 * Creates an Identifier with as value a random UUID and scheme "UUID"
 */ @JvmOverloads constructor(
    var scheme: String = Scheme.Companion.UUID,
    var value: String = UUID.randomUUID().toString()
) : Serializable {
    @Suppress("unused")
    interface Scheme {
        companion object {
            const val UUID: String = "UUID"
            const val ISBN: String = "ISBN"
            const val URL: String = "URL"
            const val URI: String = "URI"
        }
    }

    /**
     * This bookId property allows the book creator to add multiple ids and
     * tell the epubwriter which one to write out as the bookId.
     * 
     * 
     * The Dublin Core metadata spec allows multiple identifiers for a Book.
     * The epub spec requires exactly one identifier to be marked as the book id.
     * 
     * @return whether this is the unique book id.
     */
    var isBookId: Boolean = false


    override fun hashCode(): Int {
        return StringUtil.defaultIfNull(scheme).hashCode() xor StringUtil
            .defaultIfNull(value).hashCode()
    }

    override fun equals(otherIdentifier: Any?): Boolean {
        if (otherIdentifier !is Identifier) {
            return false
        }
        return StringUtil.equals(scheme, otherIdentifier.scheme)
            && StringUtil.equals(value, otherIdentifier.value)
    }

    override fun toString(): String {
        if (StringUtil.isBlank(scheme)) {
            return "" + value
        }
        return "" + scheme + ":" + value
    }

    companion object {
        private const val serialVersionUID = 955949951416391810L

        /**
         * The first identifier for which the bookId is true is made the
         * bookId identifier.
         * 
         * 
         * If no identifier has bookId == true then the first bookId identifier
         * is written as the primary.
         * 
         * @param identifiers i
         * @return The first identifier for which the bookId is true is made
         * the bookId identifier.
         */
        fun getBookIdIdentifier(identifiers: MutableList<Identifier>?): Identifier? {
            if (identifiers == null || identifiers.isEmpty()) {
                return null
            }

            var result: Identifier? = null
            for (identifier in identifiers) {
                if (identifier.isBookId) {
                    result = identifier
                    break
                }
            }

            if (result == null) {
                result = identifiers.get(0)
            }

            return result
        }
    }
}
