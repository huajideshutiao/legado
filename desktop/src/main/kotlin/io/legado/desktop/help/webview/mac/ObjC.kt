package io.legado.desktop.help.webview.mac

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import io.legado.app.constant.AppLog

/**
 * JNA + Objective-C runtime 直绑 (macOS 系统框架, 零体积增量)。
 *
 * 覆盖 WKWebView 需要的最小 ObjC 面: objc_msgSend 变参调用、NSString/NSArray/NSDictionary
 * 互转、ObjC block 构造 (completion handler 用)、动态子类 (WKNavigationDelegate /
 * NSWindowDelegate / toolbar target-action 用)。
 *
 * 线程模型: AppKit 必须跑在主线程 —— AWT 应用的 EDT 即 Cocoa 主线程 (AWT 启动时已初始化
 * NSApplication), 因此所有 WKWebView 操作经 [CocoaLoop] 投递到 EDT, 与 Compose/AWT 零冲突。
 *
 * # ObjC block 构造 ([ObjCBlock])
 * block 是内存中的结构体 (isa/flags/reserved/invoke/descriptor), invoke 指向 C 函数指针。
 * JNA 的 Callback 实例即可作 invoke; 系统对 completionHandler 属性 (copy) 赋值时会走
 * _Block_copy —— flags 不带 COPY_DISPOSE/SIGNATURE 时仅 memcpy + 换 malloc isa, 安全。
 * block 的调用约定: 第一个参数是 block 指针自身, 随后才是业务参数 (与 C 函数一致,
 * x86_64/arm64 相同)。
 *
 * # 动态子类 ([newDelegateClass])
 * objc_allocateClassPair + class_addMethod (imp 来自 imp_implementationWithBlock) +
 * objc_registerClassPair, 实例用 class_createInstance 直接创建 (NSObject init 空实现)。
 * 协议方法经固定签名 block 分发回 Kotlin。
 *
 * # 内存管理
 * 每个主线程任务外包 @autoreleasepool (objc_autoreleasePoolPush/Pop), autoreleased 对象
 * (requestWithURL: 等) 及时释放; alloc/init 的 retained 对象 (WKWebView/NSWindow/delegate)
 * 由会话持有, 销毁时显式 release。
 */
internal object ObjC {

    @Volatile
    private var objcLoaded = false

    private fun objc(): LibObjC {
        if (!objcLoaded) {
            objcLib = Native.load("objc", LibObjC::class.java)
            objcLoaded = true
        }
        return objcLib
    }

    private lateinit var objcLib: LibObjC

    interface LibObjC : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, vararg args: Any?): Pointer
        fun objc_msgSendInt(receiver: Pointer?, selector: Pointer?, vararg args: Any?): Int
        fun objc_msgSendLong(receiver: Pointer?, selector: Pointer?, vararg args: Any?): Long
        fun objc_msgSendDouble(receiver: Pointer?, selector: Pointer?, vararg args: Any?): Double
        fun objc_getClass(name: String): Pointer
        fun objc_getProtocol(name: String): Pointer
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Int): Pointer
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, sel: Pointer, imp: Pointer, types: String): Int
        fun class_createInstance(cls: Pointer, extraBytes: Int): Pointer
        fun imp_implementationWithBlock(block: Pointer): Pointer
        fun objc_autoreleasePoolPush(): Pointer
        fun objc_autoreleasePoolPop(pool: Pointer)
        fun objc_disposeClassPair(cls: Pointer)
    }

    // ==================== 基础调用 ====================

    fun cls(name: String): Pointer = objc().objc_getClass(name)

    fun sel(name: String): Pointer = objc().sel_registerName(name)

    /** ObjC BOOL 返回统一走 Int (BOOL 只保证 al, JNA Boolean 按 int 读不可靠)。 */
    fun bool(obj: Pointer, selector: String, vararg args: Any?): Boolean =
        objc().objc_msgSendInt(obj, sel(selector), *args) != 0

    fun ptr(obj: Pointer?, selector: String, vararg args: Any?): Pointer? =
        objc().objc_msgSend(obj, sel(selector), *args)

    fun int(obj: Pointer, selector: String, vararg args: Any?): Int =
        objc().objc_msgSendInt(obj, sel(selector), *args)

    fun long(obj: Pointer, selector: String, vararg args: Any?): Long =
        objc().objc_msgSendLong(obj, sel(selector), *args)

    fun dbl(obj: Pointer, selector: String, vararg args: Any?): Double =
        objc().objc_msgSendDouble(obj, sel(selector), *args)

    fun void(obj: Pointer?, selector: String, vararg args: Any?) {
        objc().objc_msgSend(obj, sel(selector), *args)
    }

    /** 类方法调用: [obj] 为类对象。 */
    fun clsPtr(cls: Pointer, selector: String, vararg args: Any?): Pointer? =
        objc().objc_msgSend(cls, sel(selector), *args)

    // ==================== Foundation 互转 ====================

    private val nsStringCls by lazy { cls("NSString") }
    private val nsArrayCls by lazy { cls("NSArray") }
    private val nsDictionaryCls by lazy { cls("NSDictionary") }
    private val nsNullCls by lazy { cls("NSNull") }
    private val nsNumberCls by lazy { cls("NSNumber") }

    /** Java String → NSString* (autoreleased, 由调用方 pool 管理)。 */
    fun ns(value: String): Pointer = clsPtr(nsStringCls, "stringWithUTF8String:", value)!!

    /** NSString* / NSNumber* / NSNull* → Java String? (null 映射 null)。 */
    fun fromId(obj: Pointer?): String? {
        if (obj == null || obj == Pointer.NULL) return null
        val cls = objc().objc_msgSend(obj, sel("class"))
        return when {
            cls == nsStringCls || objc().objc_msgSendInt(
                obj,
                sel("isKindOfClass:"),
                nsStringCls
            ) != 0 -> {
                val cstr = objc().objc_msgSend(obj, sel("UTF8String"))
                if (cstr == null || cstr == Pointer.NULL) null else cstr.getString(0)
            }

            objc().objc_msgSendInt(obj, sel("isKindOfClass:"), nsNumberCls) != 0 -> fromId(
                ptr(
                    obj,
                    "stringValue"
                )
            )

            else -> null
        }
    }

    /** NSArray* 长度。 */
    fun arrayCount(array: Pointer): Int = objc().objc_msgSendLong(array, sel("count")).toInt()

    /** NSArray* 下标取元素 (id)。 */
    fun arrayObject(array: Pointer, index: Int): Pointer? =
        objc().objc_msgSend(array, sel("objectAtIndex:"), index.toLong())

    /** 单键 NSDictionary*: [NSDictionary dictionaryWithObject:forKey:]。 */
    fun dict1(key: String, value: Pointer): Pointer =
        clsPtr(nsDictionaryCls, "dictionaryWithObject:forKey:", value, ns(key))!!

    /** NSArray* (元素为 id): [NSArray arrayWithObjects:count:] (不依赖 nil 结尾)。 */
    fun nsArray(vararg objs: Pointer): Pointer {
        val mem = Memory(8L * objs.size)
        objs.forEachIndexed { i, p -> mem.setPointer(i * 8L, p) }
        return clsPtr(nsArrayCls, "arrayWithObjects:count:", mem, objs.size.toLong())!!
    }

    /** 属性值读取 (id 返回)。 */
    fun property(obj: Pointer, name: String): Pointer? = objc().objc_msgSend(obj, sel(name))

    fun propertyString(obj: Pointer, name: String): String? = fromId(property(obj, name))

    /** 属性设置。 */
    fun setProperty(obj: Pointer, name: String, value: Pointer?) {
        objc().objc_msgSend(obj, sel("set${name.replaceFirstChar { it.uppercase() }}:"), value)
    }

    // ==================== 结构体 (16 字节内, 寄存器传递, 避开 objc_msgSend_stret) ====================

    class NSPoint : Structure(), Structure.ByValue {
        @JvmField
        var x: Double = 0.0
        @JvmField
        var y: Double = 0.0
        override fun getFieldOrder() = listOf("x", "y")
    }

    class NSSize : Structure(), Structure.ByValue {
        @JvmField
        var width: Double = 0.0
        @JvmField
        var height: Double = 0.0
        override fun getFieldOrder() = listOf("width", "height")
    }

    /** 32 字节 (>16), 仅作**传参**用 (返回结构体才需要 objc_msgSend_stret, 本模块避开)。 */
    class NSRect : Structure(), Structure.ByValue {
        @JvmField
        var origin: NSPoint = NSPoint()
        @JvmField
        var size: NSSize = NSSize()
        override fun getFieldOrder() = listOf("origin", "size")
    }

    fun point(x: Double, y: Double) = NSPoint().apply { this.x = x; this.y = y }

    fun size(w: Double, h: Double) = NSSize().apply { this.width = w; this.height = h }

    // ==================== ObjC block 构造 ====================

    /** block 结构体 isa: _NSConcreteStackBlock (libSystem 导出)。 */
    private val stackBlockIsa: Long by lazy {
        runCatching {
            Pointer.nativeValue(
                NativeLibrary.getProcess().getGlobalVariableAddress("_NSConcreteStackBlock")
            )
        }.getOrDefault(0L)
    }

    /**
     * 构造一个无捕获变量的 stack block。
     * [invoke] 的 JNA Callback 签名必须与 block 调用约定一致 (第一参数为 block 指针自身)。
     */
    class ObjCBlock(
        invoke: Callback,
    ) {
        private val mem = Memory(32 + 32)

        init {
            // block literal: isa(8) flags(4) reserved(4) invoke(8) descriptor(8) = 32
            mem.setLong(0, stackBlockIsa)
            mem.setInt(8, 0) // flags: 无 COPY_DISPOSE/SIGNATURE
            mem.setInt(12, 0)
            mem.setPointer(16, CallbackReference.getFunctionPointer(invoke))
            mem.setLong(24, Pointer.nativeValue(mem) + 32)
            // descriptor: reserved(8) size(8) = 16 (无 copy/dispose/signature)
            mem.setLong(32, 0)
            mem.setLong(40, 32)
        }

        fun pointer(): Pointer = mem
    }

    // ==================== 动态子类 ====================

    /**
     * 创建 NSObject 动态子类并返回一个实例。
     * [methods] 的 value 为方法 type encoding (如 "v@:@@"); 每个方法由 [impl] 分发
     * (参数: 方法名, self, 其余参数)。
     */
    fun newDelegateClass(
        className: String,
        methods: List<Pair<String, String>>,
        impl: (method: String, self: Pointer, args: List<Any?>) -> Unit,
    ): Pointer {
        val lib = objc()
        val cls = lib.objc_allocateClassPair(cls("NSObject"), className, 0)
        val keepAlive = ArrayList<ObjCBlock>()
        for ((name, types) in methods) {
            val invoke = object : Callback {
                fun invoke(
                    block: Pointer,
                    self: Pointer,
                    a: Pointer,
                    b: Pointer,
                    c: Pointer
                ): Pointer? {
                    val args = listOf(a, b, c).filter { it != null && it != Pointer.NULL }
                    runCatching { impl(name, self, args) }
                        .onFailure { AppLog.put("ObjC delegate 回调异常 ($name)", it) }
                    return null
                }
            }
            val block = ObjCBlock(invoke)
            keepAlive.add(block)
            val imp = lib.imp_implementationWithBlock(block.pointer())
            lib.class_addMethod(cls, sel(name), imp, types)
        }
        lib.objc_registerClassPair(cls)
        return lib.class_createInstance(cls, 0)
    }

    /** 主线程任务自动释放池包装 (JNA 调用无系统 pool, autoreleased 对象会泄漏)。 */
    fun <T> withAutoreleasePool(block: () -> T): T {
        val pool = objc().objc_autoreleasePoolPush()
        try {
            return block()
        } finally {
            objc().objc_autoreleasePoolPop(pool)
        }
    }
}
