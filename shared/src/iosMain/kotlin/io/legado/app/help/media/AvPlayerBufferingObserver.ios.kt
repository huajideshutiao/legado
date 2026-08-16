@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.media

import io.legado.app.platform.kvo.LegadoKeyValueObservingProtocol
import kotlinx.cinterop.COpaquePointer
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusUnknown
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.timeControlStatus
import platform.Foundation.NSKeyValueObservingOptionInitial
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject

/**
 * AVPlayer 缓冲状态 KVO 观察器 (事件驱动, 替代轮询):
 * - `item.status == Unknown` → 加载中 (覆盖"链接就绪→首帧"窗口)
 * - `player.timeControlStatus == waitingToPlayAtSpecifiedRate` → 等待起播 (播放中卡顿)
 *
 * 两个 keyPath 任一变化即回调最新缓冲态; `NSKeyValueObservingOptionInitial` 使注册时
 * 立即回调一次, 渲染层无需初始猜测。KVO 回调线程不保证, 调用方自行切线程/写 StateFlow。
 */
class AvPlayerBufferingObserver(
    private val player: AVPlayer,
    private val item: AVPlayerItem,
    private val onBufferingChange: (Boolean) -> Unit,
) : NSObject(), LegadoKeyValueObservingProtocol {

    private var observing = false

    fun start() {
        if (observing) return
        observing = true
        player.addObserver(
            observer = this,
            forKeyPath = PLAYER_TIME_CONTROL_KEY,
            options = NSKeyValueObservingOptionInitial or NSKeyValueObservingOptionNew,
            context = null,
        )
        item.addObserver(
            observer = this,
            forKeyPath = ITEM_STATUS_KEY,
            options = NSKeyValueObservingOptionInitial or NSKeyValueObservingOptionNew,
            context = null,
        )
    }

    fun dispose() {
        if (!observing) return
        observing = false
        player.removeObserver(this, forKeyPath = PLAYER_TIME_CONTROL_KEY)
        item.removeObserver(this, forKeyPath = ITEM_STATUS_KEY)
    }

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        if (!observing) return
        val watched = (keyPath == PLAYER_TIME_CONTROL_KEY && ofObject === player) ||
            (keyPath == ITEM_STATUS_KEY && ofObject === item)
        if (!watched) return
        onBufferingChange(isBuffering())
    }

    private fun isBuffering(): Boolean {
        if (item.status == AVPlayerItemStatusUnknown) return true
        return player.timeControlStatus() == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
    }
}

// K/N 限制: ObjC 子类 (NSObject) 的 companion 不允许字段, const 常量放文件顶层
private const val PLAYER_TIME_CONTROL_KEY = "timeControlStatus"
private const val ITEM_STATUS_KEY = "status"
