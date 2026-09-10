@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.loadedTimeRanges
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSValue

/**
 * 获取 AVPlayerItem 已缓冲到的最大时间戳 (毫秒)。
 *
 * `AVPlayerItem.loadedTimeRanges` 是 `NSValue`(装 `CMTimeRange`) 数组, 逐段取
 * `CMTimeRangeGetEnd` 的最大值; seek 后会出现多段不连续区间, 取最大 end 与
 * media3/ExoPlayer `bufferedPosition` 口径一致。
 */
fun AVPlayerItem.maxLoadedTimeRangeEndMs(): Long {
    val ranges = loadedTimeRanges ?: return 0L
    var maxEndMs = 0L
    for (value in ranges) {
        val range = (value as? NSValue)?.CMTimeRangeValue ?: continue
        val seconds = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
        if (seconds.isNaN() || seconds.isInfinite()) continue
        val ms = (seconds * 1000.0).toLong()
        if (ms > maxEndMs) maxEndMs = ms
    }
    return maxEndMs
}
