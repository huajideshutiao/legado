@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.media

import io.legado.app.platform.kvo.LegadoKeyValueObservingProtocol
import kotlinx.cinterop.COpaquePointer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.Foundation.NSKeyValueObservingOptionInitial
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject

/** AVPlayerItem.status 的可释放 KVO 观察器。 */
class AvPlayerItemStatusObserver(
    private val item: AVPlayerItem,
    private val onReady: () -> Unit,
    private val onFailed: (String) -> Unit,
) : NSObject(), LegadoKeyValueObservingProtocol {

    private var observing = false

    fun start() {
        if (observing) return
        observing = true
        item.addObserver(
            observer = this,
            forKeyPath = STATUS_KEY,
            options = NSKeyValueObservingOptionInitial or NSKeyValueObservingOptionNew,
            context = null,
        )
    }

    fun dispose() {
        if (!observing) return
        observing = false
        item.removeObserver(this, forKeyPath = STATUS_KEY)
    }

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        if (!observing || keyPath != STATUS_KEY || ofObject !== item) return
        when (item.status) {
            AVPlayerItemStatusReadyToPlay -> {
                dispose()
                onReady()
            }

            AVPlayerItemStatusFailed -> {
                val message = item.error?.localizedDescription ?: "AVPlayerItem 加载失败"
                dispose()
                onFailed(message)
            }
        }
    }

    private companion object {
        private const val STATUS_KEY = "status"
    }
}
