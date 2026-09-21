package io.legado.app.lib.epublib.base

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.legado.app.utils.asIOException
import java.io.EOFException
import java.io.IOException

/**
 * 读取ParcelFileDescriptor的工具类
 */
@Suppress("unused")
object PfdHelper {
    /**
     * 读取基本类型的buffer
     */
    private val readBuffer = ByteArray(8)

    @Throws(IOException::class)
    fun seek(pfd: ParcelFileDescriptor, pos: Long) {
        try {
            Os.lseek(pfd.getFileDescriptor(), pos, OsConstants.SEEK_SET)
        } catch (e: ErrnoException) {
            throw e.asIOException()
        }
    }

    @Throws(IOException::class)
    fun getFilePointer(pfd: ParcelFileDescriptor): Long {
        try {
            return Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_CUR)
        } catch (e: ErrnoException) {
            throw e.asIOException()
        }
    }

    @Throws(IOException::class)
    fun length(pfd: ParcelFileDescriptor): Long {
        try {
            return Os.fstat(pfd.getFileDescriptor()).st_size //android.system.Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_END);
        } catch (e: ErrnoException) {
            throw e.asIOException()
        }
    }

    @Throws(IOException::class)
    private fun readBytes(pfd: ParcelFileDescriptor, b: ByteArray?, off: Int, len: Int): Int {
        try {
            return Os.read(pfd.getFileDescriptor(), b, off, len)
        } catch (e: ErrnoException) {
            throw e.asIOException()
        }
    }

    @Throws(IOException::class)
    fun read(pfd: ParcelFileDescriptor): Int {
        return if (read(pfd, readBuffer, 0, 1) != -1) readBuffer[0].toInt() and 0xff else -1
    }

    @Throws(IOException::class)
    fun read(pfd: ParcelFileDescriptor, b: ByteArray?, off: Int, len: Int): Int {
        return readBytes(pfd, b, off, len)
    }

    @Throws(IOException::class)
    fun read(pfd: ParcelFileDescriptor, b: ByteArray): Int {
        return readBytes(pfd, b, 0, b.size)
    }

    @Throws(IOException::class)
    fun readFully(pfd: ParcelFileDescriptor, b: ByteArray) {
        readFully(pfd, b, 0, b.size)
    }

    @Throws(IOException::class)
    fun readFully(pfd: ParcelFileDescriptor, b: ByteArray?, off: Int, len: Int) {
        var n = 0
        do {
            val count = read(pfd, b, off + n, len - n)
            if (count < 0) throw EOFException()
            n += count
        } while (n < len)
    }


    @Throws(IOException::class)
    fun skipBytes(pfd: ParcelFileDescriptor, n: Int): Int {
        val pos: Long
        val len: Long
        var newpos: Long

        if (n <= 0) {
            return 0
        }
        pos = getFilePointer(pfd)
        len = length(pfd)
        newpos = pos + n
        if (newpos > len) {
            newpos = len
        }
        seek(pfd, newpos)

        /* return the actual number of bytes skipped */
        return (newpos - pos).toInt()
    }
}
