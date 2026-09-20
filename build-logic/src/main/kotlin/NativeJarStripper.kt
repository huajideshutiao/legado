package io.legado.buildlogic

import org.gradle.api.GradleException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 打包期剔除 jar 内非构建平台的 native 条目 (桌面 release 链)。
 *
 * 根因 (实测于 3.26.09150109 镜像): 三方 jar 把全平台 native 装同一个 artifact —
 *  - sqlite-bundled-jvm 2.7.0: natives/{windows_x64,linux_x64,linux_arm64,osx_arm64} 四份
 *    sqliteJni, 解压 7.17MiB, 非本平台三份在 jar 内仍占 2.66MiB;
 *  - jna 5.19.1: com/sun/jna/ 下 27 份 jnidispatch (aix/sunos/freebsd/loongarch64/s390x…),
 *    解压 5.04MiB, 本机只认 win32-x86-64 一份。
 * 这些条目在 jar 内已 deflate, jpackage 外层再压不动, 所以每出一个平台的包就把其它平台的字节
 * 照抄一遍 —— Windows 安装包里约 4MiB 是死字节。
 *
 * 放在 build-logic 而非脚本里: 配置缓存下任务动作不得引用脚本级对象 (含脚本方法),
 * 否则整次构建在存储缓存阶段失败 (官方 config_cache:requirements:disallowed_types)。
 */
object NativeJarStripper {

    private val foreignNativeRoots = listOf("natives/", "com/sun/jna/")

    private val knownNativeTokens = setOf(
        // sqlite-bundled: natives/<token>/
        "windows_x64", "windows_arm64", "linux_x64", "linux_arm64", "osx_x64", "osx_arm64",
        // jna: com/sun/jna/<token>/
        "win32-x86", "win32-x86-64", "win32-aarch64", "win32-amd64",
        "linux-x86", "linux-x86-64", "linux-arm", "linux-armel", "linux-aarch64",
        "linux-ppc", "linux-ppc64", "linux-ppc64le", "linux-mips64el", "linux-loongarch64",
        "linux-riscv64", "linux-s390x", "linux-x86_64",
        "darwin-x86", "darwin-x86-64", "darwin-aarch64", "darwin-universal",
        "sunos-x86", "sunos-x86-64", "sunos-sparc", "sunos-sparcv9",
        "freebsd-x86", "freebsd-x86-64", "freebsd-arm", "freebsd-ia64",
        "openbsd-x86", "openbsd-x86-64", "netbsd-x86", "netbsd-x86-64",
        "dragonflybsd-x86-64", "kfreebsd-i386", "kfreebsd-x86-64", "aix-ppc", "aix-ppc64",
    )

    /** 判定口径: 只有 token 命中已知平台目录名单、且不是本平台的那份才删。 */
    fun isForeignNativeEntry(name: String, keep: Set<String>): Boolean {
        val root = foreignNativeRoots.firstOrNull { name.startsWith(it) } ?: return false
        val token = name.substring(root.length).substringBefore('/')
        return token in knownNativeTokens && token !in keep
    }

    /**
     * 就地重写目录下的 jar, 只留构建平台自己的 native 目录。
     *
     * 重写后未变小则保留原件, 不静默接受负收益; 目录不存在直接抛 —— 插件输出路径变了必须暴露,
     * 否则静默跳过后白背的体积又回来了。
     */
    fun strip(jarDir: File, keep: Set<String>, report: (String) -> Unit) {
        if (!jarDir.isDirectory) {
            throw GradleException(
                "剔除跨平台 native 失败: ProGuard 输出目录不存在 $jarDir —— " +
                    "插件输出路径已变, 不得静默跳过 (否则白背体积又回来)"
            )
        }
        var totalSaved = 0L
        var rewritten = 0
        val jars = jarDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy { it.name } ?: emptyList()
        for (jar in jars) {
            val dropNames = HashSet<String>()
            ZipFile(jar).use { zf ->
                zf.entries().asSequence().forEach { e ->
                    if (!e.isDirectory && isForeignNativeEntry(e.name, keep)) dropNames += e.name
                }
            }
            if (dropNames.isEmpty()) continue
            val tmp = File(jar.parentFile, jar.name + ".stripping")
            ZipFile(jar).use { zf ->
                ZipOutputStream(
                    BufferedOutputStream(FileOutputStream(tmp))
                ).use { out ->
                    out.setLevel(Deflater.BEST_COMPRESSION)
                    zf.entries().asSequence().forEach { e ->
                        if (e.name in dropNames) return@forEach
                        // 拷贝原条目元数据 (含 size/compressedSize/crc/time/method);
                        // 对 STORED 条目保留原始 size 与 crc, 满足 ZipOutputStream.putNextEntry 对 STORED 的校验契约
                        val ne = ZipEntry(e)
                        out.putNextEntry(ne)
                        zf.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            val after = tmp.length()
            val original = jar.length()
            if (after >= original) {
                // 重写后反而变大 (理论上只会变小: 删的都是已压缩条目) → 保留原件
                tmp.delete()
                report("[native-strip] ${jar.name} 重写后未变小, 保留原件")
                continue
            }
            if (!jar.delete() || !tmp.renameTo(jar)) {
                throw GradleException("[native-strip] 替换 jar 失败: ${jar.absolutePath}")
            }
            totalSaved += original - after
            rewritten++
            report(
                "[native-strip] ${jar.name}: $original B → $after B (剔 ${dropNames.size} 个非本平台 native 条目)"
            )
        }
        report(
            "[legado-desktop] 跨平台 native 剔除完成: 保留目录 $keep, 重写 $rewritten 个 jar, 省 ${totalSaved / 1024} KB"
        )
    }

    /** 本平台要保留的 native 目录 token。 */
    fun tokensToKeep(os: NativePlatformOs, arm64: Boolean): Set<String> = when (os) {
        NativePlatformOs.WINDOWS ->
            if (arm64) setOf("win32-aarch64", "windows_arm64")
            else setOf("win32-x86-64", "win32-amd64", "windows_x64")
        NativePlatformOs.MACOS ->
            if (arm64) setOf("darwin-aarch64", "osx_arm64")
            else setOf("darwin-x86-64", "darwin-x86", "osx_x64")
        NativePlatformOs.LINUX ->
            if (arm64) setOf("linux-aarch64", "linux_arm64")
            else setOf("linux-x86-64", "linux-x86_64", "linux_x64")
    }
}

/** 任务动作可序列化的平台枚举 (不携带 OperatingSystem 实例)。 */
enum class NativePlatformOs {
    WINDOWS,
    MACOS,
    LINUX,
}
