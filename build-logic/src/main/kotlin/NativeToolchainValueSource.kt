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
 * 桌面 JVM native 库的工具链探测 (cmake / MSVC nmake / llvm-mingw),
 * 供 modules/quickjs 与 desktop 的 smtc / wndchrome 三处 native 构建共用。
 *
 * 外部进程经 ExecOperations 执行, 结果由配置缓存计入指纹 (缓存复用时自动重查)。
 * 裸 ProcessBuilder 在配置期启动子进程是 CC 违规项 (官方配置缓存要求: Running External Processes),
 * 故探测一律收敛到本 ValueSource。Gradle 9.6 的 ValueSource: obtain() 无参,
 * 参数经 @Inject 注入, 不得引用 Project。
 */
abstract class NativeToolchainValueSource :
    ValueSource<NativeToolchainValueSource.Toolchain, NativeToolchainValueSource.Params> {

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
    ) : Serializable {
        /** 脚本可直接使用的工具链描述 (同一份 ValueSource 服务多个 native 任务)。 */
        fun toNativeToolchain(): NativeToolchain = NativeToolchain(
            cmake = cmake.ifEmpty { null },
            generator = generator.takeIf { it != "CMake default" },
            mingwBin = mingwBin.ifEmpty { null },
            hasNmake = hasNmake,
            fingerprint = fingerprint,
        )
    }

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Inject
    protected abstract val parameters: Params

    override fun obtain(): Toolchain {
        val isWindows = parameters.windowsHost.get()
        val cmake = findCmakeExecutable()
        val cmakeIdentity = if (cmake == null) "<missing>" else commandIdentity(listOf(cmake, "--version"))
        val hasNmake = isWindows && commandSucceeds(listOf("nmake", "/?"))
        // nmake 存在不代表 cl.exe 可用: 非 VS 开发者提示符下 nmake 能启动而 cl 缺失,
        // cmake 会选到 Visual Studio 生成器但构建失败。指纹里带上 cl 身份, 该状态变化能被发现。
        val msvcIdentity = if (isWindows && hasNmake) commandIdentity(listOf("cl")) else "<absent>"
        val mingwBin = if (isWindows && !hasNmake) findMingwBinDir() else null
        val generator = if (mingwBin != null) "MinGW Makefiles" else "CMake default"
        val compilerIdentity = when {
            mingwBin != null ->
                commandIdentity(listOf(File(mingwBin, "gcc.exe").absolutePath, "--version"))
            isWindows && hasNmake -> msvcIdentity
            else -> {
                val cc = parameters.envCc.orNull ?: "cc"
                val cxx = parameters.envCxx.orNull ?: "c++"
                "CC=$cc:${probeCompiler(cc)};CXX=$cxx:${probeCompiler(cxx)}"
            }
        }
        val fingerprint = listOf(
            cmake ?: "",
            cmakeIdentity,
            generator,
            mingwBin ?: "",
            msvcIdentity,
            if (isWindows && hasNmake) "msvc" else compilerIdentity,
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

    /**
     * 探测编译器身份 (版本号由首轮输出提取, 提取失败才回落 which 路径, 避免重复起子进程)。
     */
    private fun probeCompiler(binary: String): String {
        val identity = commandIdentity(listOf(binary, "--version"))
        val version = Regex("\\d+(?:\\.\\d+)+|clang-\\d+|LLVM\\s*\\d+").find(identity)?.value
        val fallback = if (version == null) identityOrPath(binary) else version
        return "$identity:$fallback"
    }

    /**
     * 无版本号输出的编译器 (Apple clang) 用真实路径区分, 路径变化 (Xcode 升级) 同样计入指纹。
     */
    private fun identityOrPath(name: String): String {
        val output = ByteArrayOutputStream()
        return try {
            execOperations.exec {
                commandLine("which", name)
                standardOutput = output
                errorOutput = output
            }
            val path = output.toString("UTF-8").trim()
            if (path.isEmpty()) "no-path" else "path=$path"
        } catch (_: Exception) {
            "no-path"
        }
    }

    /**
     * 供 [NativeToolchainValueSource.obtain] 判定 / 供脚本拼装 cmake 命令行的工具链描述。
     * 全部字段可序列化: 任务动作与配置缓存都不允许携带 Project / 进程句柄。
     */
    data class NativeToolchain(
        val cmake: String?,
        val generator: String?,
        val mingwBin: String?,
        val hasNmake: Boolean,
        val fingerprint: String,
    ) {
        /** cmake 命令行前缀: 非默认生成器时带 -G。 */
        fun configureCommand(): List<String> {
            val base = mutableListOf(cmake ?: error("cmake 未找到"))
            if (generator != null) base += listOf("-G", generator)
            return base
        }

        /** 把工具链目录前置到子进程 PATH (MinGW 时 cmake 需要在 PATH 上找到 gcc/make)。 */
        fun applyToolchainPath(environment: MutableMap<String, String>) {
            val binDir = mingwBin ?: return
            environment["PATH"] =
                binDir + File.pathSeparator + (environment["PATH"] ?: "")
        }

        /** 任务输入指纹: 工具链或其版本/路径变化即让 native 任务失效重建。 */
        fun fingerprintProperty(): String = fingerprint
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

    /** `where`/`which` 查 gcc 真实路径, 取所在 bin 目录。 */
    private fun gccParentDir(): String? {
        val output = ByteArrayOutputStream()
        val command = if (parameters.windowsHost.get()) listOf("where", "gcc") else listOf("which", "gcc")
        return try {
            execOperations.exec {
                commandLine(command)
                standardOutput = output
                errorOutput = output
            }
            output.toString("UTF-8").trim().split("[\r\n]".toRegex())[0].trim()
                .takeIf { it.isNotEmpty() && File(it).exists() }
                ?.let { File(it).parent }
        } catch (_: Exception) {
            null
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

        if (commandSucceeds(listOf("gcc", "--version"))) {
            val binDir = gccParentDir()
            if (binDir != null) return binDir
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
