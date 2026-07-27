package io.legado.app.lib.epublib.util.commons.io

import java.io.Serializable

/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

/**
 * Byte Order Mark (BOM) representation - see [BOMInputStream].
 * 
 * @see BOMInputStream
 * 
 * @see [Wikipedia: Byte Order Mark](http://en.wikipedia.org/wiki/Byte_order_mark)
 * 
 * @see [W3C: Autodetection of Character Encodings
 * 
 * @since 2.0
](http://www.w3.org/TR/2006/REC-xml-20060816/.sec-guessing) */
class ByteOrderMark(charsetName: String, vararg bytes: Int) : Serializable {
    /**
     * Return the name of the [java.nio.charset.Charset] the BOM represents.
     * 
     * @return the character set name
     */
    val charsetName: String
    private val bytes: IntArray

    /**
     * Construct a new BOM.
     * 
     * @param charsetName The name of the charset the BOM represents
     * @param bytes       The BOM's bytes
     * @throws IllegalArgumentException if the charsetName is null or
     * zero length
     * @throws IllegalArgumentException if the bytes are null or zero
     * length
     */
    init {
        require(!(charsetName == null || charsetName.isEmpty())) { "No charsetName specified" }
        require(!(bytes == null || bytes.size == 0)) { "No bytes specified" }
        this.charsetName = charsetName
        this.bytes = IntArray(bytes.size)
        System.arraycopy(bytes, 0, this.bytes, 0, bytes.size)
    }

    /**
     * Return the length of the BOM's bytes.
     * 
     * @return the length of the BOM's bytes
     */
    fun length(): Int {
        return bytes.size
    }

    /**
     * The byte at the specified position.
     * 
     * @param pos The position
     * @return The specified byte
     */
    fun get(pos: Int): Int {
        return bytes[pos]
    }

    /**
     * Return a copy of the BOM's bytes.
     * 
     * @return a copy of the BOM's bytes
     */
    fun getBytes(): ByteArray {
        val copy = ByteArray(bytes.size)
        for (i in bytes.indices) {
            copy[i] = bytes[i].toByte()
        }
        return copy
    }

    /**
     * Indicates if this BOM's bytes equals another.
     * 
     * @param obj The object to compare to
     * @return true if the bom's bytes are equal, otherwise
     * false
     */
    override fun equals(obj: Any?): Boolean {
        if (obj !is ByteOrderMark) {
            return false
        }
        val bom = obj
        if (bytes.size != bom.length()) {
            return false
        }
        for (i in bytes.indices) {
            if (bytes[i] != bom.get(i)) {
                return false
            }
        }
        return true
    }

    /**
     * Return the hashcode for this BOM.
     * 
     * @return the hashcode for this BOM.
     * @see Object.hashCode
     */
    override fun hashCode(): Int {
        var hashCode = javaClass.hashCode()
        for (b in bytes) {
            hashCode += b
        }
        return hashCode
    }

    /**
     * Provide a String representation of the BOM.
     * 
     * @return the length of the BOM's bytes
     */
    override fun toString(): String {
        val builder = StringBuilder()
        builder.append(javaClass.getSimpleName())
        builder.append('[')
        builder.append(charsetName)
        builder.append(": ")
        for (i in bytes.indices) {
            if (i > 0) {
                builder.append(",")
            }
            builder.append("0x")
            builder.append(Integer.toHexString(0xFF and bytes[i]).uppercase())
        }
        builder.append(']')
        return builder.toString()
    }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * UTF-8 BOM
         */
        val UTF_8: ByteOrderMark = ByteOrderMark("UTF-8", 0xEF, 0xBB, 0xBF)

        /**
         * UTF-16BE BOM (Big-Endian)
         */
        val UTF_16BE: ByteOrderMark = ByteOrderMark("UTF-16BE", 0xFE, 0xFF)

        /**
         * UTF-16LE BOM (Little-Endian)
         */
        val UTF_16LE: ByteOrderMark = ByteOrderMark("UTF-16LE", 0xFF, 0xFE)

        /**
         * UTF-32BE BOM (Big-Endian)
         * 
         * @since 2.2
         */
        val UTF_32BE: ByteOrderMark = ByteOrderMark("UTF-32BE", 0x00, 0x00, 0xFE, 0xFF)

        /**
         * UTF-32LE BOM (Little-Endian)
         * 
         * @since 2.2
         */
        val UTF_32LE: ByteOrderMark = ByteOrderMark("UTF-32LE", 0xFF, 0xFE, 0x00, 0x00)

        /**
         * Unicode BOM character; external form depends on the encoding.
         * 
         * @see [Byte Order Mark
         * @since 2.5
        ](http://unicode.org/faq/utf_bom.html.BOM) */
        @Suppress("unused")
        const val UTF_BOM: Char = '\uFEFF'
    }
}