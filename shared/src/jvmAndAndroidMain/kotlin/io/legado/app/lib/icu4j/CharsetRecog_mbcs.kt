// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 ****************************************************************************
 * Copyright (C) 2005-2012, International Business Machines Corporation and *
 * others. All Rights Reserved.                                             *
 ****************************************************************************
 *
 */
package io.legado.app.lib.icu4j

import java.util.Arrays
import kotlin.math.ln
import kotlin.math.min

/**
 * CharsetRecognizer implemenation for Asian  - double or multi-byte - charsets.
 * Match is determined mostly by the input data adhering to the
 * encoding scheme for the charset, and, optionally,
 * frequency-of-occurence of characters.
 * 
 * 
 * Instances of this class are singletons, one per encoding
 * being recognized.  They are created in the main
 * CharsetDetector class and kept in the global list of available
 * encodings to be checked.  The specific encoding being recognized
 * is determined by subclass.
 */
internal abstract class CharsetRecog_mbcs : CharsetRecognizer() {
    /**
     * Get the IANA name of this charset.
     * 
     * @return the charset name.
     */
    abstract override fun getName(): String


    /**
     * Test the match of this charset with the input text data
     * which is obtained via the CharsetDetector object.
     * 
     * @param det The CharsetDetector, which contains the input text
     * to be checked for being in this charset.
     * @return Two values packed into one int  (Damn java, anyhow)
     * <br></br>
     * bits 0-7:  the match confidence, ranging from 0-100
     * <br></br>
     * bits 8-15: The match reason, an enum-like value.
     */
    fun match(det: CharsetDetector, commonChars: IntArray?): Int {
        @Suppress("unused") var singleByteCharCount = 0 //TODO Do we really need this?
        var doubleByteCharCount = 0
        var commonCharCount = 0
        var badCharCount = 0
        var totalCharCount = 0
        var confidence = 0
        val iter = iteratedChar()

        // 原 Java labeled block (detectBlock:) 在 Kotlin 中用 run { ... return@run ... } 模拟
        run {
            iter.reset()
            while (nextChar(iter, det)) {
                totalCharCount++
                if (iter.error) {
                    badCharCount++
                } else {
                    val cv = iter.charValue.toLong() and 0xFFFFFFFFL

                    if (cv <= 0xff) {
                        singleByteCharCount++
                    } else {
                        doubleByteCharCount++
                        if (commonChars != null) {
                            // NOTE: This assumes that there are no 4-byte common chars.
                            if (Arrays.binarySearch(commonChars, cv.toInt()) >= 0) {
                                commonCharCount++
                            }
                        }
                    }
                }
                if (badCharCount >= 2 && badCharCount * 5 >= doubleByteCharCount) {
                    // Bail out early if the byte data is not matching the encoding scheme.
                    return@run
                }
            }

            if (doubleByteCharCount <= 10 && badCharCount == 0) {
                // Not many multi-byte chars.
                if (doubleByteCharCount == 0 && totalCharCount < 10) {
                    // There weren't any multibyte sequences, and there was a low density of non-ASCII single bytes.
                    // We don't have enough data to have any confidence.
                    // Statistical analysis of single byte non-ASCII charcters would probably help here.
                } else {
                    //   ASCII or ISO file?  It's probably not our encoding,
                    //   but is not incompatible with our encoding, so don't give it a zero.
                    confidence = 10
                }

                return@run
            }

            //
            //  No match if there are too many characters that don't fit the encoding scheme.
            //    (should we have zero tolerance for these?)
            //
            if (doubleByteCharCount < 20 * badCharCount) {
                return@run
            }
            if (commonChars == null) {
                // We have no statistics on frequently occuring characters.
                //  Assess confidence purely on having a reasonable number of
                //  multi-byte characters (the more the better
                confidence = 30 + doubleByteCharCount - 20 * badCharCount
                if (confidence > 100) {
                    confidence = 100
                }
            } else {
                //
                // Frequency of occurence statistics exist.
                //
                val maxVal = ln((doubleByteCharCount.toFloat() / 4).toDouble())
                val scaleFactor = 90.0 / maxVal
                confidence = (ln((commonCharCount + 1).toDouble()) * scaleFactor + 10).toInt()
                confidence = min(confidence, 100)
            }
        } // end of detectBlock


        return confidence
    }

    // "Character"  iterated character class.
    //    Recognizers for specific mbcs encodings make their "characters" available
    //    by providing a nextChar() function that fills in an instance of iteratedChar
    //    with the next char from the input.
    //    The returned characters are not converted to Unicode, but remain as the raw
    //    bytes (concatenated into an int) from the codepage data.
    //
    //  For Asian charsets, use the raw input rather than the input that has been
    //   stripped of markup.  Detection only considers multi-byte chars, effectively
    //   stripping markup anyway, and double byte chars do occur in markup too.
    //
    internal class iteratedChar {
        var charValue: Int = 0 // 1-4 bytes from the raw input data
        var nextIndex: Int = 0
        var error: Boolean = false
        var done: Boolean = false

        fun reset() {
            charValue = 0
            nextIndex = 0
            error = false
            done = false
        }

        fun nextByte(det: CharsetDetector): Int {
            if (nextIndex >= det.fRawLength) {
                done = true
                return -1
            }
            return det.fRawInput[nextIndex++].toInt() and 0x00ff
        }
    }

    /**
     * Get the next character (however many bytes it is) from the input data
     * Subclasses for specific charset encodings must implement this function
     * to get characters according to the rules of their encoding scheme.
     * 
     * 
     * This function is not a method of class iteratedChar only because
     * that would require a lot of extra derived classes, which is awkward.
     * 
     * @param it  The iteratedChar "struct" into which the returned char is placed.
     * @param det The charset detector, which is needed to get at the input byte data
     * being iterated over.
     * @return True if a character was returned, false at end of input.
     */
    abstract fun nextChar(it: iteratedChar, det: CharsetDetector): Boolean


    /**
     * Shift-JIS charset recognizer.
     */
    internal class CharsetRecog_sjis : CharsetRecog_mbcs() {
        override fun nextChar(it: iteratedChar, det: CharsetDetector): Boolean {
            it.error = false
            val firstByte: Int
            it.charValue = it.nextByte(det)
            firstByte = it.charValue
            if (firstByte < 0) {
                return false
            }

            if (firstByte <= 0x7f || (firstByte > 0xa0 && firstByte <= 0xdf)) {
                return true
            }

            val secondByte = it.nextByte(det)
            if (secondByte < 0) {
                return false
            }
            it.charValue = (firstByte shl 8) or secondByte
            if (!((secondByte >= 0x40 && secondByte <= 0x7f) || (secondByte >= 0x80 && secondByte <= 0xff))) {
                // Illegal second byte value.
                it.error = true
            }
            return true
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val confidence = match(det, commonChars)
            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }

        override fun getName(): String {
            return "Shift_JIS"
        }

        override fun getLanguage(): String {
            return "ja"
        }


        companion object {
            val commonChars: IntArray =
            // TODO:  This set of data comes from the character frequency-
            //        of-occurence analysis tool.  The data needs to be moved
                //        into a resource and loaded from there.
                intArrayOf(
                    0x8140, 0x8141, 0x8142, 0x8145, 0x815b, 0x8169, 0x816a, 0x8175, 0x8176, 0x82a0,
                    0x82a2, 0x82a4, 0x82a9, 0x82aa, 0x82ab, 0x82ad, 0x82af, 0x82b1, 0x82b3, 0x82b5,
                    0x82b7, 0x82bd, 0x82be, 0x82c1, 0x82c4, 0x82c5, 0x82c6, 0x82c8, 0x82c9, 0x82cc,
                    0x82cd, 0x82dc, 0x82e0, 0x82e7, 0x82e8, 0x82e9, 0x82ea, 0x82f0, 0x82f1, 0x8341,
                    0x8343, 0x834e, 0x834f, 0x8358, 0x835e, 0x8362, 0x8367, 0x8375, 0x8376, 0x8389,
                    0x838a, 0x838b, 0x838d, 0x8393, 0x8e96, 0x93fa, 0x95aa
                )
        }
    }


    /**
     * Big5 charset recognizer.
     */
    internal class CharsetRecog_big5 : CharsetRecog_mbcs() {
        override fun nextChar(it: iteratedChar, det: CharsetDetector): Boolean {
            it.error = false
            val firstByte: Int
            it.charValue = it.nextByte(det)
            firstByte = it.charValue
            if (firstByte < 0) {
                return false
            }

            if (firstByte <= 0x7f || firstByte == 0xff) {
                // single byte character.
                return true
            }

            val secondByte = it.nextByte(det)
            if (secondByte < 0) {
                return false
            }
            it.charValue = (it.charValue shl 8) or secondByte

            if (secondByte < 0x40 || secondByte == 0x7f || secondByte == 0xff) {
                it.error = true
            }
            return true
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val confidence = match(det, commonChars)
            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }

        override fun getName(): String {
            return "Big5"
        }


        override fun getLanguage(): String {
            return "zh"
        }

        companion object {
            val commonChars: IntArray =
            // TODO:  This set of data comes from the character frequency-
            //        of-occurence analysis tool.  The data needs to be moved
                //        into a resource and loaded from there.
                intArrayOf(
                    0xa140, 0xa141, 0xa142, 0xa143, 0xa147, 0xa149, 0xa175, 0xa176, 0xa440, 0xa446,
                    0xa447, 0xa448, 0xa451, 0xa454, 0xa457, 0xa464, 0xa46a, 0xa46c, 0xa477, 0xa4a3,
                    0xa4a4, 0xa4a7, 0xa4c1, 0xa4ce, 0xa4d1, 0xa4df, 0xa4e8, 0xa4fd, 0xa540, 0xa548,
                    0xa558, 0xa569, 0xa5cd, 0xa5e7, 0xa657, 0xa661, 0xa662, 0xa668, 0xa670, 0xa6a8,
                    0xa6b3, 0xa6b9, 0xa6d3, 0xa6db, 0xa6e6, 0xa6f2, 0xa740, 0xa751, 0xa759, 0xa7da,
                    0xa8a3, 0xa8a5, 0xa8ad, 0xa8d1, 0xa8d3, 0xa8e4, 0xa8fc, 0xa9c0, 0xa9d2, 0xa9f3,
                    0xaa6b, 0xaaba, 0xaabe, 0xaacc, 0xaafc, 0xac47, 0xac4f, 0xacb0, 0xacd2, 0xad59,
                    0xaec9, 0xafe0, 0xb0ea, 0xb16f, 0xb2b3, 0xb2c4, 0xb36f, 0xb44c, 0xb44e, 0xb54c,
                    0xb5a5, 0xb5bd, 0xb5d0, 0xb5d8, 0xb671, 0xb7ed, 0xb867, 0xb944, 0xbad8, 0xbb44,
                    0xbba1, 0xbdd1, 0xc2c4, 0xc3b9, 0xc440, 0xc45f
                )
        }
    }


    /**
     * EUC charset recognizers.  One abstract class that provides the common function
     * for getting the next character according to the EUC encoding scheme,
     * and nested derived classes for EUC_KR, EUC_JP, EUC_CN.
     */
    internal abstract class CharsetRecog_euc : CharsetRecog_mbcs() {
        /*
                 *  (non-Javadoc)
                 *  Get the next character value for EUC based encodings.
                 *  Character "value" is simply the raw bytes that make up the character
                 *     packed into an int.
                 */
        override fun nextChar(it: iteratedChar, det: CharsetDetector): Boolean {
            it.error = false
            // 原 Java 局部变量 firstByte/secondByte/thirdByte 在 labeled block (buildChar:) 内赋值
            // Kotlin 中用 var 闭包捕获 + run { ... return@run ... } 替代 labeled block
            var firstByte: Int = 0
            var secondByte: Int = 0
            var thirdByte: Int = 0

            //int fourthByte = 0;
            run {
                it.charValue = it.nextByte(det)
                firstByte = it.charValue
                if (firstByte < 0) {
                    // Ran off the end of the input data
                    it.done = true
                    return@run
                }
                if (firstByte <= 0x8d) {
                    // single byte char
                    return@run
                }

                secondByte = it.nextByte(det)
                it.charValue = (it.charValue shl 8) or secondByte

                if (firstByte >= 0xA1 && firstByte <= 0xfe) {
                    // Two byte Char
                    if (secondByte < 0xa1) {
                        it.error = true
                    }
                    return@run
                }
                if (firstByte == 0x8e) {
                    // Code Set 2.
                    //   In EUC-JP, total char size is 2 bytes, only one byte of actual char value.
                    //   In EUC-TW, total char size is 4 bytes, three bytes contribute to char value.
                    // We don't know which we've got.
                    // Treat it like EUC-JP.  If the data really was EUC-TW, the following two
                    //   bytes will look like a well formed 2 byte char.
                    if (secondByte < 0xa1) {
                        it.error = true
                    }
                    return@run
                }
                if (firstByte == 0x8f) {
                    // Code set 3.
                    // Three byte total char size, two bytes of actual char value.
                    thirdByte = it.nextByte(det)
                    it.charValue = (it.charValue shl 8) or thirdByte
                    if (thirdByte < 0xa1) {
                        it.error = true
                    }
                }
            }

            return (!it.done)
        }

        /**
         * The charset recognize for EUC-JP.  A singleton instance of this class
         * is created and kept by the public CharsetDetector class
         */
        internal class CharsetRecog_euc_jp : CharsetRecog_euc() {
            override fun getName(): String {
                return "EUC-JP"
            }

            override fun match(det: CharsetDetector): CharsetMatch? {
                val confidence = match(det, commonChars)
                return if (confidence == 0) null else CharsetMatch(det, this, confidence)
            }

            override fun getLanguage(): String {
                return "ja"
            }

            companion object {
                val commonChars: IntArray =
                // TODO:  This set of data comes from the character frequency-
                //        of-occurence analysis tool.  The data needs to be moved
                    //        into a resource and loaded from there.
                    intArrayOf(
                        0xa1a1,
                        0xa1a2,
                        0xa1a3,
                        0xa1a6,
                        0xa1bc,
                        0xa1ca,
                        0xa1cb,
                        0xa1d6,
                        0xa1d7,
                        0xa4a2,
                        0xa4a4,
                        0xa4a6,
                        0xa4a8,
                        0xa4aa,
                        0xa4ab,
                        0xa4ac,
                        0xa4ad,
                        0xa4af,
                        0xa4b1,
                        0xa4b3,
                        0xa4b5,
                        0xa4b7,
                        0xa4b9,
                        0xa4bb,
                        0xa4bd,
                        0xa4bf,
                        0xa4c0,
                        0xa4c1,
                        0xa4c3,
                        0xa4c4,
                        0xa4c6,
                        0xa4c7,
                        0xa4c8,
                        0xa4c9,
                        0xa4ca,
                        0xa4cb,
                        0xa4ce,
                        0xa4cf,
                        0xa4d0,
                        0xa4de,
                        0xa4df,
                        0xa4e1,
                        0xa4e2,
                        0xa4e4,
                        0xa4e8,
                        0xa4e9,
                        0xa4ea,
                        0xa4eb,
                        0xa4ec,
                        0xa4ef,
                        0xa4f2,
                        0xa4f3,
                        0xa5a2,
                        0xa5a3,
                        0xa5a4,
                        0xa5a6,
                        0xa5a7,
                        0xa5aa,
                        0xa5ad,
                        0xa5af,
                        0xa5b0,
                        0xa5b3,
                        0xa5b5,
                        0xa5b7,
                        0xa5b8,
                        0xa5b9,
                        0xa5bf,
                        0xa5c3,
                        0xa5c6,
                        0xa5c7,
                        0xa5c8,
                        0xa5c9,
                        0xa5cb,
                        0xa5d0,
                        0xa5d5,
                        0xa5d6,
                        0xa5d7,
                        0xa5de,
                        0xa5e0,
                        0xa5e1,
                        0xa5e5,
                        0xa5e9,
                        0xa5ea,
                        0xa5eb,
                        0xa5ec,
                        0xa5ed,
                        0xa5f3,
                        0xb8a9,
                        0xb9d4,
                        0xbaee,
                        0xbbc8,
                        0xbef0,
                        0xbfb7,
                        0xc4ea,
                        0xc6fc,
                        0xc7bd,
                        0xcab8,
                        0xcaf3,
                        0xcbdc,
                        0xcdd1
                    )
            }
        }

        /**
         * The charset recognize for EUC-KR.  A singleton instance of this class
         * is created and kept by the public CharsetDetector class
         */
        internal class CharsetRecog_euc_kr : CharsetRecog_euc() {
            override fun getName(): String {
                return "EUC-KR"
            }

            override fun match(det: CharsetDetector): CharsetMatch? {
                val confidence = match(det, commonChars)
                return if (confidence == 0) null else CharsetMatch(det, this, confidence)
            }

            override fun getLanguage(): String {
                return "ko"
            }

            companion object {
                val commonChars: IntArray =
                // TODO:  This set of data comes from the character frequency-
                //        of-occurence analysis tool.  The data needs to be moved
                    //        into a resource and loaded from there.
                    intArrayOf(
                        0xb0a1,
                        0xb0b3,
                        0xb0c5,
                        0xb0cd,
                        0xb0d4,
                        0xb0e6,
                        0xb0ed,
                        0xb0f8,
                        0xb0fa,
                        0xb0fc,
                        0xb1b8,
                        0xb1b9,
                        0xb1c7,
                        0xb1d7,
                        0xb1e2,
                        0xb3aa,
                        0xb3bb,
                        0xb4c2,
                        0xb4cf,
                        0xb4d9,
                        0xb4eb,
                        0xb5a5,
                        0xb5b5,
                        0xb5bf,
                        0xb5c7,
                        0xb5e9,
                        0xb6f3,
                        0xb7af,
                        0xb7c2,
                        0xb7ce,
                        0xb8a6,
                        0xb8ae,
                        0xb8b6,
                        0xb8b8,
                        0xb8bb,
                        0xb8e9,
                        0xb9ab,
                        0xb9ae,
                        0xb9cc,
                        0xb9ce,
                        0xb9fd,
                        0xbab8,
                        0xbace,
                        0xbad0,
                        0xbaf1,
                        0xbbe7,
                        0xbbf3,
                        0xbbfd,
                        0xbcad,
                        0xbcba,
                        0xbcd2,
                        0xbcf6,
                        0xbdba,
                        0xbdc0,
                        0xbdc3,
                        0xbdc5,
                        0xbec6,
                        0xbec8,
                        0xbedf,
                        0xbeee,
                        0xbef8,
                        0xbefa,
                        0xbfa1,
                        0xbfa9,
                        0xbfc0,
                        0xbfe4,
                        0xbfeb,
                        0xbfec,
                        0xbff8,
                        0xc0a7,
                        0xc0af,
                        0xc0b8,
                        0xc0ba,
                        0xc0bb,
                        0xc0bd,
                        0xc0c7,
                        0xc0cc,
                        0xc0ce,
                        0xc0cf,
                        0xc0d6,
                        0xc0da,
                        0xc0e5,
                        0xc0fb,
                        0xc0fc,
                        0xc1a4,
                        0xc1a6,
                        0xc1b6,
                        0xc1d6,
                        0xc1df,
                        0xc1f6,
                        0xc1f8,
                        0xc4a1,
                        0xc5cd,
                        0xc6ae,
                        0xc7cf,
                        0xc7d1,
                        0xc7d2,
                        0xc7d8,
                        0xc7e5,
                        0xc8ad
                    )
            }
        }
    }

    /**
     * GB-18030 recognizer. Uses simplified Chinese statistics.
     */
    internal class CharsetRecog_gb_18030 : CharsetRecog_mbcs() {
        /*
                 *  (non-Javadoc)
                 *  Get the next character value for EUC based encodings.
                 *  Character "value" is simply the raw bytes that make up the character
                 *     packed into an int.
                 */
        override fun nextChar(it: iteratedChar, det: CharsetDetector): Boolean {
            it.error = false
            // 原 Java 局部变量 firstByte/secondByte/thirdByte/fourthByte 在 labeled block (buildChar:) 内赋值
            // Kotlin 中用 var 闭包捕获 + run { ... return@run ... } 替代 labeled block
            var firstByte: Int = 0
            var secondByte: Int = 0
            var thirdByte: Int = 0
            var fourthByte: Int = 0

            run {
                it.charValue = it.nextByte(det)
                firstByte = it.charValue

                if (firstByte < 0) {
                    // Ran off the end of the input data
                    it.done = true
                    return@run
                }

                if (firstByte <= 0x80) {
                    // single byte char
                    return@run
                }

                secondByte = it.nextByte(det)
                it.charValue = (it.charValue shl 8) or secondByte
                if (firstByte <= 0xFE) {
                    // Two byte Char
                    if ((secondByte >= 0x40 && secondByte <= 0x7E) || (secondByte >= 80 && secondByte <= 0xFE)) {
                        return@run
                    }

                    // Four byte char
                    if (secondByte >= 0x30 && secondByte <= 0x39) {
                        thirdByte = it.nextByte(det)

                        if (thirdByte >= 0x81 && thirdByte <= 0xFE) {
                            fourthByte = it.nextByte(det)

                            if (fourthByte >= 0x30 && fourthByte <= 0x39) {
                                it.charValue =
                                    (it.charValue shl 16) or (thirdByte shl 8) or fourthByte
                                return@run
                            }
                        }
                    }

                    it.error = true
                }
            }

            return (!it.done)
        }

        override fun getName(): String {
            return "GB18030"
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val confidence = match(det, commonChars)
            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }

        override fun getLanguage(): String {
            return "zh"
        }

        companion object {
            val commonChars: IntArray =
            // TODO:  This set of data comes from the character frequency-
            //        of-occurence analysis tool.  The data needs to be moved
                //        into a resource and loaded from there.
                intArrayOf(
                    0xa1a1, 0xa1a2, 0xa1a3, 0xa1a4, 0xa1b0, 0xa1b1, 0xa1f1, 0xa1f3, 0xa3a1, 0xa3ac,
                    0xa3ba, 0xb1a8, 0xb1b8, 0xb1be, 0xb2bb, 0xb3c9, 0xb3f6, 0xb4f3, 0xb5bd, 0xb5c4,
                    0xb5e3, 0xb6af, 0xb6d4, 0xb6e0, 0xb7a2, 0xb7a8, 0xb7bd, 0xb7d6, 0xb7dd, 0xb8b4,
                    0xb8df, 0xb8f6, 0xb9ab, 0xb9c9, 0xb9d8, 0xb9fa, 0xb9fd, 0xbacd, 0xbba7, 0xbbd6,
                    0xbbe1, 0xbbfa, 0xbcbc, 0xbcdb, 0xbcfe, 0xbdcc, 0xbecd, 0xbedd, 0xbfb4, 0xbfc6,
                    0xbfc9, 0xc0b4, 0xc0ed, 0xc1cb, 0xc2db, 0xc3c7, 0xc4dc, 0xc4ea, 0xc5cc, 0xc6f7,
                    0xc7f8, 0xc8ab, 0xc8cb, 0xc8d5, 0xc8e7, 0xc9cf, 0xc9fa, 0xcab1, 0xcab5, 0xcac7,
                    0xcad0, 0xcad6, 0xcaf5, 0xcafd, 0xccec, 0xcdf8, 0xceaa, 0xcec4, 0xced2, 0xcee5,
                    0xcfb5, 0xcfc2, 0xcfd6, 0xd0c2, 0xd0c5, 0xd0d0, 0xd0d4, 0xd1a7, 0xd2aa, 0xd2b2,
                    0xd2b5, 0xd2bb, 0xd2d4, 0xd3c3, 0xd3d0, 0xd3fd, 0xd4c2, 0xd4da, 0xd5e2, 0xd6d0
                )
        }
    }
}
