package io.legado.app.model.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 短窗频谱: 低频段能量 + 多频段幅度。
 *
 * 窗长 1024、hop 512，周期 Hann 窗。一次 FFT 同时给出:
 * - [Frame.lowBand]: 20–320Hz 的幅度和, 与 MilkDrop/Butterchurn 的 bass 段一致 ——
 *   歌词辉光取这条连续能量曲线, 不做离散的起音检测 (离散事件会漏拍、卡顿)。
 * - [Frame.bands]: 从 20Hz 到接近 Nyquist 的对数分布多频段幅度均值, 供边缘光效按频段
 *   分别驱动 (各段各自起伏, 不是一条总能量的复制)。
 *
 * 频段取段内幅度均值而不是求和: 对数分布的相邻段 bin 数差几倍, 求和会让高频段永远压过
 * 低频段。
 */
class AudioSpectrum(private val sampleRate: Int) {

    companion object {
        /** 多频段分析的段数 (对数分布) */
        const val BAND_COUNT = 12

        private const val FFT_SIZE = 1024
        private const val HALF = FFT_SIZE / 2
        private const val FFT_LOG2 = 10
        private const val RING_SIZE = FFT_SIZE

        /** 频段下界 / 上界 (Hz); 上界另受 Nyquist 限制 */
        private const val BAND_MIN_HZ = 20.0
        private const val BAND_MAX_HZ = 16000.0

        private fun reverseBits(value: Int): Int {
            var v = value
            var result = 0
            repeat(FFT_LOG2) {
                result = (result shl 1) or (v and 1)
                v = v shr 1
            }
            return result
        }
    }

    /**
     * 一次分析的结果。
     *
     * 同一个 [AudioSpectrum] 复用同一实例: 内容在下一次分析时被覆盖, 调用方只能当场读,
     * 不要保留引用 (这样每次分析不产生新分配)。
     */
    class Frame internal constructor() {
        /** 20–320Hz 幅度和 */
        var lowBand: Double = 0.0
            internal set

        /** 对数分布各段幅度均值 (长度 = [BAND_COUNT]) */
        val bands: DoubleArray = DoubleArray(BAND_COUNT)
    }

    private val ring = DoubleArray(RING_SIZE)
    private var writeIndex = 0
    private var filled = 0

    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) {
        0.5 - 0.5 * cos(2.0 * PI * it / FFT_SIZE)
    }
    private val reversed = IntArray(FFT_SIZE) { reverseBits(it) }
    /**
     * 旋转因子表: 第 len 级蝶形的第 k 个因子 = exp(-2πi · k·(HALF/len) / FFT_SIZE),
     * 下标按 `k * (HALF / halfLen)` 取。每级每对只查表, 不再靠上一次结果乘下一个因子
     * (累乘会把浮点误差逐级放大到整谱)。
     */
    private val twiddleRe = DoubleArray(HALF) { cos(-2.0 * PI * it / FFT_SIZE) }
    private val twiddleIm = DoubleArray(HALF) { sin(-2.0 * PI * it / FFT_SIZE) }
    private val binHz: Double
    private val bassStart: Int
    private val bassEnd: Int

    /** 各段对应的 bin 区间 [start, end) */
    private val bandStart = IntArray(BAND_COUNT)
    private val bandEnd = IntArray(BAND_COUNT)

    private val frame = Frame()

    init {
        binHz = sampleRate.toDouble() / FFT_SIZE
        bassStart = (20.0 / binHz).toInt().coerceIn(1, HALF - 1)
        bassEnd = (320.0 / binHz).toInt().coerceIn(bassStart + 1, HALF)

        // 对数分布边界: 20Hz → min(16kHz, 0.45 * 采样率)
        val nyquistLimit = sampleRate * 0.45
        val top = BAND_MAX_HZ.coerceAtMost(nyquistLimit)
        val ratio = top / BAND_MIN_HZ
        var previous = bassStart
        for (i in 0 until BAND_COUNT) {
            val hz = BAND_MIN_HZ * ratio.pow((i + 1).toDouble() / BAND_COUNT)
            val bin = (hz / binHz).toInt().coerceIn(previous + 1, HALF)
            bandStart[i] = previous
            bandEnd[i] = bin
            previous = bin
        }
    }

    fun reset() {
        ring.fill(0.0)
        writeIndex = 0
        filled = 0
        frame.lowBand = 0.0
        frame.bands.fill(0.0)
    }

    fun pushSample(mono: Double) {
        ring[writeIndex] = mono
        writeIndex = (writeIndex + 1) % RING_SIZE
        if (filled < RING_SIZE) filled++
    }

    /** 窗口未满返回 null */
    fun analyze(): Frame? {
        if (filled < FFT_SIZE) return null
        var index = (writeIndex - FFT_SIZE + RING_SIZE) % RING_SIZE
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[index] * window[i]
            im[i] = 0.0
            index = (index + 1) % RING_SIZE
        }
        fft()

        var lowEnergy = 0.0
        for (k in bassStart until bassEnd) {
            lowEnergy += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        for (i in 0 until BAND_COUNT) {
            var sum = 0.0
            for (k in bandStart[i] until bandEnd[i]) {
                sum += sqrt(re[k] * re[k] + im[k] * im[k])
            }
            frame.bands[i] = sum / (bandEnd[i] - bandStart[i])
        }
        frame.lowBand = lowEnergy
        return frame
    }

    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val j = reversed[i]
            if (j > i) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val halfLen = length / 2
            val stride = HALF / halfLen
            var start = 0
            while (start < FFT_SIZE) {
                for (k in 0 until halfLen) {
                    val tw = k * stride
                    val curRe = twiddleRe[tw]
                    val curIm = twiddleIm[tw]
                    val a = start + k
                    val b = a + halfLen
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                }
                start += length
            }
            length *= 2
        }
    }
}
