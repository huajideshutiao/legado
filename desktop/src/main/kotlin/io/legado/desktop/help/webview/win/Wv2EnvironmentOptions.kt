package io.legado.desktop.help.webview.win

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** IUnknown: 任何 COM 对象都必须应答的 IID。 */
private val IID_IUNKNOWN = Guid.GUID("00000000-0000-0000-C000-000000000046")

/** ICoreWebView2EnvironmentOptions (IDL uuid 2fde08a8-1e9a-4766-8c05-95a9ceb9d1c5)。 */
private val IID_ENVIRONMENT_OPTIONS = Guid.GUID("2FDE08A8-1E9A-4766-8C05-95A9CEB9D1C5")

/**
 * EnvironmentOptions 的 get/put 槽位形状统一为 (this, pointer) → HRESULT, 一个回调接口即可。
 * 与 [ComSupport] 的 ComQueryInterfaceCb 等同风格, 声明在文件顶层供 JNA 代理。
 */
internal interface ComArgCb : StdCallCallback {
    fun callback(self: Pointer, arg: Pointer?): Int
}

/**
 * Java 侧实现的 `ICoreWebView2EnvironmentOptions` COM 对象。
 *
 * 环境创建走 `CreateWebViewEnvironmentWithOptionsInternal`, 其第 4 参是 `IUnknown* environmentOptions`;
 * 本仓原先传 null, 于是拿不到 `AdditionalBrowserArguments`, mixed content 无从放行
 * (WebView2 无 `mixedContentMode` 设置项, 唯一等价途径是环境级
 * `--allow-running-insecure-content` 浏览器参数)。
 *
 * 只实现 ICoreWebView2EnvironmentOptions (IDL 声明的 10 槽) 的 get/put,
 * 其中运行时实际会读的两个 get 必须返回 CoTaskMemAlloc 分配的宽串:
 * - `get_AdditionalBrowserArguments` → [additionalBrowserArguments]
 * - `get_TargetCompatibleBrowserVersion` → [targetCompatibleBrowserVersion]
 *   (官方 Rust 绑定注释: 该属性为 NULL 时 `CreateCoreWebView2EnvironmentWithOptions` 会返回
 *   E_INVALIDARG, 故必须给值)
 *
 * 生命周期同 [ComHandler]: native 持有期间由 [live] 保活, 创建方用完调 [disown]。
 *
 * vtable 序号 (0/1/2 恒为 IUnknown) 来自 WebView2.idl 中 ICoreWebView2EnvironmentOptions
 * 的声明顺序, 与官方 WebView2.h 生成的 Go 绑定 `ICoreWebView2EnvironmentOptionsVtbl` 一致:
 * https://raw.githubusercontent.com/beyluta/WinWidgets/8e70f673c179338d983e7c9e1d471ed86a105e48/lib/WebView2/WebView2.idl
 * https://github.com/unix-world/go-webview2-wails/blob/599accbd4b53/pkg/webview2/ICoreWebView2EnvironmentOptions.go
 */
internal class Wv2EnvironmentOptions(
    private val additionalBrowserArguments: String?,
    private val targetCompatibleBrowserVersion: String?,
) {

    private val refCount = AtomicInteger(1)

    private val queryInterface = object : ComQueryInterfaceCb {
        override fun callback(self: Pointer, riid: Pointer?, ppv: Pointer?): Int {
            ppv ?: return E_POINTER
            // 只应答 IUnknown 与本接口; 其他 IID 必须回 E_NOINTERFACE,
            // 否则调用方可能拿着本对象当别的接口用 (vtable 越界 → 崩溃)
            val requested = riid?.let { runCatching { Guid.GUID(it) }.getOrNull() }
            if (requested != IID_IUNKNOWN && requested != IID_ENVIRONMENT_OPTIONS) {
                ppv.setPointer(0, Pointer.NULL)
                return E_NOINTERFACE
            }
            ppv.setPointer(0, self)
            refCount.incrementAndGet()
            return S_OK
        }
    }

    private val addRef = object : ComRefCb {
        override fun callback(self: Pointer): Int = refCount.incrementAndGet()
    }

    private val release = object : ComRefCb {
        override fun callback(self: Pointer): Int = decRef()
    }

    /** get_AdditionalBrowserArguments (槽 3)。 */
    private val getAdditionalArgs = object : ComArgCb {
        override fun callback(self: Pointer, arg: Pointer?): Int {
            arg ?: return E_POINTER
            arg.setPointer(0, coTaskWide(additionalBrowserArguments))
            return S_OK
        }
    }

    /**
     * get_Language (槽 5): 无语言偏好。与官方 WebView2EnvironmentOptions.h 一致,
     * 未设时返回 NULL (而非空串) —— 空串可能被当成一个空语言值。
     */
    private val getLanguage = object : ComArgCb {
        override fun callback(self: Pointer, arg: Pointer?): Int {
            arg ?: return E_POINTER
            arg.setPointer(0, Pointer.NULL)
            return S_OK
        }
    }

    /** get_TargetCompatibleBrowserVersion (槽 7): 必须非 NULL, 否则 E_INVALIDARG。 */
    private val getTargetVersion = object : ComArgCb {
        override fun callback(self: Pointer, arg: Pointer?): Int {
            arg ?: return E_POINTER
            arg.setPointer(0, coTaskWide(targetCompatibleBrowserVersion))
            return S_OK
        }
    }

    /** get_AllowSingleSignOnUsingOSPrimaryAccount (槽 9): FALSE。 */
    private val getSso = object : ComArgCb {
        override fun callback(self: Pointer, arg: Pointer?): Int {
            arg?.setInt(0, 0)
            return S_OK
        }
    }

    /** 所有 put_* (槽 4/6/8/10): 运行时不会写, 接受并忽略。 */
    private val putNoop = object : ComArgCb {
        override fun callback(self: Pointer, arg: Pointer?): Int = S_OK
    }

    // 强引用: 回调蹦床被 GC 后 native 调用即崩
    private val methods: List<Callback> = listOf(
        queryInterface, addRef, release,
        getAdditionalArgs, putNoop,          // 3, 4
        getLanguage, putNoop,                // 5, 6
        getTargetVersion, putNoop,           // 7, 8
        getSso, putNoop,                     // 9, 10
    )

    private val vtable = Memory(methods.size * Native.POINTER_SIZE.toLong())
    private val instance = Memory(Native.POINTER_SIZE.toLong())

    init {
        val size = Native.POINTER_SIZE.toLong()
        methods.forEachIndexed { index, callback ->
            vtable.setPointer(index * size, CallbackReference.getFunctionPointer(callback))
        }
        instance.setPointer(0, vtable)
        live[Pointer.nativeValue(instance)] = this
    }

    val pointer: Pointer get() = instance

    /** 交出创建方那一份引用 (递给 runtime 之后调, native 会自行 AddRef)。 */
    fun disown() {
        decRef()
    }

    private fun decRef(): Int {
        val count = refCount.decrementAndGet()
        if (count <= 0) live.remove(Pointer.nativeValue(instance))
        return count.coerceAtLeast(0)
    }

    private companion object {
        const val E_POINTER = 0x80004003.toInt()
        const val E_NOINTERFACE = 0x80004002.toInt()

        val live = ConcurrentHashMap<Long, Wv2EnvironmentOptions>()

        /** CoTaskMemAlloc 一段宽串 (WebView2 出参约定: 调用方负责 CoTaskMemFree); null → NULL。 */
        fun coTaskWide(value: String?): Pointer {
            if (value == null) return Pointer.NULL
            val memory = Ole32.INSTANCE.CoTaskMemAlloc((value.length + 1) * 2L)
            if (memory != null && Pointer.nativeValue(memory) != 0L) {
                memory.setWideString(0, value)
                return memory
            }
            return Pointer.NULL
        }
    }
}
