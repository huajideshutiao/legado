package io.legado.app.ui.book.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDescriptorDataSource
import androidx.media3.datasource.TransferListener
import java.io.FileNotFoundException

/**
 * 外部视频的数据源: 每次打开都重新取一份 fd 副本交给 [FileDescriptorDataSource],
 * 副本随数据源关闭, 持有的原件不受影响。
 */
@UnstableApi
internal class ExternalVideoDataSource(private val url: String) : DataSource {

    private var delegate: FileDescriptorDataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val fd = ExternalVideoHandle.dup(url) ?: throw DataSourceException(
            FileNotFoundException("外部视频读句柄不可用"),
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
        val created = FileDescriptorDataSource(fd, 0L, C.LENGTH_UNSET.toLong())
        listeners.forEach(created::addTransferListener)
        delegate = created
        return created.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireDelegate().read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }

    private fun requireDelegate(): FileDescriptorDataSource = delegate ?: throw DataSourceException(
        IllegalStateException("外部视频数据源尚未打开"),
        PlaybackException.ERROR_CODE_INVALID_STATE,
    )
}
