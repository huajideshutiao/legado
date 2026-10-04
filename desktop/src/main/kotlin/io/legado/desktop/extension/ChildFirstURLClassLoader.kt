// JVM 版扩展类加载器: child-first (子优先) 语义对齐 Android 端 DelegateLastClassLoaderCompat ——
// 扩展 dex 内自带的类 (含混淆短名) 一律从扩展 jar 解析, 仅 boot classpath 前缀强制交 parent,
// 扩展引用的宿主兼容层 (eu.kanade.tachiyomi.** / okhttp / injekt / android.* stub) 从 parent 落地。
package io.legado.desktop.extension

import java.net.URL
import java.net.URLClassLoader

class ChildFirstURLClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let {
            if (resolve) resolveClass(it)
            return it
        }

        if (!name.startsWithAny(BOOT_PREFIXES)) {
            try {
                val self = findClass(name)
                if (resolve) resolveClass(self)
                return self
            } catch (_: ClassNotFoundException) {
                // 扩展 jar 没有该类, 落到 parent (宿主兼容层/依赖)
            }
        }
        return super.loadClass(name, resolve)
    }

    private fun String.startsWithAny(prefixes: List<String>): Boolean = prefixes.any { startsWith(it) }

    companion object {
        /** Android boot classpath 前缀: 这些前缀必须由 parent (JDK) 解析, 不允许扩展覆盖。 */
        private val BOOT_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.dom.", "org.xml.sax.", "org.ietf.jgss.",
        )
    }
}
