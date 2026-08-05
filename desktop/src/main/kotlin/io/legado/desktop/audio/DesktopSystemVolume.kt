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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * Windows 系统音量 (WASAPI 渲染端点, IAudioEndpointVolume):
 * 视频手势右半竖滑调"系统音量" (对照 app 端 AudioManager.setStreamVolume(STREAM_MUSIC))。
 *
 * # 机制 (MSDN: IAudioEndpointVolume)
 * - CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_INPROC_SERVER,
 *   IID_IMMDeviceEnumerator, out) → IMMDeviceEnumerator.EnumAudioEndpoints(eRender,
 *   DEVICE_STATE_ACTIVE, out) → IMMDevice.Activate(IID_IAudioEndpointVolume,
 *   CLSCTX_INPROC_SERVER, null, out) → 读/写主音量 (0..1 标量)。
 * - 全部走 JNA 手写 vtable (照 DesktopSmtc.vtbl 模式: Function.getFunction +
 *   ALT_CONVENTION invokeInt); GUID 按 16 字节 COM 内存布局 (Data1/2/3 小端)。
 *
 * # 槽位核对 (Windows SDK mmdeviceapi.h / endpointvolume.h, 与 NAudio/Wine 交叉验证)
 * - IMMDeviceEnumerator (A95664D2-...): IUnknown 0/1/2, EnumAudioEndpoints=3,
 *   GetDefaultAudioEndpoint=4, GetDevice=5
 * - IMMDevice (D666063F-...): Activate=3, OpenPropertyStore=4, GetId=5, GetState=6
 * - IAudioEndpointVolume (5CDF2C82-...): RegisterControlChangeNotify=3,
 *   UnregisterControlChangeNotify=4, GetChannelCount=5, SetMasterVolumeLevel=6,
 *   SetMasterVolumeLevelScalar=7, GetMasterVolumeLevel=8, GetMasterVolumeLevelScalar=9,
 *   SetChannelVolumeLevel=10, SetChannelVolumeLevelScalar=11, GetChannelVolumeLevel=12,
 *   GetChannelVolumeLevelScalar=13, SetMute=14, GetMute=15
 *
 * # 线程模型
 * COM 对象线程亲和 (STA): 所有调用固定跑在单线程 executor (照 DesktopSmtc 模式),
 * 公开 API 同步等待结果 (COM 调用微秒级, 不卡交互); 全部 runCatching, 失败返回 null /
 * 仅日志, 调用方回落 mediamp 音量, UI 不崩。
 *
 * # 失败策略
 * 初始化失败 (无音频设备/权限/COM 异常) 记一次日志后不再重试 (避免拖动中每帧刷日志);
 * 该会话内手势音量写系统失败静默, 读回落 mediamp 音量, 重启进程后自动恢复。
 */
internal object DesktopSystemVolume {

    // ==================== 常量 ====================

    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val E_RENDER = 0 // EDataFlow.eRender
    private const val DEVICE_STATE_ACTIVE = 0x1
    private const val S_OK = 0
    private const val S_FALSE = 1

    /** COM 调用超时 (异常挂起时兜底, 正常微秒级返回)。 */
    private const val CALL_TIMEOUT_MS = 2000L

    // IUnknown
    private const val SLOT_RELEASE = 2

    // IMMDeviceEnumerator
    private const val SLOT_ENUM_AUDIO_ENDPOINTS = 3

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

    // ==================== 状态 (仅 executor 线程访问; 标记跨线程) ====================

    @Volatile
    private var comReady = false

    /** 初始化失败后不再重试 (防拖动中每帧刷日志), 见类注释。 */
    @Volatile
    private var initFailed = false

    private var enumerator: Pointer? = null
    private var endpoint: Pointer? = null
    private var endpointVolume: Pointer? = null

    /** 单线程 executor: 所有 COM 调用固定在此线程 (对象线程亲和, STA)。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-wasapi").apply { isDaemon = true }
    }

    // ==================== 公开 API (全 runCatching, 失败 null / 静默) ====================

    /** 当前系统音量 0..1; 失败/不可用返回 null (调用方回落 mediamp 音量)。 */
    fun getVolume(): Float? = submit { endpointVolume()?.let { readScalar(it) } }

    /** 设置系统音量 0..1 (内部 coerce; 失败仅日志)。 */
    fun setVolume(v: Float) {
        submit {
            endpointVolume()?.let { writeScalar(it, v.coerceIn(0f, 1f)) }
            null
        }
    }

    /** 静音状态; 不可用返回 null。 */
    fun getMute(): Boolean? = submit { endpointVolume()?.let { readMute(it) } }

    /** 设置静音 (失败仅日志)。 */
    fun setMute(mute: Boolean) {
        submit {
            endpointVolume()?.let { writeMute(it, mute) }
            null
        }
    }

    /** 释放 COM 引用链 (幂等; 下次调用自动重建; 初始化失败锁不重置, 见类注释)。 */
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

    // ==================== 内部实现 (仅在 executor 线程执行) ====================

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
                val deviceRef = PointerByReference()
                hr = vtbl(
                    enumRef.value,
                    SLOT_ENUM_AUDIO_ENDPOINTS,
                    E_RENDER,
                    DEVICE_STATE_ACTIVE,
                    deviceRef
                )
                if (hr != S_OK || deviceRef.value == null) {
                    throw IllegalStateException("EnumAudioEndpoints hr=$hr")
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
        mem.setInt(0, g.substring(0, 8).toInt(16))
        mem.setShort(4, g.substring(8, 12).toInt(16).toShort())
        mem.setShort(6, g.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            mem.setByte(8L + i, g.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
        }
        return mem
    }
}
