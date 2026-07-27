// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/**
 * ******************************************************************************
 * Copyright (C) 2005 - 2014, International Business Machines Corporation and  *
 * others. All Rights Reserved.                                                *
 * ******************************************************************************
 */
package io.legado.app.lib.icu4j

/**
 * Charset recognizer for UTF-8
 */
internal class CharsetRecog_UTF8 : CharsetRecognizer() {
    override fun getName(): String {
        return "UTF-8"
    }

    /* (non-Javadoc)
     * @see com.ibm.icu.text.CharsetRecognizer#match(com.ibm.icu.text.CharsetDetector)
     */
    override fun match(det: CharsetDetector): CharsetMatch? {
        var hasBOM = false
        var numValid = 0
        var numInvalid = 0
        val input = det.fRawInput
        var i: Int
        var trailBytes = 0
        var confidence: Int

        if (det.fRawLength >= 3 && (input[0].toInt() and 0xFF) == 0xef && (input[1].toInt() and 0xFF) == 0xbb && (input[2].toInt() and 0xFF) == 0xbf) {
            hasBOM = true
        }

        // Scan for multi-byte sequences
        i = 0
        while (i < det.fRawLength) {
            var b = input[i].toInt()
            if ((b and 0x80) == 0) {
                i++
                continue  // ASCII
            }

            // Hi bit on char found.  Figure out how long the sequence should be
            if ((b and 0x0e0) == 0x0c0) {
                trailBytes = 1
            } else if ((b and 0x0f0) == 0x0e0) {
                trailBytes = 2
            } else if ((b and 0x0f8) == 0xf0) {
                trailBytes = 3
            } else {
                numInvalid++
                i++
                continue
            }

            // Verify that we've got the right number of trail bytes in the sequence
            while (true) {
                i++
                if (i >= det.fRawLength) {
                    break
                }
                b = input[i].toInt()
                if ((b and 0xc0) != 0x080) {
                    numInvalid++
                    break
                }
                if (--trailBytes == 0) {
                    numValid++
                    break
                }
            }
            i++
        }

        // Cook up some sort of confidence score, based on presense of a BOM
        //    and the existence of valid and/or invalid multi-byte sequences.
        confidence = 0
        if (hasBOM && numInvalid == 0) {
            confidence = 100
        } else if (hasBOM && numValid > numInvalid * 10) {
            confidence = 80
        } else if (numValid > 3 && numInvalid == 0) {
            confidence = 100
        } else if (numValid > 0 && numInvalid == 0) {
            confidence = 80
        } else if (numValid == 0 && numInvalid == 0) {
            // Plain ASCII. Confidence must be > 10, it's more likely than UTF-16, which
            //              accepts ASCII with confidence = 10.
            // TODO: add plain ASCII as an explicitly detected type.
            confidence = 15
        } else if (numValid > numInvalid * 10) {
            // Probably corruput utf-8 data.  Valid sequences aren't likely by chance.
            confidence = 25
        }
        return if (confidence == 0) null else CharsetMatch(det, this, confidence)
    }
}
