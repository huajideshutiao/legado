package io.legado.app.lib.webdav

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import okhttp3.Request

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
        val end = minOf(this.size - 1, offset + size - 1)
        val range = "bytes=$offset-$end"

        try {
            val response = webDav.webDavClient.newCall(
                Request.Builder()
                    .url(webDav.httpUrl!!)
                    .header("Range", range)
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }

            val body = response.body ?: throw ErrnoException("onRead", OsConstants.EIO)
            var bytesRead = 0
            val inputStream = body.byteStream()
            while (bytesRead < size) {
                val read = inputStream.read(data, bytesRead, size - bytesRead)
                if (read == -1) break
                bytesRead += read
            }
            return bytesRead
        } catch (e: Exception) {
            throw ErrnoException("onRead", OsConstants.EIO)
        }
    }

    override fun onRelease() {
        handlerThread.quitSafely()
    }
}
