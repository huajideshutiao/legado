package io.legado.app.lib.cronet

import androidx.annotation.Keep
import okhttp3.RequestBody
import okio.BufferedSource
import okio.Pipe
import okio.buffer
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

/**
 * 用于上传大型文件
 *
 * @property body
 * @property executorService
 */
@Keep
class LargeBodyUploadProvider(
    private val body: RequestBody,
    private val executorService: ExecutorService
) : UploadDataProvider(), AutoCloseable {
    private var pipe = Pipe(BUFFER_SIZE.toLong())
    private var source: BufferedSource = pipe.source.buffer()

    @Volatile
    private var filled: Boolean = false
    override fun getLength(): Long {
        return body.contentLength()
    }

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        if (!filled) {
            fillBuffer()
        }
        check(byteBuffer.hasRemaining()) { "Cronet passed a buffer with no bytes remaining" }
        var read: Int
        var bytesRead = 0
        while (bytesRead == 0) {
            read = source.read(byteBuffer)
            if (read < 0) break // 读到 EOF 必须退出, 否则死循环
            bytesRead += read
        }
        // Cronet javadoc: onReadSucceeded 只在至少读到 1 字节时调用, EOF 不能回调 false
        if (bytesRead == 0) {
            if (getLength() < 0) {
                // chunked: 空读即最后一块, 回调 true 结束上传
                uploadDataSink.onReadSucceeded(true)
            } else {
                // 声明了确定长度却提前没数据, 必须报错, 否则假成功
                uploadDataSink.onReadError(IOException("body ended prematurely"))
            }
            return
        }
        uploadDataSink.onReadSucceeded(false)
    }

    @Synchronized
    private fun fillBuffer() {
        // filled 必须在 submit 前置位: rewind 同步回调 onRewindSucceeded 后 Cronet 立即调 read,
        // 若延后置位, read 会再触发一次 fillBuffer, 两个任务并发写同一根管子
        filled = true
        executorService.submit {
            try {
                // 不能 use{} 关闭 sink: Pipe 的 sink 一旦 close 即置 sinkClosed 且不可复位,
                // 之后 write 必抛 IllegalStateException; 写完 flush 即可, 释放交给 close()/pipe.cancel()
                val writeSink = pipe.sink.buffer()
                body.writeTo(writeSink)
                writeSink.flush()
            } catch (e: Throwable) {
                // ISE 等非 IOException (如写已关闭的 sink) 也不能被静默吞掉
                e.printStackTrace()
            }
        }
    }

    override fun rewind(uploadDataSink: UploadDataSink?) {
        // isOneShot 表示只能写一次, 可 rewind 的前提是 !isOneShot
        check(!body.isOneShot()) { "Okhttp RequestBody is oneShot" }
        // okio Pipe 的 sink 关闭后不可复位, rewind 靠 cancel 旧管 + 重建新管实现
        pipe.cancel()
        pipe = Pipe(BUFFER_SIZE.toLong())
        source = pipe.source.buffer()
        filled = false
        fillBuffer()
        // 缺该回调 Cronet 会永久等待
        uploadDataSink?.onRewindSucceeded()
    }

    override fun close() {
        // 唯一释放点: cancel 让阻塞在 pipe 读写上的 executor 任务立即结束, 不占线程
        pipe.cancel()
        super.close()
    }
}