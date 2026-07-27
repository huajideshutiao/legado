// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 * ******************************************************************************
 * Copyright (C) 2005-2016, International Business Machines Corporation and    *
 * others. All Rights Reserved.                                                *
 * ******************************************************************************
 */
package io.legado.app.lib.icu4j

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import kotlin.math.min

/**
 * This class represents a charset that has been identified by a CharsetDetector
 * as a possible encoding for a set of input data.  From an instance of this
 * class, you can ask for a confidence level in the charset identification,
 * or for Java Reader or String to access the original byte data in Unicode form.
 * 
 * 
 * Instances of this class are created only by CharsetDetectors.
 * 
 * 
 * Note:  this class has a natural ordering that is inconsistent with equals.
 * The natural ordering is based on the match confidence value.
 * 
 * @stable ICU 3.4
 */
@Suppress("unused")
class CharsetMatch : Comparable<CharsetMatch> {
    val reader: Reader?
        /**
         * Create a java.io.Reader for reading the Unicode character data corresponding
         * to the original byte data supplied to the Charset detect operation.
         * 
         * 
         * CAUTION:  if the source of the byte data was an InputStream, a Reader
         * can be created for only one matching char set using this method.  If more
         * than one charset needs to be tried, the caller will need to reset
         * the InputStream and create InputStreamReaders itself, based on the charset name.
         * 
         * @return the Reader for the Unicode character data.
         * @stable ICU 3.4
         */
        get() {
            var inputStream = fInputStream

            if (inputStream == null) {
                inputStream = ByteArrayInputStream(fRawInput, 0, fRawLength)
            }

            try {
                inputStream.reset()
                return InputStreamReader(inputStream, this.name)
            } catch (e: IOException) {
                return null
            }
        }

    @get:Throws(IOException::class)
    val string: String
        /**
         * Create a Java String from Unicode character data corresponding
         * to the original byte data supplied to the Charset detect operation.
         * 
         * @return a String created from the converted input data.
         * @stable ICU 3.4
         */
        get() = getString(-1)

    /**
     * Create a Java String from Unicode character data corresponding
     * to the original byte data supplied to the Charset detect operation.
     * The length of the returned string is limited to the specified size;
     * the string will be trunctated to this length if necessary.  A limit value of
     * zero or less is ignored, and treated as no limit.
     * 
     * @param maxLength The maximium length of the String to be created when the
     * source of the data is an input stream, or -1 for
     * unlimited length.
     * @return a String created from the converted input data.
     * @stable ICU 3.4
     */
    @Throws(IOException::class)
    fun getString(maxLength: Int): String {
        val result: String
        if (fInputStream != null) {
            val sb = StringBuilder()
            val buffer = CharArray(1024)
            val reader = this.reader
            var max = if (maxLength < 0) Int.Companion.MAX_VALUE else maxLength
            var bytesRead: Int

            while ((reader!!.read(buffer, 0, min(max, 1024)).also { bytesRead = it }) >= 0) {
                sb.append(buffer, 0, bytesRead)
                max -= bytesRead
            }

            reader.close()

            return sb.toString()
        } else {
            var name = this.name
            /*
             * getName() may return a name with a suffix 'rtl' or 'ltr'. This cannot
             * be used to open a charset (e.g. IBM424_rtl). The ending '_rtl' or 'ltr'
             * should be stripped off before creating the string.
             */
            val startSuffix =
                if (!name.contains("_rtl")) name.indexOf("_ltr") else name.indexOf("_rtl")
            if (startSuffix > 0) {
                name = name.substring(0, startSuffix)
            }
            result = kotlin.text.String(fRawInput!!, charset(name))
        }
        return result
    }

    /**
     * Compare to other CharsetMatch objects.
     * Comparison is based on the match confidence value, which
     * allows CharsetDetector.detectAll() to order its results.
     * 
     * @param other the CharsetMatch object to compare against.
     * @return a negative integer, zero, or a positive integer as the
     * confidence level of this CharsetMatch
     * is less than, equal to, or greater than that of
     * the argument.
     * @throws ClassCastException if the argument is not a CharsetMatch.
     * @stable ICU 4.4
     */
    override fun compareTo(other: CharsetMatch): Int {
        var compareResult = 0
        if (this.confidence > other.confidence) {
            compareResult = 1
        } else if (this.confidence < other.confidence) {
            compareResult = -1
        }
        return compareResult
    }

    /*
     *  Constructor.  Implementation internal
     */
    internal constructor(det: CharsetDetector, rec: CharsetRecognizer, conf: Int) {
        this.confidence = conf

        // The references to the original application input data must be copied out
        //   of the charset recognizer to here, in case the application resets the
        //   recognizer before using this CharsetMatch.
        if (det.fInputStream == null) {
            // We only want the existing input byte data if it came straight from the user,
            //   not if is just the head of a stream.
            fRawInput = det.fRawInput
            fRawLength = det.fRawLength
        }
        fInputStream = det.fInputStream
        this.name = rec.getName()
        this.language = rec.getLanguage()
    }

    /*
     *  Constructor.  Implementation internal
     */
    internal constructor(
        det: CharsetDetector,
        rec: CharsetRecognizer?,
        conf: Int,
        csName: String,
        lang: String?
    ) {
        this.confidence = conf

        // The references to the original application input data must be copied out
        //   of the charset recognizer to here, in case the application resets the
        //   recognizer before using this CharsetMatch.
        if (det.fInputStream == null) {
            // We only want the existing input byte data if it came straight from the user,
            //   not if is just the head of a stream.
            fRawInput = det.fRawInput
            fRawLength = det.fRawLength
        }
        fInputStream = det.fInputStream
        this.name = csName
        this.language = lang
    }


    /**
     * Get an indication of the confidence in the charset detected.
     * Confidence values range from 0-100, with larger numbers indicating
     * a better match of the input data to the characteristics of the
     * charset.
     * 
     * @return the confidence in the charset match
     * @stable ICU 3.4
     */
    //
    //   Private Data
    //
    val confidence: Int
    private var fRawInput: ByteArray? = null // Original, untouched input bytes.

    //  If user gave us a byte array, this is it.
    private var fRawLength = 0 // Length of data in fRawInput array.

    private val fInputStream: InputStream? // User's input stream, or null if the user

    /**
     * Get the name of the detected charset.
     * The name will be one that can be used with other APIs on the
     * platform that accept charset names.  It is the "Canonical name"
     * as defined by the class java.nio.charset.Charset; for
     * charsets that are registered with the IANA charset registry,
     * this is the MIME-preferred registerd name.
     * 
     * @return The name of the charset.
     * @stable ICU 3.4
     * @see java.nio.charset.Charset
     * 
     * @see InputStreamReader
     */
    //   gave us a byte array.
    val name: String // The name of the charset this CharsetMatch

    /**
     * Get the ISO code for the language of the detected charset.
     * 
     * @return The ISO code for the language or `null` if the language cannot be determined.
     * @stable ICU 3.4
     */
    //   represents.  Filled in by the recognizer.
    val language: String? // The language, if one was determined by
    //   the recognizer during the detect operation.
}
