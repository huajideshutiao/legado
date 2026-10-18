@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model

import io.legado.app.model.audio.AudioBandMeter
import io.legado.app.model.audio.AudioEnergyMeter
import io.legado.app.model.audio.AudioLoudnessMeter
import io.legado.app.model.audio.AudioSpectrum
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.asset
import platform.AVFoundation.audioMix
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMItemCount
import platform.MediaToolbox.MTAudioProcessingTapCallbacks
import platform.MediaToolbox.MTAudioProcessingTapCreate
import platform.MediaToolbox.MTAudioProcessingTapFlags
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapGetStorage
import platform.MediaToolbox.MTAudioProcessingTapRef
import platform.MediaToolbox.MTAudioProcessingTapRefVar
import platform.MediaToolbox.kMTAudioProcessingTapCallbacksVersion_0
import platform.MediaToolbox.kMTAudioProcessingTapCreationFlag_PostEffects

/** CoreAudio 的 PCM 格式标志位 (CoreAudioTypes.h: kAudioFormatFlagIsFloat / kAudioFormatFlagIsNonInterleaved) */
private const val FORMAT_FLAG_IS_FLOAT = 1u
private const val FORMAT_FLAG_IS_NON_INTERLEAVED = 32u

/**
 * iOS 端的音频能量采集: 在播放中的 PCM 上挂一个 AudioProcessingTap。
 *
 * 响度与能量归一化全部复用共享层 ([AudioLoudnessMeter] + [AudioEnergyMeter]),
 * 与安卓的 PCM 拦截、桌面端从播放器滤镜取电平同口径。
 *
 * 平台约束 (Apple 说明): MTAudioProcessingTap 只对逐帧可取的音源生效, HLS 一类分段流拿不到
 * 连续 PCM; 这类音源挂不上 tap, 表现为辉光不随音乐起伏。
 */
object IosAudioEnergyTap {

    /** tap 回调要用的运行态, 经 clientInfo 的 StableRef 传进 C 回调 (C 回调不能捕获 Kotlin 对象) */
    internal class TapState {
        /** 本 tap 自己的 StableRef: 终结回调用它释放, 不依赖类级单槽 */
        var handle: StableRef<TapState>? = null
        var loudness: AudioLoudnessMeter? = null
        var spectrum: AudioSpectrum? = null
        val energyMeter = AudioEnergyMeter()
        val bandMeter = AudioBandMeter()

        var sampleRate: Double = 44100.0
        var channels: Int = 2
        var bytesPerSample: Int = 4
        var isFloat: Boolean = true
        var isNonInterleaved: Boolean = false
    }

    private var attachedItem: AVPlayerItem? = null

    /** 把 tap 挂到播放项的音频轨上; 已挂过同一项则跳过 */
    fun attach(item: AVPlayerItem) {
        if (attachedItem === item) return
        detach()
        val track = item.asset.tracksWithMediaType(AVMediaTypeAudio)
            .firstOrNull() as? AVAssetTrack ?: return
        val handle = StableRef.create(TapState())
        val created: MTAudioProcessingTapRef? = memScoped {
            val callbacks = alloc<MTAudioProcessingTapCallbacks>()
            callbacks.version = kMTAudioProcessingTapCallbacksVersion_0
            callbacks.clientInfo = handle.asCPointer()
            callbacks.init = staticCFunction(::onTapInit)
            callbacks.finalize = staticCFunction(::onTapFinalize)
            callbacks.prepare = staticCFunction(::onTapPrepare)
            callbacks.unprepare = staticCFunction(::onTapUnprepare)
            callbacks.process = staticCFunction(::onTapProcess)
            val tapOut = alloc<MTAudioProcessingTapRefVar>()
            val status = MTAudioProcessingTapCreate(
                null,
                callbacks.ptr,
                kMTAudioProcessingTapCreationFlag_PostEffects,
                tapOut.ptr,
            )
            if (status != 0) null else tapOut.value
        }
        if (created == null) {
            handle.dispose()
            return
        }
        handle.get().handle = handle
        val params = AVMutableAudioMixInputParameters()
        params.setTrackID(track.trackID)
        params.setAudioTapProcessor(created)
        // Create 规则: 调用方持有 +1。参数对象已经 retain, 这里释放自己那份,
        // tap 的生命周期此后由音频混音持有 —— 混音释放时才会回调 finalize
        CFRelease(created)
        val mix = AVMutableAudioMix.audioMix()
        mix.setInputParameters(listOf(params))
        item.audioMix = mix
        attachedItem = item
    }

    /** 摘掉 tap (换源/销毁时调用); 运行态由 tap 的终结回调释放 */
    fun detach() {
        attachedItem?.let { it.audioMix = null }
        attachedItem = null
        AudioPlayShared.audioEnergy.value = 0f
    }
}

/** 从 tap 的 storage 取回运行态 */
private fun tapStateOf(tap: MTAudioProcessingTapRef?): IosAudioEnergyTap.TapState? {
    if (tap == null) return null
    val storage = MTAudioProcessingTapGetStorage(tap) ?: return null
    return storage.asStableRef<IosAudioEnergyTap.TapState>().get()
}

private fun onTapInit(
    tap: MTAudioProcessingTapRef?,
    clientInfo: COpaquePointer?,
    tapStorageOut: CPointer<COpaquePointerVar>?,
) {
    tapStorageOut?.pointed?.value = clientInfo
}

private fun onTapFinalize(tap: MTAudioProcessingTapRef?) {
    val state = tapStateOf(tap)
    state?.loudness?.reset()
    // 终结的是这个 tap 自己的运行态, 不碰其他 tap (每个 tap 自持自己的 StableRef)
    state?.handle?.dispose()
    state?.handle = null
    AudioPlayShared.audioEnergy.value = 0f
}

private fun onTapPrepare(
    tap: MTAudioProcessingTapRef?,
    maxFrames: CMItemCount,
    processingFormat: CPointer<AudioStreamBasicDescription>?,
) {
    val state = tapStateOf(tap) ?: return
    val format = processingFormat?.pointed ?: return
    val sampleRate = format.mSampleRate
    state.sampleRate = if (sampleRate > 0.0) sampleRate else 44100.0
    state.channels = format.mChannelsPerFrame.toInt().coerceAtLeast(1)
    val bits = format.mBitsPerChannel.toInt().coerceAtLeast(16)
    state.bytesPerSample = (bits / 8).coerceAtLeast(2)
    state.isFloat = (format.mFormatFlags and FORMAT_FLAG_IS_FLOAT) != 0u
    state.isNonInterleaved = (format.mFormatFlags and FORMAT_FLAG_IS_NON_INTERLEAVED) != 0u
    state.loudness = AudioLoudnessMeter(state.sampleRate.toInt())
    state.spectrum = AudioSpectrum(state.sampleRate.toInt())
    state.energyMeter.reset()
    state.bandMeter.reset()
}

private fun onTapUnprepare(tap: MTAudioProcessingTapRef?) {
    tapStateOf(tap)?.loudness?.reset()
    tapStateOf(tap)?.spectrum?.reset()
}

private fun onTapProcess(
    tap: MTAudioProcessingTapRef?,
    numberFrames: CMItemCount,
    flags: MTAudioProcessingTapFlags,
    bufferListInOut: CPointer<AudioBufferList>?,
    numberFramesOut: CPointer<LongVar>?,
    flagsOut: CPointer<UIntVar>?,
) {
    if (tap == null || bufferListInOut == null) return
    // 先向源取音频: 不取就等于把声音吞掉
    val status = MTAudioProcessingTapGetSourceAudio(
        tap,
        numberFrames,
        bufferListInOut,
        flagsOut,
        null,
        numberFramesOut,
    )
    if (status != 0) return
    val frames = (numberFramesOut?.pointed?.value ?: numberFrames).toInt()
    if (frames <= 0) return
    val state = tapStateOf(tap) ?: return
    val loudness = state.loudness ?: return
    val spectrum = state.spectrum ?: return

    analyzePcm(state, loudness, spectrum, bufferListInOut, frames)
    val dtMs = frames.toDouble() / state.sampleRate * 1000.0
    val frame = spectrum.analyze() ?: return
    AudioPlayShared.audioEnergy.value =
        state.energyMeter.pushFrame(dtMs, loudness.momentaryLkfs(), frame.lowBand)
    // 各段独立起伏: 边缘光效按频段分开驱动
    // (必须复制: push 复用同一个数组实例, StateFlow 按引用相等会判为没变而不发出)
    AudioPlayShared.audioBands.value = state.bandMeter.push(dtMs, frame.bands).copyOf()
}

/** 按 ASBD 描述的采样格式把多声道混成单声道后喂给响度计与频谱 */
private fun analyzePcm(
    state: IosAudioEnergyTap.TapState,
    loudness: AudioLoudnessMeter,
    spectrum: AudioSpectrum,
    bufferList: CPointer<AudioBufferList>,
    frames: Int,
) {
    val list = bufferList.pointed
    val bufferCount = list.mNumberBuffers.toInt().coerceAtLeast(1)
    val channels = state.channels
    if (state.isNonInterleaved && bufferCount > 1) {
        // 平面格式: 每个声道一个 AudioBuffer
        for (frame in 0 until frames) {
            var mono = 0.0
            var used = 0
            for (ch in 0 until minOf(bufferCount, channels)) {
                val data = list.mBuffers[ch].mData ?: continue
                mono += readSample(data, frame, state)
                used++
            }
            if (used > 0) {
                val value = mono / used
                loudness.pushSample(value)
                spectrum.pushSample(value)
            }
        }
    } else {
        // 交错格式: 所有声道在同一个 AudioBuffer 里
        val data = list.mBuffers[0].mData ?: return
        for (frame in 0 until frames) {
            var mono = 0.0
            for (ch in 0 until channels) {
                mono += readSample(data, frame * channels + ch, state)
            }
            val value = mono / channels
            loudness.pushSample(value)
            spectrum.pushSample(value)
        }
    }
}

/** 取第 [index] 个样本并归一到 -1.0~1.0 */
private fun readSample(data: COpaquePointer, index: Int, state: IosAudioEnergyTap.TapState): Double {
    if (state.isFloat && state.bytesPerSample == 4) {
        return data.reinterpret<FloatVar>()[index].toDouble()
    }
    return data.reinterpret<ShortVar>()[index] / 32768.0
}
