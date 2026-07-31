package io.legado.desktop.help.webview.win

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary.StdCallCallback

/**
 * WebView2 所需的最小 COM 支撑层 (JNA 手写)。
 *
 * jna-platform 只提供 COM 基础设施 (Ole32/Guid), 不含 WebView2 接口包装, 故此处
 * 按 vtable 序号调用 ([vtbl]) —— 与同仓 `WindowsFileDialogs` 的 IFileDialog 直调同套路。
 * 区别是 WebView2 全异步, 还需**反向**实现 COM 接口 (完成/事件 handler), 由 [ComHandler]
 * 在堆上拼一张 vtable, 每项指向 JNA 回调蹦床。
 */

internal const val S_OK = 0

private val POINTER_SIZE = Native.POINTER_SIZE.toLong()

/**
 * 按 vtable 序号调用 COM 方法: 对象首字段是 vtable 指针, 第 [index] 项即目标函数,
 * 第一个实参固定为 this。ALT_CONVENTION = stdcall (x86 必需, x64 无副作用)。
 */
internal fun vtbl(target: Pointer, index: Int, vararg args: Any?): Int {
    val vtable = target.getPointer(0)
    val method = vtable.getPointer(index * POINTER_SIZE)
    return Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(arrayOf(target, *args))
}

/** IUnknown::Release, 所有 COM 对象通用 (序号 2)。 */
internal fun comRelease(target: Pointer?) {
    target ?: return
    runCatching { vtbl(target, 2) }
}

/** IUnknown::QueryInterface, 失败返回 null。 */
internal fun comQueryInterface(target: Pointer, iid: Guid.GUID): Pointer? {
    val out = PointerByReference()
    if (vtbl(target, 0, iid, out) != S_OK) return null
    return out.value
}

/** 宽字符串入参 (调用期间由局部变量持有防 GC)。 */
internal fun wide(value: String): Memory {
    val memory = Memory((value.length + 1) * 2L)
    memory.setWideString(0, value)
    return memory
}

/** 取 COM 返回的 LPWSTR 并 CoTaskMemFree (WebView2 的 get_XXX 出参都是 COM 分配的)。 */
internal fun takeWideString(ref: PointerByReference): String? {
    val raw = ref.value ?: return null
    return try {
        raw.getWideString(0)
    } finally {
        Ole32.INSTANCE.CoTaskMemFree(raw)
    }
}

/** RECT 值传递版, 供 ICoreWebView2Controller::put_Bounds。 */
@Structure.FieldOrder("left", "top", "right", "bottom")
internal class RectValue : Structure(), Structure.ByValue {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0
}

/* ---- JNA 回调签名 (WebView2 handler 的 Invoke 只有下面两种形状) ---- */

internal interface ComQueryInterfaceCb : StdCallCallback {
    fun callback(self: Pointer, riid: Pointer?, ppv: Pointer?): Int
}

internal interface ComRefCb : StdCallCallback {
    fun callback(self: Pointer): Int
}

/** Invoke(this, HRESULT errorCode, IXxx* result) —— 各类 CompletedHandler。 */
internal interface ComInvokeResultCb : StdCallCallback {
    fun callback(self: Pointer, errorCode: Int, result: Pointer?): Int
}

/** Invoke(this, ICoreWebView2* sender, IXxxEventArgs* args) —— 各类 EventHandler。 */
internal interface ComInvokeEventCb : StdCallCallback {
    fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int
}

/**
 * Java 侧实现的 COM 回调对象。WebView2 的 handler 一律是 IUnknown + 单个 Invoke,
 * 故 vtable 固定 4 项。
 *
 * 引用计数返回常量 1 并由本对象的 Java 强引用兜底生命周期 (调用方需持有到 native 用完为止),
 * QueryInterface 直接回自身 —— WebView2 只会用该 handler 自己的 IID 查询。
 */
internal class ComHandler(invoke: Callback) {

    private val queryInterface = object : ComQueryInterfaceCb {
        override fun callback(self: Pointer, riid: Pointer?, ppv: Pointer?): Int {
            ppv?.setPointer(0, self)
            return S_OK
        }
    }

    private val addRef = object : ComRefCb {
        override fun callback(self: Pointer): Int = 1
    }

    private val release = object : ComRefCb {
        override fun callback(self: Pointer): Int = 1
    }

    // 强引用: 回调蹦床被 GC 后 native 侧调用即崩
    private val methods: List<Callback> = listOf(queryInterface, addRef, release, invoke)
    private val vtable = Memory(methods.size * POINTER_SIZE)
    private val instance = Memory(POINTER_SIZE)

    init {
        methods.forEachIndexed { index, callback ->
            vtable.setPointer(index * POINTER_SIZE, CallbackReference.getFunctionPointer(callback))
        }
        instance.setPointer(0, vtable)
    }

    val pointer: Pointer get() = instance
}
