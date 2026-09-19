package io.legado.buildlogic

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import javax.inject.Inject

/**
 * QuickJS 桌面 JVM native 库的工具链探测 (cmake / MSVC nmake / llvm-mingw)。
 *
 * 外部进程经 ExecOperations 执行, 结果由配置缓存计入指纹 (缓存复用时自动重查)。
 * 裸 ProcessBuilder 在配置期启动子进程是 CC 违规项, 故从 modules/quickjs/build.gradle 迁入。
 * Gradle 9.6 的 ValueSource: obtain() 无参, 参数经 @Inject 注入, 不得引用 Project。
 */
abstract class QuickjsNativeToolchainValueSource :
    ValueSource<QuickjsNativeToolchainValueSource.Toolchain, QuickjsNativeToolchainValueSource.Params> {

    interface Params : ValueSourceParameters {
        // 勿以 is 开头命名 Property 抽象方法: Gradle 装饰器按 JavaBean 规范保留 is* 前缀, 会拒生成
        val windowsHost: Property<Boolean>
        /** gradle property legado.cmake.path */
        val cmakeProp: Property<String>
        /** gradle property legado.mingw.path */
        val mingwProp: Property<String>
        /** local.properties 的 sdk.dir (cmake 兜底搜索根) */
        val sdkDir: Property<String>
        val userHome: Property<String>
        val envCc: Property<String>
        val envCxx: Property<String>
        val javaHome: Property<String>
    }

    /** 全 String/Boolean 字段, 满足配置缓存的值序列化要求 */
    data class Toolchain(
        val cmake: String,
        val cmakeIdentity: String,
        val hasNmake: Boolean,
        val mingwBin: String,
        val generator: String,
        val compilerIdentity: String,
        val fingerprint: String,
        val id: String,
    ) : Serializable

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Inject
    protected abstract val parameters: Params

    override fun obtain(): Toolchain {
        val isWindows = parameters.windowsHost.get()
        val cmake = findCmakeExecutable()
        val cmakeIdentity = if (cmake == null) "<missing>" else commandIdentity(listOf(cmake, "--version"))
        val hasNmake = isWindows && commandSucceeds(listOf("nmake", "/?"))
        val mingwBin = if (isWindows && !hasNmake) findMingwBinDir() else null
        val generator = if (mingwBin != null) "MinGW Makefiles" else "CMake default"
        val compilerIdentity = when {
            mingwBin != null ->
                commandIdentity(listOf(File(mingwBin, "gcc.exe").absolutePath, "--version"))
            isWindows && hasNmake ->
                commandIdentity(listOf("cl"))
            else -> {
                val cc = parameters.envCc.orNull ?: "cc"
                val cxx = parameters.envCxx.orNull ?: "c++"
                "CC=${cc}:${commandIdentity(listOf(cc, "--version"))};" +
                    "CXX=${cxx}:${commandIdentity(listOf(cxx, "--version"))}"
            }
        }
        val fingerprint = listOf(
            cmake ?: "",
            cmakeIdentity,
            generator,
            mingwBin ?: "",
            compilerIdentity,
            "java=" + parameters.javaHome.get(),
        ).joinToString("|")
        val hash = Integer.toUnsignedString(fingerprint.hashCode(), 16)
        val kind = when {
            mingwBin != null -> "mingw"
            hasNmake -> "msvc"
            else -> "default"
        }
        return Toolchain(
            cmake = cmake ?: "",
            cmakeIdentity = cmakeIdentity,
            hasNmake = hasNmake,
            mingwBin = mingwBin ?: "",
            generator = generator,
            compilerIdentity = compilerIdentity,
            fingerprint = fingerprint,
            id = "$kind-$hash",
        )
    }

    /**
     * Gradle 9 移除了 ignoreExitValue: exec 非零退出/进程缺失一律抛异常, 用 try/catch 区分探测成败。
     */
    private fun commandSucceeds(command: List<String>): Boolean {
        return try {
            execOperations.exec {
                commandLine(command)
                standardOutput = ByteArrayOutputStream()
                errorOutput = ByteArrayOutputStream()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun commandIdentity(command: List<String>): String {
        return try {
            val output = ByteArrayOutputStream()
            execOperations.exec {
                commandLine(command)
                standardOutput = output
                errorOutput = output
            }
            "ok;${output.toString("UTF-8").trim()}"
        } catch (error: Exception) {
            "unavailable:${error.javaClass.simpleName}"
        }
    }

    private fun findCmakeExecutable(): String? {
        parameters.cmakeProp.orNull?.let { propPath ->
            if (File(propPath).exists()) return propPath
        }

        if (commandSucceeds(listOf("cmake", "--version"))) return "cmake"

        return try {
            val sdkDir = parameters.sdkDir.orNull
            if (sdkDir != null) {
                val cmakeBaseDir = File(sdkDir, "cmake")
                if (cmakeBaseDir.exists()) {
                    cmakeBaseDir.listFiles()
                        ?.sortedDescending()
                        ?.firstNotNullOfOrNull { dir ->
                            sequenceOf(File(dir, "bin/cmake.exe"), File(dir, "bin/cmake"))
                                .firstOrNull(File::exists)
                                ?.absolutePath
                        }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findMingwBinDir(): String? {
        parameters.mingwProp.orNull?.let { propPath ->
            if (File(propPath, "gcc.exe").exists()) return propPath
        }

        try {
            if (commandSucceeds(listOf("gcc", "--version"))) {
                val output = ByteArrayOutputStream()
                execOperations.exec {
                    commandLine("where", "gcc")
                    standardOutput = output
                    errorOutput = output
                }
                val gccPath = output.toString("UTF-8").trim().split("[\r\n]".toRegex())[0].trim()
                if (gccPath.isNotEmpty() && File(gccPath).exists()) {
                    return File(gccPath).parent
                }
            }
        } catch (_: Exception) {
        }

        return try {
            val wingetPkgBase = File(parameters.userHome.get(), "AppData/Local/Microsoft/WinGet/Packages")
            if (wingetPkgBase.exists()) {
                wingetPkgBase.listFiles()
                    ?.filter { it.name.lowercase().contains("llvm-mingw") }
                    ?.sortedDescending()
                    ?.firstNotNullOfOrNull { pkgDir ->
                        pkgDir.listFiles()
                            ?.filter(File::isDirectory)
                            ?.firstNotNullOfOrNull { subDir ->
                                File(subDir, "bin").takeIf { File(it, "gcc.exe").exists() }?.absolutePath
                            }
                    }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
