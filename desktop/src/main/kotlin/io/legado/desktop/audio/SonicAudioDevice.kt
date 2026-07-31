package io.legado.desktop.audio

import javazoom.jl.player.JavaSoundAudioDevice

/**
 * 在 jlayer 输出层插入 [Sonic] 变速 (保音高)。
 *
 * jlayer 解码后经 `writeImpl(short[], offs, len)` 送 PCM 到 SourceDataLine,
 * 这里把 PCM 喂给 Sonic 处理后再交给父类写出; [speed] = 1 时直通, 无额外拷贝。
 */
class SonicAudioDevice : JavaSoundAudioDevice() {

    /** 播放速率 (1f = 原速); 可在播放中随时修改, 下一次 writeImpl 生效 */
    @Volatile
    var speed: Float = 1f
        set(value) {
            field = SonicAudioDevice.coerceSpeed(value)
        }

    private var sonic: Sonic? = null
    private var sonicChannels = 0
    private var sonicSampleRate = 0

    /** Sonic 输入暂存 (offs != 0 时用); 输出暂存 [outBuf] */
    private var inBuf = ShortArray(0)
    private var outBuf = ShortArray(0)

    /** seek/换章请求丢弃 Sonic 残留; 用标志位而非加锁, 避免调用方阻塞在声卡写入上 */
    @Volatile
    private var resetRequested = false

    /** seek / 换章后调用, 丢弃 Sonic 内残留样本, 避免串音 */
    fun resetSonic() {
        resetRequested = true
    }

    override fun writeImpl(samples: ShortArray, offs: Int, len: Int) {
        if (resetRequested) {
            resetRequested = false
            sonic = null
            sonicChannels = 0
            sonicSampleRate = 0
        }
        val curSpeed = speed
        if (curSpeed in (1f - SPEED_EPS)..(1f + SPEED_EPS)) {
            // 原速直通; 若刚从变速切回, 先把 Sonic 里剩下的样本吐出去
            if (sonic != null) {
                drainSonic(flush = true)
                sonic = null
            }
            super.writeImpl(samples, offs, len)
            return
        }
        val stream = obtainSonic() ?: run {
            super.writeImpl(samples, offs, len)
            return
        }
        stream.setSpeed(curSpeed)
        val channels = sonicChannels
        val numFrames = len / channels
        if (numFrames <= 0) return
        val input = if (offs == 0) {
            samples
        } else {
            if (inBuf.size < len) inBuf = ShortArray(len)
            System.arraycopy(samples, offs, inBuf, 0, len)
            inBuf
        }
        stream.writeShortToStream(input, numFrames)
        drainSonic(flush = false)
    }

    /** 播放结束前把 Sonic 缓冲清空再 drain 声卡 (父类 flushImpl = source.drain) */
    override fun flushImpl() {
        if (sonic != null && !resetRequested) {
            drainSonic(flush = true)
        }
        super.flushImpl()
    }

    /** 取出 Sonic 输出并写给父类; [flush] 为 true 时先 flushStream 补齐尾部样本 */
    private fun drainSonic(flush: Boolean) {
        val stream = sonic ?: return
        val channels = sonicChannels
        if (flush) {
            stream.flushStream()
        }
        while (true) {
            val available = stream.samplesAvailable()
            if (available <= 0) break
            val needed = available * channels
            if (outBuf.size < needed) outBuf = ShortArray(needed)
            val frames = stream.readShortFromStream(outBuf, available)
            if (frames <= 0) break
            super.writeImpl(outBuf, 0, frames * channels)
        }
    }

    /** 按解码头的采样率/声道数惰性创建 Sonic; 参数变化时重建 */
    private fun obtainSonic(): Sonic? {
        val decoder = getDecoder() ?: return null
        val sampleRate = decoder.outputFrequency
        val channels = decoder.outputChannels
        if (sampleRate <= 0 || channels <= 0) return null
        val cur = sonic
        if (cur != null && sonicSampleRate == sampleRate && sonicChannels == channels) {
            return cur
        }
        val created = Sonic(sampleRate, channels)
        sonic = created
        sonicSampleRate = sampleRate
        sonicChannels = channels
        return created
    }

    companion object {
        /** 对齐 app 端倍速滑杆 (0..30 → 0.0x..3.0x); 下限避免 Sonic changeSpeed 死循环 */
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 3f

        /** 与 Sonic.processStreamInput 的原速判定一致 */
        private const val SPEED_EPS = 0.00001f

        /** 速率钳制, 供调用方同步自己的 speed 记录 */
        fun coerceSpeed(rate: Float): Float = rate.coerceIn(MIN_SPEED, MAX_SPEED)
    }
}
