package io.legado.app.help.media

import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem

/**
 * Windows klib 校验专用 stub (源根 src/iosWindowsCheckMain, 仅非 mac 主机的
 * iosArm64Main/iosSimulatorArm64Main 编译挂载): 真实实现在
 * AvPlayerBufferingObserver.ios.kt / AvPlayerItemStatusObserver.ios.kt, 依赖
 * nskeyvalueobserving cinterop 的 LegadoKeyValueObservingProtocol (需 Xcode sysroot),
 * Windows 上由本 stub 顶替, 使 TTS/音频/视频播放器消费方通过 klib 语法/签名校验。
 *
 * 构造参数名与真实实现一致 (调用方用具名参数); 本代码不会在真实设备执行 (mac 上
 * 编译真实实现, 本文件不挂载), 方法体为空。
 */
class AvPlayerBufferingObserver(
    @Suppress("unused") private val player: AVPlayer,
    @Suppress("unused") private val item: AVPlayerItem,
    @Suppress("unused") private val onBufferingChange: (Boolean) -> Unit,
) {
    fun start() {}
    fun dispose() {}
}

class AvPlayerItemStatusObserver(
    @Suppress("unused") private val item: AVPlayerItem,
    @Suppress("unused") private val onReady: () -> Unit,
    @Suppress("unused") private val onFailed: (String) -> Unit,
) {
    fun start() {}
    fun dispose() {}
}
