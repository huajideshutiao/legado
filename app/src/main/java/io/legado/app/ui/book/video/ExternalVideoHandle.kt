package io.legado.app.ui.book.video

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.net.toUri
import java.io.FileDescriptor

/**
 * 外部递来的 content:// 视频的读句柄: 在判定为视频直投时打开并持有。
 *
 * 播放时交出 [dup] 出来的副本, 播放器关闭的是副本。
 */
internal object ExternalVideoHandle {

    private var heldUri: String? = null
    private var held: ParcelFileDescriptor? = null

    /** 打开并持有该地址的读句柄; 同一地址重复投递时复用。 */
    fun hold(context: Context, url: String): Boolean {
        if (url == heldUri && held != null) return true
        release()
        val opened = runCatching {
            context.contentResolver.openFileDescriptor(url.toUri(), "r")
        }.getOrNull() ?: return false
        heldUri = url
        held = opened
        return true
    }

    /** 该地址当前是否持有可读句柄。 */
    fun holds(url: String): Boolean = url == heldUri && held != null

    /** 播放用的 fd 副本; 非持有地址返回 null。 */
    fun dup(url: String): FileDescriptor? {
        if (!holds(url)) return null
        return runCatching { Os.dup(held!!.fileDescriptor) }.getOrNull()
    }

    /** 释放持有的句柄。 */
    fun release() {
        runCatching { held?.close() }
        held = null
        heldUri = null
    }
}
