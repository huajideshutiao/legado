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
package io.legado.app.lib.epublib.util.commons.io


import android.os.Build
import io.legado.app.lib.epublib.util.IOUtil.EOF
import java.io.IOException
import java.io.InputStream
import java.util.Arrays

/**
 * This class is used to wrap a stream that includes an encoded [ByteOrderMark] as its first bytes.
 * 
 * 
 * This class detects these bytes and, if required, can automatically skip them and return the subsequent byte as the
 * first byte in the stream.
 * 
 * 
 * The [ByteOrderMark] implementation has the following pre-defined BOMs:
 * 
 *  * UTF-8 - [ByteOrderMark.UTF_8]
 *  * UTF-16BE - [ByteOrderMark.UTF_16LE]
 *  * UTF-16LE - [ByteOrderMark.UTF_16BE]
 *  * UTF-32BE - [ByteOrderMark.UTF_32LE]
 *  * UTF-32LE - [ByteOrderMark.UTF_32BE]
 * 
 * 
 * 
 * <h2>Example 1 - Detect and exclude a UTF-8 BOM</h2>
 * 
 * <pre>
 * BOMInputStream bomIn = new BOMInputStream(in);
 * if (bomIn.hasBOM()) {
 * // has a UTF-8 BOM
 * }
</pre> * 
 * 
 * <h2>Example 2 - Detect a UTF-8 BOM (but don't exclude it)</h2>
 * 
 * <pre>
 * boolean include = true;
 * BOMInputStream bomIn = new BOMInputStream(in, include);
 * if (bomIn.hasBOM()) {
 * // has a UTF-8 BOM
 * }
</pre> * 
 * 
 * <h2>Example 3 - Detect Multiple BOMs</h2>
 * 
 * <pre>
 * BOMInputStream bomIn = new BOMInputStream(in,
 * ByteOrderMark.UTF_16LE, ByteOrderMark.UTF_16BE,
 * ByteOrderMark.UTF_32LE, ByteOrderMark.UTF_32BE
 * );
 * if (bomIn.hasBOM() == false) {
 * // No BOM found
 * } else if (bomIn.hasBOM(ByteOrderMark.UTF_16LE)) {
 * // has a UTF-16LE BOM
 * } else if (bomIn.hasBOM(ByteOrderMark.UTF_16BE)) {
 * // has a UTF-16BE BOM
 * } else if (bomIn.hasBOM(ByteOrderMark.UTF_32LE)) {
 * // has a UTF-32LE BOM
 * } else if (bomIn.hasBOM(ByteOrderMark.UTF_32BE)) {
 * // has a UTF-32BE BOM
 * }
</pre> * 
 * 
 * @see ByteOrderMark
 * 
 * @see [Wikipedia - Byte Order Mark](http://en.wikipedia.org/wiki/Byte_order_mark)
 * 
 * @since 2.0
 */
class BOMInputStream(delegate: InputStream?, include: Boolean, vararg boms: ByteOrderMark?) :
    ProxyInputStream(delegate) {
    private val include: Boolean

    /**
     * BOMs are sorted from longest to shortest.
     */
    private val boms: MutableList<ByteOrderMark>
    private var byteOrderMark: ByteOrderMark? = null
    private var firstBytes: IntArray? = null
    private var fbLength = 0
    private var fbIndex = 0
    private var markFbIndex = 0
    private var markedAtStart = false

    /**
     * Constructs a new BOM InputStream that excludes a [ByteOrderMark.UTF_8] BOM.
     * 
     * @param delegate the InputStream to delegate to
     */
    @Suppress("unused")
    constructor(delegate: InputStream?) : this(delegate, false, ByteOrderMark.Companion.UTF_8)

    /**
     * Constructs a new BOM InputStream that detects a a [ByteOrderMark.UTF_8] and optionally includes it.
     * 
     * @param delegate the InputStream to delegate to
     * @param include  true to include the UTF-8 BOM or false to exclude it
     */
    @Suppress("unused")
    constructor(delegate: InputStream?, include: Boolean) : this(
        delegate,
        include,
        ByteOrderMark.Companion.UTF_8
    )

    /**
     * Constructs a new BOM InputStream that excludes the specified BOMs.
     * 
     * @param delegate the InputStream to delegate to
     * @param boms     The BOMs to detect and exclude
     */
    @Suppress("unused")
    constructor(delegate: InputStream?, vararg boms: ByteOrderMark?) : this(delegate, false, *boms)

    /**
     * Constructs a new BOM InputStream that detects the specified BOMs and optionally includes them.
     * 
     * @param delegate the InputStream to delegate to
     * @param include  true to include the specified BOMs or false to exclude them
     * @param boms     The BOMs to detect and optionally exclude
     */
    init {
        require(boms.isNotEmpty()) { "No BOMs specified" }
        this.include = include
        val list = Arrays.asList<ByteOrderMark?>(*boms)
        // Sort the BOMs to match the longest BOM first because some BOMs have the same starting two bytes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            list.sortWith(ByteOrderMarkLengthComparator)
        }
        this.boms = list
    }

    /**
     * Indicates whether the stream contains one of the specified BOMs.
     * 
     * @return true if the stream has one of the specified BOMs, otherwise false if it does not
     * @throws IOException if an error reading the first bytes of the stream occurs
     */
    @Suppress("unused")
    @Throws(IOException::class)
    fun hasBOM(): Boolean {
        return this.bOM != null
    }

    /**
     * Indicates whether the stream contains the specified BOM.
     * 
     * @param bom The BOM to check for
     * @return true if the stream has the specified BOM, otherwise false if it does not
     * @throws IllegalArgumentException if the BOM is not one the stream is configured to detect
     * @throws IOException              if an error reading the first bytes of the stream occurs
     */
    @Suppress("unused")
    @Throws(IOException::class)
    fun hasBOM(bom: ByteOrderMark?): Boolean {
        require(boms.contains(bom)) { "Stream not configure to detect " + bom }
        this.bOM
        return byteOrderMark != null && byteOrderMark == bom
    }

    @get:Throws(IOException::class)
    val bOM: ByteOrderMark?
        /**
         * Return the BOM (Byte Order Mark).
         * 
         * @return The BOM or null if none
         * @throws IOException if an error reading the first bytes of the stream occurs
         */
        get() {
            if (firstBytes == null) {
                fbLength = 0
                // BOMs are sorted from longest to shortest
                val maxBomSize = boms.get(0).length()
                firstBytes = IntArray(maxBomSize)
                // Read first maxBomSize bytes
                for (i in firstBytes!!.indices) {
                    firstBytes!![i] = `in`.read()
                    fbLength++
                    if (firstBytes!![i] < 0) {
                        break
                    }
                }
                // match BOM in firstBytes
                byteOrderMark = find()
                if (byteOrderMark != null) {
                    if (!include) {
                        if (byteOrderMark!!.length() < firstBytes!!.size) {
                            fbIndex = byteOrderMark!!.length()
                        } else {
                            fbLength = 0
                        }
                    }
                }
            }
            return byteOrderMark
        }

    @get:Throws(IOException::class)
    val bOMCharsetName: String?
        /**
         * Return the BOM charset Name - [ByteOrderMark.getCharsetName].
         * 
         * @return The BOM charset Name or null if no BOM found
         * @throws IOException if an error reading the first bytes of the stream occurs
         */
        get() {
            this.bOM
            return if (byteOrderMark == null) null else byteOrderMark!!.charsetName
        }

    /**
     * This method reads and either preserves or skips the first bytes in the stream. It behaves like the single-byte
     * `read()` method, either returning a valid byte or -1 to indicate that the initial bytes have been
     * processed already.
     * 
     * @return the byte read (excluding BOM) or -1 if the end of stream
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    private fun readFirstBytes(): Int {
        this.bOM
        return if (fbIndex < fbLength) firstBytes!![fbIndex++] else EOF
    }

    /**
     * Find a BOM with the specified bytes.
     * 
     * @return The matched BOM or null if none matched
     */
    private fun find(): ByteOrderMark? {
        for (bom in boms) {
            if (matches(bom)) {
                return bom
            }
        }
        return null
    }

    /**
     * Check if the bytes match a BOM.
     * 
     * @param bom The BOM
     * @return true if the bytes match the bom, otherwise false
     */
    private fun matches(bom: ByteOrderMark): Boolean {
        // if (bom.length() != fbLength) {
        // return false;
        // }
        // firstBytes may be bigger than the BOM bytes
        for (i in 0..<bom.length()) {
            if (bom.get(i) != firstBytes!![i]) {
                return false
            }
        }
        return true
    }

    // ----------------------------------------------------------------------------
    // Implementation of InputStream
    // ----------------------------------------------------------------------------
    /**
     * Invokes the delegate's `read()` method, detecting and optionally skipping BOM.
     * 
     * @return the byte read (excluding BOM) or -1 if the end of stream
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    override fun read(): Int {
        val b = readFirstBytes()
        return if (b >= 0) b else `in`.read()
    }

    /**
     * Invokes the delegate's `read(byte[], int, int)` method, detecting and optionally skipping BOM.
     * 
     * @param buf the buffer to read the bytes into
     * @param off The start offset
     * @param len The number of bytes to read (excluding BOM)
     * @return the number of bytes read or -1 if the end of stream
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    override fun read(buf: ByteArray?, off: Int, len: Int): Int {
        var off = off
        var len = len
        if (buf == null) return `in`.read(null, off, len)
        var firstCount = 0
        var b = 0
        while (len > 0 && b >= 0) {
            b = readFirstBytes()
            if (b >= 0) {
                buf[off++] = (b and 0xFF).toByte()
                len--
                firstCount++
            }
        }
        val secondCount = `in`.read(buf, off, len)
        return if (secondCount < 0) if (firstCount > 0) firstCount else EOF else firstCount + secondCount
    }

    /**
     * Invokes the delegate's `read(byte[])` method, detecting and optionally skipping BOM.
     * 
     * @param buf the buffer to read the bytes into
     * @return the number of bytes read (excluding BOM) or -1 if the end of stream
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    override fun read(buf: ByteArray?): Int {
        return read(buf, 0, buf?.size ?: 0)
    }

    /**
     * Invokes the delegate's `mark(int)` method.
     * 
     * @param readlimit read ahead limit
     */
    @Synchronized
    override fun mark(readlimit: Int) {
        markFbIndex = fbIndex
        markedAtStart = firstBytes == null
        `in`.mark(readlimit)
    }

    /**
     * Invokes the delegate's `reset()` method.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Synchronized
    @Throws(IOException::class)
    override fun reset() {
        fbIndex = markFbIndex
        if (markedAtStart) {
            firstBytes = null
        }

        `in`.reset()
    }

    /**
     * Invokes the delegate's `skip(long)` method, detecting and optionally skipping BOM.
     * 
     * @param n the number of bytes to skip
     * @return the number of bytes to skipped or -1 if the end of stream
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        var skipped = 0
        while ((n > skipped) && (readFirstBytes() >= 0)) {
            skipped++
        }
        return `in`.skip(n - skipped) + skipped
    }

    companion object {
        /**
         * Compares ByteOrderMark objects in descending length order.
         */
        private val ByteOrderMarkLengthComparator =
            Comparator { bom1: ByteOrderMark?, bom2: ByteOrderMark? ->
                val len1 = bom1!!.length()
                val len2 = bom2!!.length()
                Integer.compare(len2, len1)
            }
    }
}
