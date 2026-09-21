// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
  ******************************************************************************
  Copyright (C) 2005-2016, International Business Machines Corporation and    *
  others. All Rights Reserved.                                                *
  ******************************************************************************
 */
package io.legado.app.lib.icu4j

import io.legado.app.lib.icu4j.CharsetRecog_2022.CharsetRecog_2022CN
import io.legado.app.lib.icu4j.CharsetRecog_2022.CharsetRecog_2022JP
import io.legado.app.lib.icu4j.CharsetRecog_2022.CharsetRecog_2022KR
import io.legado.app.lib.icu4j.CharsetRecog_Unicode.CharsetRecog_UTF_16_BE
import io.legado.app.lib.icu4j.CharsetRecog_Unicode.CharsetRecog_UTF_16_LE
import io.legado.app.lib.icu4j.CharsetRecog_Unicode.CharsetRecog_UTF_32_BE
import io.legado.app.lib.icu4j.CharsetRecog_Unicode.CharsetRecog_UTF_32_LE
import io.legado.app.lib.icu4j.CharsetRecog_mbcs.CharsetRecog_big5
import io.legado.app.lib.icu4j.CharsetRecog_mbcs.CharsetRecog_euc.CharsetRecog_euc_jp
import io.legado.app.lib.icu4j.CharsetRecog_mbcs.CharsetRecog_euc.CharsetRecog_euc_kr
import io.legado.app.lib.icu4j.CharsetRecog_mbcs.CharsetRecog_gb_18030
import io.legado.app.lib.icu4j.CharsetRecog_mbcs.CharsetRecog_sjis
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_1
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_2
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_5_ru
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_6_ar
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_7_el
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_8_I_he
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_8_he
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_8859_9_tr
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_IBM420_ar_ltr
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_IBM420_ar_rtl
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_IBM424_he_ltr
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_IBM424_he_rtl
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_KOI8_R
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_windows_1251
import io.legado.app.lib.icu4j.CharsetRecog_sbcs.CharsetRecog_windows_1256
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.Arrays
import java.util.Collections

/**
 * `CharsetDetector` provides a facility for detecting the
 * charset or encoding of character data in an unknown format.
 * The input data can either be from an input stream or an array of bytes.
 * The result of the detection operation is a list of possibly matching
 * charsets, or, for simple use, you can just ask for a Java Reader that
 * will will work over the input data.
 * 
 * 
 * Character set detection is at best an imprecise operation.  The detection
 * process will attempt to identify the charset that best matches the characteristics
 * of the byte data, but the process is partly statistical in nature, and
 * the results can not be guaranteed to always be correct.
 * 
 * 
 * For best accuracy in charset detection, the input data should be primarily
 * in a single language, and a minimum of a few hundred bytes worth of plain text
 * in the language are needed.  The detection process will attempt to
 * ignore html or xml style markup that could otherwise obscure the content.
 * 
 * 
 * 
 * @stable ICU 3.4
 */
@Suppress("unused")
class CharsetDetector  //   Question: Should we have getters corresponding to the setters for input text
//   and declared encoding?
//   A thought: If we were to create our own type of Java Reader, we could defer
//   figuring out an actual charset for data that starts out with too much English
//   only ASCII until the user actually read through to something that didn't look
//   like 7 bit English.  If  nothing else ever appeared, we would never need to
//   actually choose the "real" charset.  All assuming that the application just
//   wants the data, and doesn't care about a char set name.
/**
 * Constructor
 * 
 * @stable ICU 3.4
 */
{
    /**
     * Set the declared encoding for charset detection.
     * The declared encoding of an input text is an encoding obtained
     * from an http header or xml declaration or similar source that
     * can be provided as additional information to the charset detector.
     * A match between a declared encoding and a possible detected encoding
     * will raise the quality of that detected encoding by a small delta,
     * and will also appear as a "reason" for the match.
     * 
     * 
     * A declared encoding that is incompatible with the input data being
     * analyzed will not be added to the list of possible encodings.
     * 
     * @param encoding The declared encoding
     * @stable ICU 3.4
     */
    fun setDeclaredEncoding(encoding: String?): CharsetDetector {
        fDeclaredEncoding = encoding
        return this
    }

    /**
     * Set the input text (byte) data whose charset is to be detected.
     * 
     * @param in the input text of unknown encoding
     * @return This CharsetDetector
     * @stable ICU 3.4
     */
    fun setText(`in`: ByteArray): CharsetDetector {
        fRawInput = `in`
        fRawLength = `in`.size

        return this
    }

    /**
     * Set the input text (byte) data whose charset is to be detected.
     * 
     * 
     * The input stream that supplies the character data must have markSupported()
     * == true; the charset detection process will read a small amount of data,
     * then return the stream to its original position via
     * the InputStream.reset() operation.  The exact amount that will
     * be read depends on the characteristics of the data itself.
     * 
     * @param in the input text of unknown encoding
     * @return This CharsetDetector
     * @stable ICU 3.4
     */
    @Throws(IOException::class)
    fun setText(`in`: InputStream): CharsetDetector {
        fInputStream = `in`
        fInputStream!!.mark(kBufSize)
        fRawInput = ByteArray(kBufSize) // Always make a new buffer because the
        //   previous one may have come from the caller,
        //   in which case we can't touch it.
        fRawLength = 0
        var remainingLength: Int = kBufSize
        while (remainingLength > 0) {
            // read() may give data in smallish chunks, esp. for remote sources.  Hence, this loop.
            val bytesRead = fInputStream!!.read(fRawInput, fRawLength, remainingLength)
            if (bytesRead <= 0) {
                break
            }
            fRawLength += bytesRead
            remainingLength -= bytesRead
        }
        fInputStream!!.reset()

        return this
    }


    /**
     * Return the charset that best matches the supplied input data.
     * 
     * 
     * Note though, that because the detection
     * only looks at the start of the input data,
     * there is a possibility that the returned charset will fail to handle
     * the full set of input data.
     * p>
     * aise an exception if
     * 
     *  * no charset appears to match the data.
     *  * no input text has been provided
     * 
     * 
     * @return a CharsetMatch object representing the best matching charset, or
     * `null` if there are no matches.
     * @stable ICU 3.4
     */
    fun detect(): CharsetMatch? {
//   TODO:  A better implementation would be to copy the detect loop from
//          detectAll(), and cut it short as soon as a match with a high confidence
//          is found.  This is something to be done later, after things are otherwise
//          working.
        val matches = detectAll()

        if (matches.size == 0) {
            return null
        }

        return matches[0]
    }

    /**
     * Return an array of all charsets that appear to be plausible
     * matches with the input data.  The array is ordered with the
     * best quality match first.
     * 
     * 
     * aise an exception if
     * 
     *  * no charsets appear to match the input data.
     *  * no input text has been provided
     * 
     * 
     * @return An array of CharsetMatch objects representing possibly matching charsets.
     * @stable ICU 3.4
     */
    fun detectAll(): Array<CharsetMatch> {
        // matches 元素恒非空（match==null 时不 add），用 ArrayList<CharsetMatch> 才能让
        // Collections.sort 接受（Kotlin 中 MutableList<CharsetMatch?> 与 Java List<T> 不兼容）
        val matches = ArrayList<CharsetMatch>()

        MungeInput() // Strip html markup, collect byte stats.

        //  Iterate over all possible charsets, remember all that
        //    give a match quality > 0.
        for (i in ALL_CS_RECOGNIZERS.indices) {
            val rcinfo: CSRecognizerInfo = ALL_CS_RECOGNIZERS.get(i)
            val active =
                if (fEnabledRecognizers != null) fEnabledRecognizers!![i] else rcinfo.isDefaultEnabled
            if (active) {
                val m = rcinfo.recognizer.match(this)
                if (m != null) {
                    matches.add(m)
                }
            }
        }
        Collections.sort(matches) // CharsetMatch compares on confidence
        Collections.reverse(matches) //  Put best match first.
        // 元素恒非空（match==null 时不 add），返回 Array<CharsetMatch> 更准确
        return matches.toTypedArray()
    }


    /**
     * Autodetect the charset of an inputStream, and return a Java Reader
     * to access the converted input data.
     * 
     * 
     * This is a convenience method that is equivalent to
     * `this.setDeclaredEncoding(declaredEncoding).setText(in).detect().getReader();`
     * 
     * 
     * For the input stream that supplies the character data, markSupported()
     * must be true; the  charset detection will read a small amount of data,
     * then return the stream to its original position via
     * the InputStream.reset() operation.  The exact amount that will
     * be read depends on the characteristics of the data itself.
     * 
     * 
     * Raise an exception if no charsets appear to match the input data.
     * 
     * @param in               The source of the byte data in the unknown charset.
     * @param declaredEncoding A declared encoding for the data, if available,
     * or null or an empty string if none is available.
     * @stable ICU 3.4
     */
    fun getReader(`in`: InputStream, declaredEncoding: String?): Reader? {
        fDeclaredEncoding = declaredEncoding

        try {
            setText(`in`)

            val match = detect()

            if (match == null) {
                return null
            }

            return match.reader
        } catch (e: IOException) {
            return null
        }
    }

    /**
     * Autodetect the charset of an inputStream, and return a String
     * containing the converted input data.
     * 
     * 
     * This is a convenience method that is equivalent to
     * `this.setDeclaredEncoding(declaredEncoding).setText(in).detect().getString();`
     * 
     * 
     * Raise an exception if no charsets appear to match the input data.
     * 
     * @param in               The source of the byte data in the unknown charset.
     * @param declaredEncoding A declared encoding for the data, if available,
     * or null or an empty string if none is available.
     * @stable ICU 3.4
     */
    fun getString(`in`: ByteArray, declaredEncoding: String?): String? {
        fDeclaredEncoding = declaredEncoding

        try {
            setText(`in`)

            val match = detect()

            if (match == null) {
                return null
            }

            return match.getString(-1)
        } catch (e: IOException) {
            return null
        }
    }


    /**
     * Test whether or not input filtering is enabled.
     * 
     * @return `true` if input text will be filtered.
     * @stable ICU 3.4
     * @see .enableInputFilter
     */
    fun inputFilterEnabled(): Boolean {
        return fStripTags
    }

    /**
     * Enable filtering of input text. If filtering is enabled,
     * text within angle brackets ("&lt;" and "&gt;") will be removed
     * before detection.
     * 
     * @param filter `true` to enable input text filtering.
     * @return The previous setting.
     * @stable ICU 3.4
     */
    fun enableInputFilter(filter: Boolean): Boolean {
        val previous = fStripTags

        fStripTags = filter

        return previous
    }

    /*
     *  MungeInput - after getting a set of raw input data to be analyzed, preprocess
     *               it by removing what appears to be html markup.
     */
    private fun MungeInput() {
        var srci: Int
        var dsti = 0
        var b: Byte
        var inMarkup = false
        var openTags = 0
        var badTags = 0

        //
        //  html / xml markup stripping.
        //     quick and dirty, not 100% accurate, but hopefully good enough, statistically.
        //     discard everything within < brackets >
        //     Count how many total '<' and illegal (nested) '<' occur, so we can make some
        //     guess as to whether the input was actually marked up at all.
        if (fStripTags) {
            srci = 0
            while (srci < fRawLength && dsti < fInputBytes.size) {
                b = fRawInput[srci]
                if (b == '<'.code.toByte()) {
                    if (inMarkup) {
                        badTags++
                    }
                    inMarkup = true
                    openTags++
                }

                if (!inMarkup) {
                    fInputBytes[dsti++] = b
                }

                if (b == '>'.code.toByte()) {
                    inMarkup = false
                }
                srci++
            }

            fInputLen = dsti
        }

        //
        //  If it looks like this input wasn't marked up, or if it looks like it's
        //    essentially nothing but markup abandon the markup stripping.
        //    Detection will have to work on the unstripped input.
        //
        if (openTags < 5 || openTags / 5 < badTags ||
            (fInputLen < 100 && fRawLength > 600)
        ) {
            var limit = fRawLength

            if (limit > kBufSize) {
                limit = kBufSize
            }

            srci = 0
            while (srci < limit) {
                fInputBytes[srci] = fRawInput[srci]
                srci++
            }
            fInputLen = srci
        }

        //
        // Tally up the byte occurence statistics.
        //   These are available for use by the various detectors.
        //
        Arrays.fill(fByteStats, 0.toShort())
        srci = 0
        while (srci < fInputLen) {
            val `val` = fInputBytes[srci].toInt() and 0x00ff
            fByteStats[`val`]++
            srci++
        }

        fC1Bytes = false
        var i = 0x80
        while (i <= 0x9F) {
            if (fByteStats[i].toInt() != 0) {
                fC1Bytes = true
                break
            }
            i += 1
        }
    }

    /*
     *  The following items are accessed by individual CharsetRecongizers during
     *     the recognition process
     *
     */
    val fInputBytes: ByteArray =  // The text to be checked.  Markup will have been
        ByteArray(kBufSize) //   removed if appropriate.

    var fInputLen: Int = 0 // Length of the byte data in fInputBytes.

    val fByteStats: ShortArray =  // byte frequency statistics for the input text.
        ShortArray(256) //   Value is percent, not absolute.

    //   Value is rounded up, so zero really means zero occurences.
    var fC1Bytes: Boolean =  // True if any bytes in the range 0x80 - 0x9F are in the input;
        false

    var fDeclaredEncoding: String? = null


    lateinit var fRawInput: ByteArray // Original, untouched input bytes.

    //  If user gave us a byte array, this is it.
    //  If user gave us a stream, it's read to a
    //  buffer here.
    var fRawLength: Int = 0 // Length of data in fRawInput array.

    var fInputStream: InputStream? = null // User's input stream, or null if the user

    //   gave us a byte array.
    //
    //  Stuff private to CharsetDetector
    //
    private var fStripTags =  // If true, setText() will strip tags from input text.
        false

    private var fEnabledRecognizers: BooleanArray? = null // If not null, active set of charset recognizers had

    // been changed from the default. The array index is
    // corresponding to ALL_RECOGNIZER. See setDetectableCharset().
    private class CSRecognizerInfo(val recognizer: CharsetRecognizer, val isDefaultEnabled: Boolean)

    @get:Deprecated("This API is ICU internal only.")
    val detectableCharsets: Array<String?>
        /**
         * Get the names of charsets that can be recognized by this CharsetDetector instance.
         * 
         * @return an array of the names of charsets that can be recognized by this CharsetDetector
         * instance.
         * @internal
         */
        get() {
            val csnames: MutableList<String?> =
                ArrayList<String?>(ALL_CS_RECOGNIZERS.size)
            for (i in ALL_CS_RECOGNIZERS.indices) {
                val rcinfo: CSRecognizerInfo =
                    ALL_CS_RECOGNIZERS.get(i)
                val active =
                    if (fEnabledRecognizers == null) rcinfo.isDefaultEnabled else fEnabledRecognizers!![i]
                if (active) {
                    csnames.add(rcinfo.recognizer.getName())
                }
            }
            return csnames.toTypedArray<String?>()
        }

    /**
     * Enable or disable individual charset encoding.
     * A name of charset encoding must be included in the names returned by
     * [.getAllDetectableCharsets].
     * 
     * @param encoding the name of charset encoding.
     * @param enabled  `true` to enable, or `false` to disable the
     * charset encoding.
     * @return A reference to this `CharsetDetector`.
     * @throws IllegalArgumentException when the name of charset encoding is
     * not supported.
     * @internal
     */
    @Deprecated("This API is ICU internal only.")
    fun setDetectableCharset(encoding: String?, enabled: Boolean): CharsetDetector {
        var modIdx = -1
        var isDefaultVal = false
        for (i in ALL_CS_RECOGNIZERS.indices) {
            val csrinfo: CSRecognizerInfo = ALL_CS_RECOGNIZERS.get(i)
            if (csrinfo.recognizer.getName() == encoding) {
                modIdx = i
                isDefaultVal = (csrinfo.isDefaultEnabled == enabled)
                break
            }
        }
        require(modIdx >= 0) { "Invalid encoding: " + "\"" + encoding + "\"" }

        if (fEnabledRecognizers == null && !isDefaultVal) {
            // Create an array storing the non default setting
            fEnabledRecognizers = BooleanArray(ALL_CS_RECOGNIZERS.size)

            // Initialize the array with default info
            for (i in ALL_CS_RECOGNIZERS.indices) {
                fEnabledRecognizers!![i] = ALL_CS_RECOGNIZERS.get(i).isDefaultEnabled
            }
        }

        if (fEnabledRecognizers != null) {
            fEnabledRecognizers!![modIdx] = enabled
        }

        return this
    }

    companion object {
        private const val kBufSize = 8000

        val allDetectableCharsets: Array<String?>
            /**
             * Get the names of all charsets supported by `CharsetDetector` class.
             * 
             * 
             * **Note:** Multiple different charset encodings in a same family may use
             * a single shared name in this implementation. For example, this method returns
             * an array including "ISO-8859-1" (ISO Latin 1), but not including "windows-1252"
             * (Windows Latin 1). However, actual detection result could be "windows-1252"
             * when the input data matches Latin 1 code points with any points only available
             * in "windows-1252".
             * 
             * @return an array of the names of all charsets supported by
             * `CharsetDetector` class.
             * @stable ICU 3.4
             */
            get() {
                val allCharsetNames =
                    arrayOfNulls<String>(ALL_CS_RECOGNIZERS.size)
                for (i in allCharsetNames.indices) {
                    allCharsetNames[i] =
                        ALL_CS_RECOGNIZERS.get(i).recognizer.getName()
                }
                return allCharsetNames
            }

        /*
    * List of recognizers for all charsets known to the implementation.
    */
        private val ALL_CS_RECOGNIZERS: MutableList<CSRecognizerInfo>

        init {
            val list: MutableList<CSRecognizerInfo?> = ArrayList<CSRecognizerInfo?>()

            list.add(CSRecognizerInfo(CharsetRecog_UTF8(), true))
            list.add(CSRecognizerInfo(CharsetRecog_UTF_16_BE(), true))
            list.add(CSRecognizerInfo(CharsetRecog_UTF_16_LE(), true))
            list.add(CSRecognizerInfo(CharsetRecog_UTF_32_BE(), true))
            list.add(CSRecognizerInfo(CharsetRecog_UTF_32_LE(), true))

            list.add(CSRecognizerInfo(CharsetRecog_sjis(), true))
            list.add(CSRecognizerInfo(CharsetRecog_2022JP(), true))
            list.add(CSRecognizerInfo(CharsetRecog_2022CN(), true))
            list.add(CSRecognizerInfo(CharsetRecog_2022KR(), true))
            list.add(CSRecognizerInfo(CharsetRecog_gb_18030(), true))
            list.add(CSRecognizerInfo(CharsetRecog_euc_jp(), true))
            list.add(CSRecognizerInfo(CharsetRecog_euc_kr(), true))
            list.add(CSRecognizerInfo(CharsetRecog_big5(), true))

            list.add(CSRecognizerInfo(CharsetRecog_8859_1(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_2(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_5_ru(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_6_ar(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_7_el(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_8_I_he(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_8_he(), true))
            list.add(CSRecognizerInfo(CharsetRecog_windows_1251(), true))
            list.add(CSRecognizerInfo(CharsetRecog_windows_1256(), true))
            list.add(CSRecognizerInfo(CharsetRecog_KOI8_R(), true))
            list.add(CSRecognizerInfo(CharsetRecog_8859_9_tr(), true))

            // IBM 420/424 recognizers are disabled by default
            list.add(CSRecognizerInfo(CharsetRecog_IBM424_he_rtl(), false))
            list.add(CSRecognizerInfo(CharsetRecog_IBM424_he_ltr(), false))
            list.add(CSRecognizerInfo(CharsetRecog_IBM420_ar_rtl(), false))
            list.add(CSRecognizerInfo(CharsetRecog_IBM420_ar_ltr(), false))

            ALL_CS_RECOGNIZERS = Collections.unmodifiableList<CSRecognizerInfo>(list)
        }
    }
}
