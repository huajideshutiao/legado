// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 *******************************************************************************
 * Copyright (C) 1996-2013, International Business Machines Corporation and    *
 * others. All Rights Reserved.                                                *
 *******************************************************************************
 *
 */
package io.legado.app.lib.icu4j

import kotlin.math.min

/**
 * This class matches UTF-16 and UTF-32, both big- and little-endian. The
 * BOM will be used if it is present.
 */
internal abstract class CharsetRecog_Unicode : CharsetRecognizer() {
    /* (non-Javadoc)
         * @see com.ibm.icu.text.CharsetRecognizer#getName()
         */
    abstract override fun getName(): String

    /* (non-Javadoc)
     * @see com.ibm.icu.text.CharsetRecognizer#match(com.ibm.icu.text.CharsetDetector)
     */
    abstract override fun match(det: CharsetDetector): CharsetMatch?

    internal class CharsetRecog_UTF_16_BE : CharsetRecog_Unicode() {
        override fun getName(): String {
            return "UTF-16BE"
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val input = det.fRawInput
            var confidence = 10

            val bytesToCheck = min(input.size, 30)
            var charIndex = 0
            while (charIndex < bytesToCheck - 1) {
                val codeUnit: Int = codeUnit16FromBytes(input[charIndex], input[charIndex + 1])
                if (charIndex == 0 && codeUnit == 0xFEFF) {
                    confidence = 100
                    break
                }
                confidence = adjustConfidence(codeUnit, confidence)
                if (confidence == 0 || confidence == 100) {
                    break
                }
                charIndex += 2
            }
            if (bytesToCheck < 4 && confidence < 100) {
                confidence = 0
            }
            if (confidence > 0) {
                return CharsetMatch(det, this, confidence)
            }
            return null
        }
    }

    internal class CharsetRecog_UTF_16_LE : CharsetRecog_Unicode() {
        override fun getName(): String {
            return "UTF-16LE"
        }

        override fun match(det: CharsetDetector): CharsetMatch? {
            val input = det.fRawInput
            var confidence = 10

            val bytesToCheck = min(input.size, 30)
            var charIndex = 0
            while (charIndex < bytesToCheck - 1) {
                val codeUnit: Int = codeUnit16FromBytes(input[charIndex + 1], input[charIndex])
                if (charIndex == 0 && codeUnit == 0xFEFF) {
                    confidence = 100
                    break
                }
                confidence = adjustConfidence(codeUnit, confidence)
                if (confidence == 0 || confidence == 100) {
                    break
                }
                charIndex += 2
            }
            if (bytesToCheck < 4 && confidence < 100) {
                confidence = 0
            }
            if (confidence > 0) {
                return CharsetMatch(det, this, confidence)
            }
            return null
        }
    }

    internal abstract class CharsetRecog_UTF_32 : CharsetRecog_Unicode() {
        abstract fun getChar(input: ByteArray, index: Int): Int

        abstract override fun getName(): String

        override fun match(det: CharsetDetector): CharsetMatch? {
            val input = det.fRawInput
            val limit = (det.fRawLength / 4) * 4
            var numValid = 0
            var numInvalid = 0
            var hasBOM = false
            var confidence = 0

            if (limit == 0) {
                return null
            }
            if (getChar(input, 0) == 0x0000FEFF) {
                hasBOM = true
            }

            var i = 0
            while (i < limit) {
                val ch = getChar(input, i)

                if (ch < 0 || ch >= 0x10FFFF || (ch >= 0xD800 && ch <= 0xDFFF)) {
                    numInvalid += 1
                } else {
                    numValid += 1
                }
                i += 4
            }


            // Cook up some sort of confidence score, based on presence of a BOM
            //    and the existence of valid and/or invalid multi-byte sequences.
            if (hasBOM && numInvalid == 0) {
                confidence = 100
            } else if (hasBOM && numValid > numInvalid * 10) {
                confidence = 80
            } else if (numValid > 3 && numInvalid == 0) {
                confidence = 100
            } else if (numValid > 0 && numInvalid == 0) {
                confidence = 80
            } else if (numValid > numInvalid * 10) {
                // Probably corrupt UTF-32BE data.  Valid sequences aren't likely by chance.
                confidence = 25
            }

            return if (confidence == 0) null else CharsetMatch(det, this, confidence)
        }
    }

    internal class CharsetRecog_UTF_32_BE : CharsetRecog_UTF_32() {
        override fun getChar(input: ByteArray, index: Int): Int {
            return (input[index].toInt() and 0xFF) shl 24 or ((input[index + 1].toInt() and 0xFF) shl 16) or (
                (input[index + 2].toInt() and 0xFF) shl 8) or (input[index + 3].toInt() and 0xFF)
        }

        override fun getName(): String {
            return "UTF-32BE"
        }
    }


    internal class CharsetRecog_UTF_32_LE : CharsetRecog_UTF_32() {
        override fun getChar(input: ByteArray, index: Int): Int {
            return (input[index + 3].toInt() and 0xFF) shl 24 or ((input[index + 2].toInt() and 0xFF) shl 16) or (
                (input[index + 1].toInt() and 0xFF) shl 8) or (input[index].toInt() and 0xFF)
        }

        override fun getName(): String {
            return "UTF-32LE"
        }
    }

    companion object {
        fun codeUnit16FromBytes(hi: Byte, lo: Byte): Int {
            return ((hi.toInt() and 0xff) shl 8) or (lo.toInt() and 0xff)
        }

        // UTF-16 confidence calculation. Very simple minded, but better than nothing.
        //   Any 8 bit non-control characters bump the confidence up. These have a zero high byte,
        //     and are very likely to be UTF-16, although they could also be part of a UTF-32 code.
        //   NULs are a contra-indication, they will appear commonly if the actual encoding is UTF-32.
        //   NULs should be rare in actual text.
        fun adjustConfidence(codeUnit: Int, confidence: Int): Int {
            var confidence = confidence
            if (codeUnit == 0) {
                confidence -= 10
            } else if ((codeUnit >= 0x20 && codeUnit <= 0xff) || codeUnit == 0x0a) {
                confidence += 10
            }
            if (confidence < 0) {
                confidence = 0
            } else if (confidence > 100) {
                confidence = 100
            }
            return confidence
        }
    }
}
