package io.legado.desktop.audio

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.desktop.help.DesktopCommandRunner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * 桌面端系统音量控制 (跨平台支持: Windows / macOS / Linux):
 * 视频手势右半竖滑调"系统音量" (对照 app 端 AudioManager.setStreamVolume(STREAM_MUSIC))。
 *
 * # 各平台机制 (均零外部第三方依赖):
 * - **Windows**: JNA 直调 WASAPI COM 接口 (IAudioEndpointVolume)，微秒级调用
 * - **macOS**: 系统内置 `osascript` 命令读写音量与静音状态
 * - **Linux**: 顺序探测 PipeWire (`wpctl`) -> PulseAudio (`pactl`) -> ALSA (`amixer`)
 *
 * # 失败策略
 * 失败安全返回 null / false，手势层与播放器安全回退，UI 不崩。
 */
internal object DesktopSystemVolume {

    // ==================== 常量 (Windows WASAPI) ====================

    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val E_RENDER = 0 // EDataFlow.eRender
    private const val E_MULTIMEDIA = 1 // ERole.eMultimedia
    private const val S_OK = 0
    private const val S_FALSE = 1

    /** COM 调用超时 (异常挂起时兜底, 正常微秒级返回)。 */
    private const val CALL_TIMEOUT_MS = 2000L
    private const val CMD_TIMEOUT_MS = 2000L

    // IUnknown
    private const val SLOT_RELEASE = 2

    // IMMDeviceEnumerator
    private const val SLOT_GET_DEFAULT_AUDIO_ENDPOINT = 4

    // IMMDevice
    private const val SLOT_ACTIVATE = 3

    // IAudioEndpointVolume
    private const val SLOT_SET_MASTER_VOLUME_SCALAR = 7
    private const val SLOT_GET_MASTER_VOLUME_SCALAR = 9
    private const val SLOT_SET_MUTE = 14
    private const val SLOT_GET_MUTE = 15

    private val CLSID_MMDEVICE_ENUMERATOR = guidBytes("BCDE0395-E52F-467C-8E3D-C4579291692E")
    private val IID_IMMDEVICE_ENUMERATOR = guidBytes("A95664D2-9614-4F35-A746-DE8DB63617E6")
    private val IID_IAUDIO_ENDPOINT_VOLUME = guidBytes("5CDF2C82-841E-4546-9722-0CF74078229A")

    // ==================== 状态 (仅 Windows executor 线程访问; 标记跨线程) ====================

    @Volatile
    private var comReady = false

    /** 初始化失败后不再重试 (防拖动中每帧刷日志)。 */
    @Volatile
    private var initFailed = false

    private var enumerator: Pointer? = null
    private var endpoint: Pointer? = null
    private var endpointVolume: Pointer? = null

    /** 单线程 executor: 所有 Windows COM 调用固定在此线程 (对象线程亲和, STA)。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-wasapi").apply { isDaemon = true }
    }

    // ==================== 公开 API (全 runCatching, 失败 null / 静默) ====================

    /** 当前系统音量 0..1; 失败/不可用返回 null (调用方回落 mediamp 音量)。 */
    fun getVolume(): Float? = runCatching {
        when {
            Platform.isWindows() -> submit { endpointVolume()?.let { readScalar(it) } }
            Platform.isMac() -> readMacVolume()
            Platform.isLinux() -> readLinuxVolume()
            else -> null
        }
    }.getOrNull()

    /**
     * 设置系统音量 0..1 (内部 coerce)。
     * @return true=写入成功; false=不可用/写入失败, 调用方可回落 mediamp 音量
     */
    fun setVolume(v: Float): Boolean = runCatching {
        val clamped = v.coerceIn(0f, 1f)
        when {
            Platform.isWindows() -> submit {
                endpointVolume()?.let { writeScalar(it, clamped) } ?: false
            } ?: false

            Platform.isMac() -> writeMacVolume(clamped)
            Platform.isLinux() -> writeLinuxVolume(clamped)
            else -> false
        }
    }.getOrDefault(false)

    /** 静音状态; 不可用返回 null。 */
    fun getMute(): Boolean? = runCatching {
        when {
            Platform.isWindows() -> submit { endpointVolume()?.let { readMute(it) } }
            Platform.isMac() -> readMacMute()
            Platform.isLinux() -> readLinuxMute()
            else -> null
        }
    }.getOrNull()

    /** 设置静音 (失败仅日志)。 */
    fun setMute(mute: Boolean) {
        runCatching {
            when {
                Platform.isWindows() -> submit {
                    endpointVolume()?.let { writeMute(it, mute) }
                    null
                }

                Platform.isMac() -> writeMacMute(mute)
                Platform.isLinux() -> writeLinuxMute(mute)
            }
        }
    }

    /** 释放 COM 引用链 (幂等; 下次调用自动重建; 初始化失败锁不重置)。 */
    fun release() {
        if (!Platform.isWindows()) return
        submit {
            listOfNotNull(endpointVolume, endpoint, enumerator).forEach {
                runCatching { vtbl(it, SLOT_RELEASE) }
            }
            endpointVolume = null
            endpoint = null
            enumerator = null
            null
        }
    }

    // ==================== macOS 实现 (系统内置 osascript) ====================

    private fun readMacVolume(): Float? = runCatching {
        val res = DesktopCommandRunner.run(
            listOf("osascript", "-e", "output volume of (get volume settings)"),
            CMD_TIMEOUT_MS,
        )
        if (!res.isOk) return@runCatching null
        val value = res.output.trim().toIntOrNull() ?: return@runCatching null
        (value / 100f).coerceIn(0f, 1f)
    }.getOrNull()

    private fun writeMacVolume(v: Float): Boolean = runCatching {
        val percent = (v * 100).toInt().coerceIn(0, 100)
        val res = DesktopCommandRunner.run(
            listOf("osascript", "-e", "set volume output volume $percent"),
            CMD_TIMEOUT_MS,
        )
        res.isOk
    }.getOrDefault(false)

    private fun readMacMute(): Boolean? = runCatching {
        val res = DesktopCommandRunner.run(
            listOf("osascript", "-e", "output muted of (get volume settings)"),
            CMD_TIMEOUT_MS,
        )
        if (!res.isOk) return@runCatching null
        res.output.trim().equals("true", ignoreCase = true)
    }.getOrNull()

    private fun writeMacMute(mute: Boolean) {
        DesktopCommandRunner.run(
            listOf("osascript", "-e", "set volume output muted $mute"),
            CMD_TIMEOUT_MS,
        )
    }

    // ==================== Linux 实现 (wpctl -> pactl -> amixer) ====================

    private fun readLinuxVolume(): Float? = runCatching {
        // 1. PipeWire wpctl
        val wpRes = DesktopCommandRunner.run(
            listOf("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"),
            CMD_TIMEOUT_MS,
        )
        if (wpRes.isOk) {
            val matched = Regex("""Volume:\s*([0-9.]+)""").find(wpRes.output)?.groupValues?.get(1)
            matched?.toFloatOrNull()?.let { return@runCatching it.coerceIn(0f, 1f) }
        }

        // 2. PulseAudio pactl
        val paRes = DesktopCommandRunner.run(
            listOf("pactl", "get-sink-volume", "@DEFAULT_SINK@"),
            CMD_TIMEOUT_MS,
        )
        if (paRes.isOk) {
            val matched = Regex("""(\d+)%""").find(paRes.output)?.groupValues?.get(1)
            matched?.toIntOrNull()?.let { return@runCatching (it / 100f).coerceIn(0f, 1f) }
        }

        // 3. ALSA amixer
        val alsaRes = DesktopCommandRunner.run(
            listOf("amixer", "sget", "Master"),
            CMD_TIMEOUT_MS,
        )
        if (alsaRes.isOk) {
            val matched = Regex("""\[(\d+)%\]""").find(alsaRes.output)?.groupValues?.get(1)
            matched?.toIntOrNull()?.let { return@runCatching (it / 100f).coerceIn(0f, 1f) }
        }

        null
    }.getOrNull()

    private fun writeLinuxVolume(v: Float): Boolean = runCatching {
        val percent = (v * 100).toInt().coerceIn(0, 100)

        // 1. PipeWire wpctl
        val wpRes = DesktopCommandRunner.run(
            listOf("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "$percent%"),
            CMD_TIMEOUT_MS,
        )
        if (wpRes.isOk) return@runCatching true

        // 2. PulseAudio pactl
        val paRes = DesktopCommandRunner.run(
            listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$percent%"),
            CMD_TIMEOUT_MS,
        )
        if (paRes.isOk) return@runCatching true

        // 3. ALSA amixer
        val alsaRes = DesktopCommandRunner.run(
            listOf("amixer", "sset", "Master", "$percent%"),
            CMD_TIMEOUT_MS,
        )
        alsaRes.isOk
    }.getOrDefault(false)

    private fun readLinuxMute(): Boolean? = runCatching {
        // 1. PipeWire wpctl
        val wpRes = DesktopCommandRunner.run(
            listOf("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"),
            CMD_TIMEOUT_MS,
        )
        if (wpRes.isOk) return@runCatching wpRes.output.contains("[MUTED]")

        // 2. PulseAudio pactl
        val paRes = DesktopCommandRunner.run(
            listOf("pactl", "get-sink-mute", "@DEFAULT_SINK@"),
            CMD_TIMEOUT_MS,
        )
        if (paRes.isOk) return@runCatching paRes.output.contains("yes", ignoreCase = true)

        // 3. ALSA amixer
        val alsaRes = DesktopCommandRunner.run(
            listOf("amixer", "sget", "Master"),
            CMD_TIMEOUT_MS,
        )
        if (alsaRes.isOk) return@runCatching alsaRes.output.contains("[off]")

        null
    }.getOrNull()

    private fun writeLinuxMute(mute: Boolean) {
        val wpRes = DesktopCommandRunner.run(
            listOf("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", if (mute) "1" else "0"),
            CMD_TIMEOUT_MS,
        )
        if (wpRes.isOk) return

        val paRes = DesktopCommandRunner.run(
            listOf("pactl", "set-sink-mute", "@DEFAULT_SINK@", if (mute) "1" else "0"),
            CMD_TIMEOUT_MS,
        )
        if (paRes.isOk) return

        DesktopCommandRunner.run(
            listOf("amixer", "sset", "Master", if (mute) "mute" else "unmute"),
            CMD_TIMEOUT_MS,
        )
    }

    // ==================== Windows COM 内部实现 (仅在 executor 线程执行) ====================

    /** 同步提交到 executor 并等待结果 (超时返回 null)。 */
    private fun <T> submit(block: () -> T?): T? {
        if (!Platform.isWindows()) return null
        return runCatching {
            executor.submit(Callable<T?> { runCatching { block() }.getOrNull() })
                .get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /** 懒初始化: CoCreateInstance → EnumAudioEndpoints → Activate, 缓存引用链。 */
    private fun endpointVolume(): Pointer? {
        endpointVolume?.let { return it }
        if (initFailed) return null
        ensureCom()
        return runCatching {
            val enumRef = PointerByReference()
            var hr = ole32("CoCreateInstance")
                .invokeInt(
                    arrayOf(
                        CLSID_MMDEVICE_ENUMERATOR,
                        null,
                        CLSCTX_INPROC_SERVER,
                        IID_IMMDEVICE_ENUMERATOR,
                        enumRef
                    )
                )
            if (hr != S_OK || enumRef.value == null) {
                throw IllegalStateException("CoCreateInstance(MMDeviceEnumerator) hr=$hr")
            }
            enumerator = enumRef.value
            try {
                // GetDefaultAudioEndpoint 直接返回默认渲染设备 (IMMDevice)。
                // 之前误用 EnumAudioEndpoints: 它返回的是 IMMDeviceCollection**, 拿集合当
                // IMMDevice 调 Activate (槽 3) 实际命中 GetCount, hr=0 且不写 out, 音量读写
                // 全部静默失效 (见类注释)
                val deviceRef = PointerByReference()
                hr = vtbl(
                    enumRef.value,
                    SLOT_GET_DEFAULT_AUDIO_ENDPOINT,
                    E_RENDER,
                    E_MULTIMEDIA,
                    deviceRef
                )
                if (hr != S_OK || deviceRef.value == null) {
                    throw IllegalStateException("GetDefaultAudioEndpoint hr=$hr")
                }
                endpoint = deviceRef.value
                val volumeRef = PointerByReference()
                hr = vtbl(
                    deviceRef.value,
                    SLOT_ACTIVATE,
                    IID_IAUDIO_ENDPOINT_VOLUME,
                    CLSCTX_INPROC_SERVER,
                    null,
                    volumeRef
                )
                if (hr != S_OK || volumeRef.value == null) {
                    throw IllegalStateException("IMMDevice.Activate(IAudioEndpointVolume) hr=$hr")
                }
                endpointVolume = volumeRef.value
                volumeRef.value
            } catch (e: Throwable) {
                // 失败释放已取得的引用 (枚举器 + 可能已激活的端点), 锁死重试
                runCatching { endpoint?.let { vtbl(it, SLOT_RELEASE) } }
                runCatching { vtbl(enumRef.value, SLOT_RELEASE) }
                endpointVolume = null
                endpoint = null
                enumerator = null
                initFailed = true
                throw e
            }
        }.onFailure {
            initFailed = true
            AppLog.put("WASAPI 系统音量初始化失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun ensureCom() {
        if (comReady) return
        val hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED).toInt()
        if (hr == S_OK || hr == S_FALSE) {
            comReady = true
        } else {
            AppLog.put("WASAPI CoInitializeEx 失败 hr=$hr")
        }
    }

    private fun readScalar(ep: Pointer): Float? {
        val value = FloatByReference()
        val hr = vtbl(ep, SLOT_GET_MASTER_VOLUME_SCALAR, value)
        if (hr != S_OK) {
            AppLog.putDebug("WASAPI GetMasterVolumeLevelScalar 失败 hr=$hr")
            return null
        }
        return value.value.coerceIn(0f, 1f)
    }

    private fun writeScalar(ep: Pointer, v: Float): Boolean {
        // 第 2 参 LPCGUID 事件上下文 = null (不触发系统音量浮层, 对照 app 端 flags=0)
        val hr = vtbl(ep, SLOT_SET_MASTER_VOLUME_SCALAR, v, null)
        if (hr != S_OK) {
            AppLog.putDebug("WASAPI SetMasterVolumeLevelScalar 失败 hr=$hr")
            return false
        }
        return true
    }

    private fun readMute(ep: Pointer): Boolean? {
        val value = IntByReference()
        val hr = vtbl(ep, SLOT_GET_MUTE, value)
        if (hr != S_OK) {
            AppLog.putDebug("WASAPI GetMute 失败 hr=$hr")
            return null
        }
        return value.value != 0
    }

    private fun writeMute(ep: Pointer, mute: Boolean): Boolean {
        val hr = vtbl(ep, SLOT_SET_MUTE, if (mute) 1 else 0, null)
        if (hr != S_OK) {
            AppLog.putDebug("WASAPI SetMute 失败 hr=$hr")
            return false
        }
        return true
    }

    // ==================== COM 基础设施 (照 DesktopSmtc.vtbl / guidBytes) ====================

    /** 按 vtable 序号调用 COM 方法 (COM = __stdcall, 与既有代码一致)。 */
    private fun vtbl(target: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = target.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method, Function.ALT_CONVENTION)
            .invokeInt(arrayOf(target, *args))
    }

    private fun ole32(name: String): Function =
        Function.getFunction("ole32", name, Function.ALT_CONVENTION)

    /** "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX" → 16 字节 COM GUID 内存布局 (Data1/2/3 小端)。 */
    private fun guidBytes(s: String): Memory {
        val g = s.replace("-", "")
        val mem = Memory(16)
        // Data1 可为 8 位 hex 且可能 > 0x7FFFFFFF (如 BCDE0395), toInt(16) 溢出抛异常,
        // 用 parseUnsignedInt (2026-08 桌面回归实崩修复)
        mem.setInt(0, Integer.parseUnsignedInt(g.substring(0, 8), 16))
        mem.setShort(4, g.substring(8, 12).toInt(16).toShort())
        mem.setShort(6, g.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            mem.setByte(8L + i, g.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
        return mem
    }
}
