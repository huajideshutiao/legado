package io.legado.desktop.media

import org.openani.mediamp.mpv.MPVHandle

/**
 * 获取 mpv 解复用缓存的终点时间戳 (毫秒)。
 *
 * 读 mpv `demuxer-cache-time` = 解复用缓存里最后一帧的时间戳, 即绝对缓存终点时间点 (秒)。
 * 直接换算毫秒, 严禁重复累加当前播放位置。
 * 若抛异常、返回 NaN / Infinite 或 <= 0.0, 统一返回 0L。
 */
fun MPVHandle.bufferedEndPositionMsOrZero(): Long {
    val seconds = runCatching { getPropertyDouble("demuxer-cache-time") }
        .getOrDefault(0.0)
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return 0L
    return (seconds * 1000.0).toLong()
}
