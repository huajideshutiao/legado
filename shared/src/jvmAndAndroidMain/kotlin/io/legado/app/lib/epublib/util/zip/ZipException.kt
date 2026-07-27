package io.legado.app.lib.epublib.util.zip

import java.io.IOException

/**
 * Thrown during the creation or input of a zip file.
 * 
 * @author Jochen Hoenicke
 * @author Per Bothner
 * @status updated to 1.4
 */
class ZipException : IOException {
    /**
     * Create an exception without a message.
     */
    constructor()

    /**
     * Create an exception with a message.
     * 
     * @param msg the message
     */
    constructor(msg: String?) : super(msg)

    companion object {
        /**
         * Compatible with JDK 1.0+.
         */
        private const val serialVersionUID = 8000196834066748623L
    }
}
