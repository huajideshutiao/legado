package com.script.quickjs

import com.script.quickjs.JsSecurityPolicy.isClassVisible
import com.script.quickjs.JsSecurityPolicy.protectedClassNamesMatcher
import com.script.quickjs.JsSecurityPolicy.protectedClasses
import com.script.quickjs.JsSecurityPolicy.systemClassProtectedName
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Member
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.Collections

/**
 * 引擎无关的 JS 安全名单策略。
 *
 * 从 modules/rhino/RhinoClassShutter.kt 抽取,移除 Rhino 类型依赖。
 * 三层防护:
 * 1. [protectedClassNamesMatcher]: 类名精确/前缀匹配(黑名单)
 * 2. [protectedClasses]: isAssignableFrom 类型检查(黑名单)
 * 3. [systemClassProtectedName]: System 类特殊方法屏蔽(load/loadLibrary/exit)
 *
 * dangerousApi = true 时全部放行(由 BaseSource.enableDangerousApi 控制)。
 */
object JsSecurityPolicy {

    /**
     * android.content.Context 类引用 (跨平台): Android 端存在, 桌面 JVM 不存在。
     * 用反射加载, null 表示当前平台无此类 (桌面 JVM 黑名单跳过 Context 检查)。
     */
    private val androidContextClass: Class<*>? by lazy {
        runCatching { Class.forName("android.content.Context") }.getOrNull()
    }

    private val protectedClassNamesMatcher by lazy {
        listOf(
            // 2026-08-04: 用户确认保留此安全收紧(异于原版, 有意为之)。
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.net.URLClassLoader",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessImpl",
            "java.lang.UNIXProcess",
            "java.io.File",
            "java.io.FileDescriptor",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.PrintStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.PrintWriter",
            "java.io.UnixFileSystem",
            "java.io.RandomAccessFile",
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream",
            "java.security.AccessController",
            "java.nio.file.Paths",
            "java.nio.file.Files",
            "java.nio.file.FileSystems",
            "java.util.Formatter",
            "sun.misc.Unsafe",
            "android.content.Intent",
            "android.provider.Settings",
            "android.app.ActivityThread",
            "android.app.AppGlobals",
            "android.os.Looper",
            "android.os.Process",
            "android.os.FileUtils",

            "cn.hutool.core.lang.JarClassLoader",
            "cn.hutool.core.lang.Singleton",
            "cn.hutool.core.util.RuntimeUtil",
            "cn.hutool.core.util.ClassLoaderUtil",
            "cn.hutool.core.util.ReflectUtil",
            "cn.hutool.core.util.SerializeUtil",
            "cn.hutool.core.util.ClassUtil",
            "io.legado.app.data.AppDatabase",
            "io.legado.app.data.AppDatabase_Impl",
            "io.legado.app.data.AppDatabaseKt",
            "io.legado.app.utils.ContextExtensionsKt",
            "androidx.core.content.FileProvider",
            "splitties.init.AppCtxKt",
            "okio.JvmSystemFileSystem",
            "okio.JvmFileHandle",
            "okio.NioSystemFileSystem",
            "okio.NioFileSystemFileHandle",
            "okio.Path",

            "android.system",
            "android.database",
            "androidx.sqlite.db",
            "androidx.room",
            "androidx.room3",
            "cn.hutool.core.io",
            "cn.hutool.core.bean",
            "cn.hutool.core.lang.reflect",
            "dalvik.system",
            "java.nio.file",
            "java.lang.reflect",
            "java.lang.invoke",
            "io.legado.app.data.dao",
            "com.script",
            "org.mozilla",
            "sun",
            "libcore",
        ).let { ClassNameMatcher(it) }
    }

    /**
     * System 类特殊屏蔽的方法名(load/loadLibrary/exit)。
     */
    val systemClassProtectedName: Set<String> by lazy {
        Collections.unmodifiableSet(hashSetOf("load", "loadLibrary", "exit"))
    }

    /**
     * Class 类特殊屏蔽的方法名。
     */
    val classProtectedName: Set<String> by lazy {
        Collections.unmodifiableSet(
            hashSetOf(
                "getClassLoader",
                "newInstance",
                "asSubclass",
                "cast",
                "getProtectionDomain",
                "getResource",
                "getResourceAsStream",
                "getConstructor",
                "getConstructors",
                "getDeclaredConstructor",
                "getDeclaredConstructors",
                "getField",
                "getFields",
                "getDeclaredField",
                "getDeclaredFields",
                "getMethod",
                "getMethods",
                "getDeclaredMethod",
                "getDeclaredMethods"
            )
        )
    }

    private val protectedClasses by lazy {
        // 跨平台: android.content.Context 在桌面 JVM 不存在, 用反射按需加载
        // (桌面端常见 JS 引擎场景无 Android Context, 但保留黑名单条目以兼容 Android 行为)
        val list = mutableListOf(
            ClassLoader::class.java,
            Member::class.java,
            ObjectInputStream::class.java,
            ObjectOutputStream::class.java,
            okio.FileSystem::class.java,
            okio.FileHandle::class.java,
            okio.Path::class.java
        )
        // android.content.Context: 桌面 JVM 不存在, 反射加载, 存在则加入黑名单
        androidContextClass?.let { list.add(it) }
        list.add(FileSystem::class.java)
        list.add(Path::class.java)
        list.toTypedArray()
    }

    /**
     * 检查类名是否允许访问。
     *
     * @param fullClassName 完整类名(如 "java.lang.System")
     * @param dangerousApi 是否旁路安全名单
     */
    fun isClassVisible(fullClassName: String, dangerousApi: Boolean): Boolean {
        if (dangerousApi) return true
        return !protectedClassNamesMatcher.match(fullClassName)
    }

    /**
     * 检查对象是否允许访问(综合类名 + 类型检查)。
     */
    fun isObjectVisible(obj: Any, dangerousApi: Boolean): Boolean {
        if (dangerousApi) return true
        // NativeObject 是 JS 对象跨语言存活的载体(cache.putMemory / evalJS 返回值),
        // com.script 黑名单本意是防反射引擎内部,不该殃及数据。
        if (obj is NativeObject) return true
        when (obj) {
            is ClassLoader,
            is Member,
            is ObjectInputStream,
            is ObjectOutputStream,
            is okio.FileSystem,
            is okio.FileHandle,
            is okio.Path -> return false
        }
        // android.content.Context: 桌面 JVM 不存在, 通过反射 isInstance 检查 (Android 端走此分支)
        androidContextClass?.takeIf { it.isInstance(obj) }?.let { return false }
        when (obj) {
            is FileSystem,
            is Path -> return false
        }
        return isClassVisible(obj.javaClass.name, false)
    }

    /**
     * 检查 Class 是否允许访问(基于 isAssignableFrom 类型检查)。
     */
    fun isClassVisible(clazz: Class<*>, dangerousApi: Boolean): Boolean {
        if (dangerousApi) return true
        protectedClasses.forEach {
            if (it.isAssignableFrom(clazz)) {
                return false
            }
        }
        return isClassVisible(clazz.name, false)
    }

    /**
     * 检查方法是否允许调用(用于 System 类方法屏蔽)。
     */
    fun isMethodVisible(className: String, methodName: String, dangerousApi: Boolean): Boolean {
        if (dangerousApi) return true
        if (className == "java.lang.System" && methodName in systemClassProtectedName) {
            return false
        }
        if (className == "java.lang.Class" && methodName in classProtectedName) {
            return false
        }
        return true
    }
}
