// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 *******************************************************************************
 * Copyright (C) 2005 - 2012, International Business Machines Corporation and  *
 * others. All Rights Reserved.                                                *
 *******************************************************************************
 */
package io.legado.app.lib.icu4j

/**
 * class CharsetRecog_2022  part of the ICU charset detection imlementation.
 * This is a superclass for the individual detectors for
 * each of the detectable members of the ISO 2022 family
 * of encodings.
 * 
 * 
 * The separate classes are nested within this class.
 */
internal abstract class CharsetRecog_2022 : CharsetRecognizer() {
    /**
     * Matching function shared among the 2022 detectors JP, CN and KR
     * Counts up the number of legal an unrecognized escape sequences in
     * the sample of text, and computes a score based on the total number &
     * the proportion that fit the encoding.
     * 
     * @param text            the byte buffer containing text to analyse
     * @param textLen         the size of the text in the byte.
     * @param escapeSequences the byte escape sequences to test for.
     * @return match quality, in the range of 0-100.
     */
    fun match(text: ByteArray, textLen: Int, escapeSequences: Array<ByteArray>): Int {
        var i: Int
        var j: Int
        var escN: Int
        var hits = 0
        var misses = 0
        var shifts = 0
        var quality: Int
        i = 0
        scanInput@ while (i < textLen) {
            if (text[i].toInt() == 0x1b) {
                escN = 0
                checkEscapes@ while (escN < escapeSequences.size) {
                    val seq = escapeSequences[escN]

                    if ((textLen - i) < seq.size) {
                        escN++
                        continue
                    }

                    j = 1
                    while (j < seq.size) {
                        if (seq[j] != text[i + j]) {
                            escN++
                            continue@checkEscapes
                        }
                        j++
                    }

                    hits++
                    i += seq.size - 1
                    i++
                    continue@scanInput
                    escN++
                }
                misses++
            }

            if (text[i].toInt() == 0x0e || text[i].toInt() == 0x0f) {
                // Shift in/out
                shifts++
            }
            i++
        }
        if (hits == 0) {
            return 0
        }

        //
        // Initial quality is based on relative proportion of recongized vs.
        //   unrecognized escape sequences.
        //   All good:  quality = 100;
        //   half or less good: quality = 0;
        //   linear inbetween.
        quality = (100 * hits - 100 * misses) / (hits + misses)

        // Back off quality if there were too few escape sequences seen.
        //   Include shifts in this computation, so that KR does not get penalized
        //   for having only a single Escape sequence, but many shifts.
        if (hits + shifts < 5) {
            quality -= (5 - (hits + shifts)) * 10
        }

        if (quality < 0) {
            quality = 0
        }
        return quality
    }


    internal class CharsetRecog_2022JP : CharsetRecog_2022() {
        private val escapeSequences = arrayOf<ByteArray>(
            byteArrayOf(0x1b, 0x24, 0x28, 0x43),  // KS X 1001:1992
            byteArrayOf(0x1b, 0x24, 0x28, 0x44),  // JIS X 212-1990
            byteArrayOf(0x1b, 0x24, 0x40),  // JIS C 6226-1978
            byteArrayOf(0x1b, 0x24, 0x41),  // GB 2312-80
            byteArrayOf(0x1b, 0x24, 0x42),  // JIS X 208-1983
            byteArrayOf(0x1b, 0x26, 0x40),  // JIS X 208 1990, 1997
            byteArrayOf(0x1b, 0x28, 0x42),  // ASCII
            byteArrayOf(0x1b, 0x28, 0x48),  // JIS-Roman
            byteArrayOf(0x1b, 0x28, 0x49),  // Half-width katakana
            byteArrayOf(0x1b, 0x28, 0x4a),  // JIS-Roman
            byteArrayOf(0x1b, 0x2e, 0x41),  // ISO 8859-1
            byteArrayOf(0x1b, 0x2e, 0x46) // ISO 8859-7
        )

        override fun getName(): String {
            return "ISO-2022-JP"
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val confidence = match(det.fInputBytes, det.fInputLen, escapeSequences)
            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }
    }

    internal class CharsetRecog_2022KR : CharsetRecog_2022() {
        private val escapeSequences = arrayOf<ByteArray>(
            byteArrayOf(0x1b, 0x24, 0x29, 0x43)
        )

        override fun getName(): String {
            return "ISO-2022-KR"
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val confidence = match(det.fInputBytes, det.fInputLen, escapeSequences)
            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }
    }

    internal class CharsetRecog_2022CN : CharsetRecog_2022() {
        private val escapeSequences = arrayOf<ByteArray>(
            byteArrayOf(0x1b, 0x24, 0x29, 0x41),  // GB 2312-80
            byteArrayOf(0x1b, 0x24, 0x29, 0x47),  // CNS 11643-1992 Plane 1
            byteArrayOf(0x1b, 0x24, 0x2A, 0x48),  // CNS 11643-1992 Plane 2
            byteArrayOf(0x1b, 0x24, 0x29, 0x45),  // ISO-IR-165
            byteArrayOf(0x1b, 0x24, 0x2B, 0x49),  // CNS 11643-1992 Plane 3
            byteArrayOf(0x1b, 0x24, 0x2B, 0x4A),  // CNS 11643-1992 Plane 4
            byteArrayOf(0x1b, 0x24, 0x2B, 0x4B),  // CNS 11643-1992 Plane 5
            byteArrayOf(0x1b, 0x24, 0x2B, 0x4C),  // CNS 11643-1992 Plane 6
            byteArrayOf(0x1b, 0x24, 0x2B, 0x4D),  // CNS 11643-1992 Plane 7
            byteArrayOf(0x1b, 0x4e),  // SS2
            byteArrayOf(0x1b, 0x4f),  // SS3
        )

        override fun getName(): String {
            return "ISO-2022-CN"
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val confidence = match(det.fInputBytes, det.fInputLen, escapeSequences)
            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }
    }
}

