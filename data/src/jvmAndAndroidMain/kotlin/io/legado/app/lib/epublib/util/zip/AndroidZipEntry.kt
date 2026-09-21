package io.legado.app.lib.epublib.util.zip

import java.util.Calendar
import java.util.Date
import java.util.zip.ZipOutputStream

/**
 * This class represents a member of a zip archive.  ZipFile and
 * ZipInputStream will give you instances of this class as information
 * about the members in an archive.  On the other hand ZipOutputStream
 * needs an instance of this class to create a new member.
 * 
 * @author Jochen Hoenicke
 */
class AndroidZipEntry : ZipConstants, Cloneable {
    /**
     * Returns the entry name.  The path components in the entry are
     * always separated by slashes ('/').
     */
    val name: String
    val nameLen: Int
    private var size = 0
    private var compressedSize = 0
    private var crc = 0
    private var dostime = 0
    private var known: Short = 0
    private var method: Short = -1
    private var extra: ByteArray? = null
    private var comment: String? = null

    var flags: Int = 0 /* used by ZipOutputStream */
    var offset: Int = 0 /* used by ZipFile and ZipOutputStream */


    /**
     * Creates a zip entry with the given name.
     * 
     * @param name the name. May include directory components separated
     * by '/'.
     * @throws NullPointerException     when name is null.
     * @throws IllegalArgumentException when name is bigger then 65535 chars.
     */
    constructor(name: String, nameLen: Int) {
        //int length = name.length();
        this.nameLen = nameLen
        require(nameLen <= 65535) { "name length is " + nameLen }
        this.name = name
    }


    /**
     * Creates a copy of the given zip entry.
     * 
     * @param e the entry to copy.
     */
    constructor(e: AndroidZipEntry) {
        name = e.name
        nameLen = e.nameLen
        known = e.known
        size = e.size
        compressedSize = e.compressedSize
        crc = e.crc
        dostime = e.dostime
        method = e.method
        extra = e.extra
        comment = e.comment
    }

    var dOSTime: Int
        get() {
            if ((known.toInt() and KNOWN_TIME) == 0) return 0
            else return dostime
        }
        set(dostime) {
            this.dostime = dostime
            known = (known.toInt() or KNOWN_TIME).toShort()
        }

    /**
     * Creates a copy of this zip entry.
     */
    /**
     * Clones the entry.
     */
    public override fun clone(): Any {
        try {
            // The JCL says that the `extra' field is also copied.
            val clone = super.clone() as AndroidZipEntry
            if (extra != null) clone.extra = extra!!.clone()
            return clone
        } catch (ex: CloneNotSupportedException) {
            throw InternalError()
        }
    }

    var time: Long
        /**
         * Gets the time of last modification of the entry.
         * 
         * @return the time of last modification of the entry, or -1 if unknown.
         */
        get() {
            if ((known.toInt() and KNOWN_TIME) == 0) return -1

            val sec = 2 * (dostime and 0x1f)
            val min = (dostime shr 5) and 0x3f
            val hrs = (dostime shr 11) and 0x1f
            val day = (dostime shr 16) and 0x1f
            val mon = ((dostime shr 21) and 0xf) - 1
            val year = ((dostime shr 25) and 0x7f) + 1980 /* since 1900 */

            try {
                cal = calendar
                synchronized(cal!!) {
                    cal!!.set(year, mon, day, hrs, min, sec)
                    return cal!!.getTime().getTime()
                }
            } catch (ex: RuntimeException) {
                /* Ignore illegal time stamp */
                known = (known.toInt() and KNOWN_TIME.inv()).toShort()
                return -1
            }
        }
        /**
         * Sets the time of last modification of the entry.
         *
         * @time the time of last modification of the entry.
         */
        set(time) {
            val cal: Calendar = calendar
            synchronized(cal) {
                cal.setTime(Date(time * 1000L))
                dostime =
                    ((cal.get(Calendar.YEAR) - 1980 and 0x7f) shl 25 or ((cal.get(Calendar.MONTH) + 1) shl 21
                        ) or ((cal.get(Calendar.DAY_OF_MONTH)) shl 16
                        ) or ((cal.get(Calendar.HOUR_OF_DAY)) shl 11
                        ) or ((cal.get(Calendar.MINUTE)) shl 5
                        ) or ((cal.get(Calendar.SECOND)) shr 1))
            }
            dostime = (dostime / 1000L).toInt()
            this.known = (this.known.toInt() or KNOWN_TIME).toShort()
        }

    /**
     * Sets the size of the uncompressed data.
     * 
     * @throws IllegalArgumentException if size is not in 0..0xffffffffL
     */
    fun setSize(size: Long) {
        require((size and -0x100000000L) == 0L)
        this.size = size.toInt()
        this.known = (this.known.toInt() or KNOWN_SIZE).toShort()
    }

    /**
     * Gets the size of the uncompressed data.
     * 
     * @return the size or -1 if unknown.
     */
    fun getSize(): Long {
        return if ((known.toInt() and KNOWN_SIZE) != 0) size.toLong() and 0xffffffffL else -1L
    }

    /**
     * Sets the size of the compressed data.
     * 
     * @throws IllegalArgumentException if size is not in 0..0xffffffffL
     */
    fun setCompressedSize(csize: Long) {
        require((csize and -0x100000000L) == 0L)
        this.compressedSize = csize.toInt()
        this.known = (this.known.toInt() or KNOWN_CSIZE).toShort()
    }

    /**
     * Gets the size of the compressed data.
     * 
     * @return the size or -1 if unknown.
     */
    fun getCompressedSize(): Long {
        return if ((known.toInt() and KNOWN_CSIZE) != 0) compressedSize.toLong() and 0xffffffffL else -1L
    }

    /**
     * Sets the crc of the uncompressed data.
     * 
     * @throws IllegalArgumentException if crc is not in 0..0xffffffffL
     */
    fun setCrc(crc: Long) {
        require((crc and -0x100000000L) == 0L)
        this.crc = crc.toInt()
        this.known = (this.known.toInt() or KNOWN_CRC).toShort()
    }

    /**
     * Gets the crc of the uncompressed data.
     * 
     * @return the crc or -1 if unknown.
     */
    fun getCrc(): Long {
        return if ((known.toInt() and KNOWN_CRC) != 0) crc.toLong() and 0xffffffffL else -1L
    }

    /**
     * Sets the compression method.  Only DEFLATED and STORED are
     * supported.
     * 
     * @throws IllegalArgumentException if method is not supported.
     * @see ZipOutputStream.DEFLATED
     * 
     * @see ZipOutputStream.STORED
     */
    fun setMethod(method: Int) {
        require(
            !(method != ZipOutputStream.STORED
                && method != ZipOutputStream.DEFLATED)
        )
        this.method = method.toShort()
    }

    /**
     * Gets the compression method.
     * 
     * @return the compression method or -1 if unknown.
     */
    fun getMethod(): Int {
        return method.toInt()
    }

    /**
     * Sets the extra data.
     * 
     * @throws IllegalArgumentException if extra is longer than 0xffff bytes.
     */
    fun setExtra(extra: ByteArray?) {
        if (extra == null) {
            this.extra = null
            return
        }

        require(extra.size <= 0xffff)
        this.extra = extra
        try {
            var pos = 0
            while (pos < extra.size) {
                val sig = ((extra[pos++].toInt() and 0xff)
                    or ((extra[pos++].toInt() and 0xff) shl 8))
                val len = ((extra[pos++].toInt() and 0xff)
                    or ((extra[pos++].toInt() and 0xff) shl 8))
                if (sig == 0x5455) {
                    /* extended time stamp */
                    val flags = extra[pos].toInt()
                    if ((flags and 1) != 0) {
                        val time = (((extra[pos + 1].toInt() and 0xff)
                            or ((extra[pos + 2].toInt() and 0xff) shl 8
                            ) or ((extra[pos + 3].toInt() and 0xff) shl 16
                            ) or ((extra[pos + 4].toInt() and 0xff) shl 24))).toLong()
                        this.time = time
                    }
                }
                pos += len
            }
        } catch (ex: ArrayIndexOutOfBoundsException) {
            /* be lenient */
            return
        }
    }

    /**
     * Gets the extra data.
     * 
     * @return the extra data or null if not set.
     */
    fun getExtra(): ByteArray? {
        return extra
    }

    /**
     * Sets the entry comment.
     * 
     * @throws IllegalArgumentException if comment is longer than 0xffff.
     */
    fun setComment(comment: String?) {
        require(!(comment != null && comment.length > 0xffff))
        this.comment = comment
    }

    /**
     * Gets the comment.
     * 
     * @return the comment or null if not set.
     */
    fun getComment(): String? {
        return comment
    }

    val isDirectory: Boolean
        /**
         * Gets true, if the entry is a directory.  This is solely
         * determined by the name, a trailing slash '/' marks a directory.
         */
        get() {
            val nlen = name.length
            return nlen > 0 && name.get(nlen - 1) == '/'
        }

    /**
     * Gets the string representation of this AndroidZipEntry.  This is just
     * the name as returned by getName().
     */
    override fun toString(): String {
        return name
    }

    /**
     * Gets the hashCode of this AndroidZipEntry.  This is just the hashCode
     * of the name.  Note that the equals method isn't changed, though.
     */
    override fun hashCode(): Int {
        return name.hashCode()
    }

    companion object {
        private const val KNOWN_SIZE = 1
        private const val KNOWN_CSIZE = 2
        private const val KNOWN_CRC = 4
        private const val KNOWN_TIME = 8

        private var cal: Calendar? = null

        /**
         * Compression method.  This method doesn't compress at all.
         */
        const val STORED: Int = 0

        /**
         * Compression method.  This method uses the Deflater.
         */
        const val DEFLATED: Int = 8

        @get:Synchronized
        private val calendar: Calendar
            get() {
                if (cal == null) cal =
                    Calendar.getInstance()

                return cal!!
            }
    }
}
