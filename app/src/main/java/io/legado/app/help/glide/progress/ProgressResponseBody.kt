package io.legado.app.help.glide.progress

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

class ProgressResponseBody internal constructor(
    private val url: String,
    private val internalProgressListener: InternalProgressListener,
    private val responseBody: ResponseBody
) : ResponseBody() {
    private var bufferedSource: BufferedSource? = null
    override fun contentType(): MediaType? {
        return responseBody.contentType()
    }

    override fun contentLength(): Long {
        return responseBody.contentLength()
    }

    override fun source(): BufferedSource {
        if (bufferedSource == null) {
            bufferedSource = source(responseBody.source()).buffer()
        }
        return bufferedSource!!
    }

    private fun source(source: Source): Source {
        return object : ForwardingSource(source) {
            var totalBytesRead: Long = 0
            var lastTotalBytesRead: Long = 0
            var lastPostTime: Long = 0

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                totalBytesRead += if (bytesRead == -1L) 0 else bytesRead
                val currentTime = System.currentTimeMillis()
                if (bytesRead == -1L || (currentTime - lastPostTime > 100 && lastTotalBytesRead != totalBytesRead)) {
                    lastTotalBytesRead = totalBytesRead
                    lastPostTime = currentTime
                    mainThreadHandler.post {
                        internalProgressListener.onProgress(
                            url, totalBytesRead, contentLength(), bytesRead == -1L
                        )
                    }
                }
                return bytesRead
            }
        }
    }

    interface InternalProgressListener {
        fun onProgress(url: String, bytesRead: Long, totalBytes: Long, isComplete: Boolean)
    }

    companion object {
        private val mainThreadHandler = Handler(Looper.getMainLooper())
    }

}