package io.legado.app.lib.epublib.util.zip

import android.os.ParcelFileDescriptor
import io.legado.app.lib.epublib.base.PfdHelper
import io.legado.app.lib.epublib.base.PfdHelper.seek
import java.io.BufferedInputStream
import java.io.DataInput
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Enumeration
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * This class represents a Zip archive.  You can ask for the contained
 * entries, or get an input stream for a file entry.  The entry is
 * automatically decompressed.
 * 
 * 
 * This class is thread safe:  You can open input streams for arbitrary
 * entries in different threads.
 * 
 * @author Jochen Hoenicke
 * @author Artur Biesiadowski
 */
class AndroidZipFile : ZipConstants {
    /**
     * Returns the (path) name of this zip file.
     */
    // Name of this zip file.
    val name: String?

    // File from which zip entries are read.
    //private final RandomAccessFile raf;
    private val pfd: ParcelFileDescriptor

    // The entries of this zip file when initialized and not yet closed.
    private var entries: HashMap<String?, AndroidZipEntry?>? = null

    private var closed = false

    /**
     * Opens a Zip file with the given name for reading.
     * 
     * @throws IOException  if a i/o error occured.
     * @throws ZipException if the file doesn't contain a valid zip
     * archive.
     */
    constructor(pfd: ParcelFileDescriptor, name: String?) {
        this.pfd = pfd
        this.name = name
    }

    /**
     * Opens a Zip file reading the given File.
     * 
     * @throws IOException  if a i/o error occured.
     * @throws ZipException if the file doesn't contain a valid zip
     * archive.
     */
    constructor(file: File) {
        this.pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        this.name = file.getPath()
    }

    /**
     * Opens a Zip file reading the given File in the given mode.
     * 
     * 
     * If the OPEN_DELETE mode is specified, the zip file will be deleted at
     * some time moment after it is opened. It will be deleted before the zip
     * file is closed or the Virtual Machine exits.
     * 
     * 
     * The contents of the zip file will be accessible until it is closed.
     * 
     * 
     * The OPEN_DELETE mode is currently unimplemented in this library
     * 
     * @param mode Must be one of OPEN_READ or OPEN_READ | OPEN_DELETE
     * @throws IOException  if a i/o error occured.
     * @throws ZipException if the file doesn't contain a valid zip
     * archive.
     * @since JDK1.3
     */
    //    public AndroidZipFile(File file, int mode) throws ZipException, IOException {
    //        if ((mode & OPEN_DELETE) != 0) {
    //            throw new IllegalArgumentException
    //                    ("OPEN_DELETE mode not supported yet in net.sf.jazzlib.AndroidZipFile");
    //        }
    //        this.raf = new RandomAccessFile(file, "r");
    //        this.name = file.getPath();
    //    }
    /**
     * Read an unsigned short in little endian byte order from the given
     * DataInput stream using the given byte buffer.
     * 
     * @param di DataInput stream to read from.
     * @param b  the byte buffer to read in (must be at least 2 bytes long).
     * @return The value read.
     * @throws IOException  if a i/o error occured.
     * @throws EOFException if the file ends prematurely
     */
    @Throws(IOException::class)
    private fun readLeShort(di: DataInput, b: ByteArray): Int {
        di.readFully(b, 0, 2)
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
    }

    @Throws(IOException::class)
    private fun readLeShort(pfd: ParcelFileDescriptor?, b: ByteArray): Int {
        PfdHelper.readFully(pfd!!, b, 0, 2) //di.readFully(b, 0, 2);
        return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
    }

    /**
     * Read an int in little endian byte order from the given
     * DataInput stream using the given byte buffer.
     * 
     * @param di DataInput stream to read from.
     * @param b  the byte buffer to read in (must be at least 4 bytes long).
     * @return The value read.
     * @throws IOException  if a i/o error occured.
     * @throws EOFException if the file ends prematurely
     */
    @Throws(IOException::class)
    private fun readLeInt(di: DataInput, b: ByteArray): Int {
        di.readFully(b, 0, 4)
        return (((b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8))
            or (((b[2].toInt() and 0xff) or ((b[3].toInt() and 0xff) shl 8)) shl 16))
    }

    @Throws(IOException::class)
    private fun readLeInt(pfd: ParcelFileDescriptor?, b: ByteArray): Int {
        PfdHelper.readFully(pfd!!, b, 0, 4) //di.readFully(b, 0, 4);
        return (((b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8))
            or (((b[2].toInt() and 0xff) or ((b[3].toInt() and 0xff) shl 8)) shl 16))
    }


    /**
     * Read an unsigned short in little endian byte order from the given
     * byte buffer at the given offset.
     * 
     * @param b   the byte array to read from.
     * @param off the offset to read from.
     * @return The value read.
     */
    private fun readLeShort(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    }

    /**
     * Read an int in little endian byte order from the given
     * byte buffer at the given offset.
     * 
     * @param b   the byte array to read from.
     * @param off the offset to read from.
     * @return The value read.
     */
    private fun readLeInt(b: ByteArray, off: Int): Int {
        return (((b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8))
            or (((b[off + 2].toInt() and 0xff) or ((b[off + 3].toInt() and 0xff) shl 8)) shl 16))
    }


    /**
     * Read the central directory of a zip file and fill the entries
     * array.  This is called exactly once when first needed. It is called
     * while holding the lock on `raf`.
     * 
     * @throws IOException  if a i/o error occured.
     * @throws ZipException if the central directory is malformed
     */
    @Throws(ZipException::class, IOException::class)
    private fun readEntries() {
        /* Search for the End Of Central Directory.  When a zip comment is
         * present the directory may start earlier.
         * FIXME: This searches the whole file in a very slow manner if the
         * file isn't a zip file.
         */
        //long pos = raf.length() - ENDHDR;
        var pos: Long = PfdHelper.length(pfd) - ZipConstants.Companion.ENDHDR
        val ebs = ByteArray(ZipConstants.Companion.CENHDR)

        do {
            if (pos < 0) throw ZipException("central directory not found, probably not a zip file: " + name)
            //raf.seek(pos--);
            seek(pfd, pos--)
        } while (readLeInt(pfd, ebs) != ZipConstants.Companion.ENDSIG)

        if (PfdHelper.skipBytes(
                pfd,
                ZipConstants.Companion.ENDTOT - ZipConstants.Companion.ENDNRD
            ) !== ZipConstants.Companion.ENDTOT - ZipConstants.Companion.ENDNRD
        ) throw EOFException(name)
        //int count = readLeShort(raf, ebs);
        val count = readLeShort(pfd, ebs)
        if (PfdHelper.skipBytes(
                pfd,
                ZipConstants.Companion.ENDOFF - ZipConstants.Companion.ENDSIZ
            ) !== ZipConstants.Companion.ENDOFF - ZipConstants.Companion.ENDSIZ
        ) throw EOFException(name)
        val centralOffset = readLeInt(pfd, ebs)

        entries = HashMap<String?, AndroidZipEntry?>(count + count / 2)
        //raf.seek(centralOffset);
        seek(pfd, centralOffset.toLong())

        var buffer = ByteArray(16)
        for (i in 0..<count) {
            //raf.readFully(ebs);
            PfdHelper.readFully(pfd, ebs)
            if (readLeInt(
                    ebs,
                    0
                ) != ZipConstants.Companion.CENSIG
            ) throw ZipException("Wrong Central Directory signature: " + name)

            val method = readLeShort(ebs, ZipConstants.Companion.CENHOW)
            val dostime = readLeInt(ebs, ZipConstants.Companion.CENTIM)
            val crc = readLeInt(ebs, ZipConstants.Companion.CENCRC)
            val csize = readLeInt(ebs, ZipConstants.Companion.CENSIZ)
            val size = readLeInt(ebs, ZipConstants.Companion.CENLEN)
            val nameLen = readLeShort(ebs, ZipConstants.Companion.CENNAM)
            val extraLen = readLeShort(ebs, ZipConstants.Companion.CENEXT)
            val commentLen = readLeShort(ebs, ZipConstants.Companion.CENCOM)

            val offset = readLeInt(ebs, ZipConstants.Companion.CENOFF)

            val needBuffer = max(nameLen, commentLen)
            if (buffer.size < needBuffer) buffer = ByteArray(needBuffer)

            PfdHelper.readFully(pfd, buffer, 0, nameLen)
            val name = String(buffer, 0, nameLen)

            val entry = AndroidZipEntry(name, nameLen)
            entry.setMethod(method)
            entry.setCrc(crc.toLong() and 0xffffffffL)
            entry.setSize(size.toLong() and 0xffffffffL)
            entry.setCompressedSize(csize.toLong() and 0xffffffffL)
            entry.time = dostime.toLong()
            if (extraLen > 0) {
                val extra = ByteArray(extraLen)
                PfdHelper.readFully(pfd, extra)
                entry.setExtra(extra)
            }
            if (commentLen > 0) {
                PfdHelper.readFully(pfd, buffer, 0, commentLen)
                entry.setComment(String(buffer, 0, commentLen))
            }
            entry.offset = offset

            //ZipEntryHelper.setOffset(entry,offset);

            //entry. = offset;
            entries!!.put(name, entry)
        }
    }

    /**
     * Closes the AndroidZipFile.  This also closes all input streams given by
     * this class.  After this is called, no further method should be
     * called.
     * 
     * @throws IOException if a i/o error occured.
     */
    @Throws(IOException::class)
    fun close() {
        synchronized(pfd) {
            closed = true
            entries = null
            pfd.close()
        }
    }

    /**
     * Calls the `close()` method when this AndroidZipFile has not yet
     * been explicitly closed.
     */
    @Throws(IOException::class)
    protected fun finalize() {
        if (!closed && pfd != null) close()
    }

    /**
     * Returns an enumeration of all Zip entries in this Zip file.
     */
    fun entries(): Enumeration<AndroidZipEntry?>? {
        try {
            return ZipEntryEnumeration(getEntries().values.iterator())
        } catch (ioe: IOException) {
            return null
        }
    }

    /**
     * Checks that the AndroidZipFile is still open and reads entries when necessary.
     * 
     * @throws IllegalStateException when the AndroidZipFile has already been closed.
     * @throws java,                 IOEexception          when the entries could not be read.
     */
    @Throws(IOException::class)
    private fun getEntries(): HashMap<String?, AndroidZipEntry?> {
        synchronized(pfd) {
            check(!closed) { "AndroidZipFile has closed: " + name }
            if (entries == null) readEntries()
            return entries!!
        }
    }

    /**
     * Searches for a zip entry in this archive with the given name.
     * 
     * @param name name. May contain directory components separated by
     * slashes ('/').
     * @return the zip entry, or null if no entry with that name exists.
     */
    fun getEntry(name: String?): AndroidZipEntry? {
        try {
            val entries = getEntries()
            val entry = entries.get(name)
            return if (entry != null) entry.clone() as AndroidZipEntry else null
        } catch (ioe: IOException) {
            return null
        }
    }


    //access should be protected by synchronized(raf)
    private val locBuf = ByteArray(ZipConstants.Companion.LOCHDR)

    /**
     * Checks, if the local header of the entry at index i matches the
     * central directory, and returns the offset to the data.
     * 
     * @param entry to check.
     * @return the start offset of the (compressed) data.
     * @throws IOException  if a i/o error occured.
     * @throws ZipException if the local header doesn't match the
     * central directory header
     */
    @Throws(IOException::class)
    private fun checkLocalHeader(entry: AndroidZipEntry): Long {
        synchronized(pfd) {
            seek(pfd, entry.offset.toLong())
            PfdHelper.readFully(pfd, locBuf)

            if (readLeInt(
                    locBuf,
                    0
                ) != ZipConstants.Companion.LOCSIG
            ) throw ZipException("Wrong Local header signature: " + name)

            if (entry.getMethod() != readLeShort(
                    locBuf,
                    ZipConstants.Companion.LOCHOW
                )
            ) throw ZipException("Compression method mismatch: " + name)

            if (entry.nameLen != readLeShort(
                    locBuf,
                    ZipConstants.Companion.LOCNAM
                )
            ) throw ZipException("file name length mismatch: " + name)

            val extraLen = entry.nameLen + readLeShort(locBuf, ZipConstants.Companion.LOCEXT)
            return (entry.offset + ZipConstants.Companion.LOCHDR + extraLen).toLong()
        }
    }

    /**
     * Creates an input stream reading the given zip entry as
     * uncompressed data.  Normally zip entry should be an entry
     * returned by getEntry() or entries().
     * 
     * @param entry the entry to create an InputStream for.
     * @return the input stream.
     * @throws IOException  if a i/o error occured.
     * @throws ZipException if the Zip archive is malformed.
     */
    @Throws(IOException::class)
    fun getInputStream(entry: AndroidZipEntry): InputStream {
        val entries = getEntries()
        val name = entry.name
        val zipEntry = entries.get(name)
        if (zipEntry == null) throw java.util.NoSuchElementException(name)

        val start = checkLocalHeader(zipEntry)
        val method = zipEntry.getMethod()
        val `is`: InputStream =
            BufferedInputStream(PartialInputStream(pfd, start, zipEntry.getCompressedSize()))
        when (method) {
            ZipOutputStream.STORED -> return `is`
            ZipOutputStream.DEFLATED -> return InflaterInputStream(`is`, Inflater(true))
            else -> throw ZipException("Unknown compression method " + method)
        }
    }

    /**
     * Returns the number of entries in this zip file.
     */
    fun size(): Int {
        try {
            return getEntries().size
        } catch (ioe: IOException) {
            return 0
        }
    }

    private class ZipEntryEnumeration(private val elements: MutableIterator<AndroidZipEntry?>) :
        Enumeration<AndroidZipEntry?> {
        override fun hasMoreElements(): Boolean {
            return elements.hasNext()
        }

        override fun nextElement(): AndroidZipEntry {
            /* We return a clone, just to be safe that the user doesn't
             * change the entry.
             */
            return (elements.next())!!.clone() as AndroidZipEntry
        }
    }

    private class PartialInputStream(pfd: ParcelFileDescriptor, start: Long, len: Long) :
        InputStream() {
        private val pfd: ParcelFileDescriptor
        var filepos: Long
        var end: Long

        init {
            this.pfd = pfd
            filepos = start
            end = start + len
        }

        override fun available(): Int {
            val amount = end - filepos
            if (amount > Int.Companion.MAX_VALUE) return Int.Companion.MAX_VALUE
            return amount.toInt()
        }

        @Throws(IOException::class)
        override fun read(): Int {
            if (filepos == end) return -1
            synchronized(pfd) {
                seek(pfd, filepos++)
                return PfdHelper.read(pfd)
            }
        }

        @Throws(IOException::class)
        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            var len = len
            if (len > end - filepos) {
                len = (end - filepos).toInt()
                if (len == 0) return -1
            }
            synchronized(pfd) {
                seek(pfd, filepos)
                val count: Int = PfdHelper.read(pfd, b, off, len)
                if (count > 0) filepos += len.toLong()
                return count
            }
        }

        override fun skip(amount: Long): Long {
            var amount = amount
            require(amount >= 0)
            if (amount > end - filepos) amount = end - filepos
            filepos += amount
            return amount
        }
    }

    companion object {
        /**
         * Mode flag to open a zip file for reading.
         */
        const val OPEN_READ: Int = 0x1

        /**
         * Mode flag to delete a zip file after reading.
         */
        const val OPEN_DELETE: Int = 0x4
    }
}
