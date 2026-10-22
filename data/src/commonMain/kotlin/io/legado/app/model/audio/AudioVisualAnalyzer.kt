package io.legado.app.model.audio

/**
 * 逐样本喂入的可视分析: 每 [HOP_FRAMES] 帧产出一帧能量与各频段幅度。
 *
 * 采样与分析必须按 hop 交替推进。管道一次给的缓冲往往几十毫秒 (1024~4096 帧), 若先把整块
 * 喂进环形窗口、再在块尾分析一次, 块内的瞬态 (鼓点、辅音) 会被整段吃掉; 若在块尾连算多次,
 * 每次看到的都是同一段末尾窗口, 读数完全不变。这里每个 hop 只喂 512 帧、只分析一次,
 * 于是每个 hop 看到的是它自己那 512 帧之后的新窗口。
 *
 * 四端的平台差异只在"怎么把 PCM 读成单声道样本", 分析口径统一走这一份实现。
 */
class AudioVisualAnalyzer(private val sampleRate: Int) {

    companion object {
        /** 分析步长: 512 帧 ≈ 11.6ms @44.1k */
        const val HOP_FRAMES = 512
    }

    /**
     * 播放倍速: 解码出来的样本按倍速更快或更慢地消耗, 一个 hop 覆盖的墙上时长要按它换算
     * (包络的时间常数是按墙上时间定的)。平台在每次缓冲开始时写入。
     */
    var playSpeed: Float = 1f

    private val loudness = AudioLoudnessMeter(sampleRate)
    private val spectrum = AudioSpectrum(sampleRate)
    private val energyMeter = AudioEnergyMeter()
    private val bandMeter = AudioPulseEnvelope(AudioSpectrum.BAND_COUNT)

    private var pendingFrames = 0

    /** 最近一个 hop 的整体能量 (0~1) */
    var energy: Float = 0f
        private set

    /** 最近一个 hop 的各频段幅度 (复用实例, 只在下次 [pushSample] 前有效); 首帧 FFT 之前为 null */
    var bands: FloatArray? = null
        private set

    /** 本次缓冲是否产出了至少一个 hop: 没有新 hop 时平台不该派发 */
    var hasFrame: Boolean = false
        private set

    fun reset() {
        loudness.reset()
        spectrum.reset()
        energyMeter.reset()
        bandMeter.reset()
        pendingFrames = 0
        energy = 0f
        bands = null
        hasFrame = false
    }

    /** 缓冲开始处调用: 只清"本次缓冲是否产出"的标记, 跨缓冲的 hop 进度要接着算 */
    fun beginBuffer() {
        hasFrame = false
    }

    /** 送入一个单声道样本 (-1.0~1.0); 喂满一个 hop 就分析一次 */
    fun pushSample(mono: Double) {
        loudness.pushSample(mono)
        spectrum.pushSample(mono)
        pendingFrames++
        if (pendingFrames < HOP_FRAMES) return
        pendingFrames = 0
        val dtMs = HOP_FRAMES.toDouble() / sampleRate * 1000.0 / playSpeed.coerceAtLeast(0.1f)
        val frame = spectrum.analyze()
        // 窗口未满时只有能量可推, 频段值要等首帧 FFT 出来
        energy = energyMeter.pushFrame(dtMs, loudness.momentaryLkfs(), frame?.lowBand ?: 0.0)
        if (frame != null) bands = bandMeter.push(dtMs, frame.bands)
        hasFrame = true
    }
}
