package io.legado.app.lib.webdav

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.IOException

class WebDavPfdCallback(
    private val webDav: WebDav,
    private val size: Long,
    private val handlerThread: android.os.HandlerThread
) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long {
        return size
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= this.size) return 0

        try {
            val bytes = webDav.readRange(offset, size, this.size)
            if (bytes.isEmpty()) return 0
            System.arraycopy(bytes, 0, data, 0, minOf(bytes.size, size))
            return bytes.size
        } catch (e: IOException) {
            Log.w("WebDavPfdCallback", "Server does not support Range requests", e)
            throw ErrnoException("onRead: ${e.message}", OsConstants.EIO)
        } catch (e: ErrnoException) {
            throw e
        } catch (e: Exception) {
            Log.e("WebDavPfdCallback", "onRead error", e)
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        handlerThread.quitSafely()
    }
}
