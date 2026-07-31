package io.legado.app.help.update

/**
 * 更新目标平台: 决定 release 资产的后缀匹配与默认更新策略。
 *
 * 检测与执行是正交的两层 (见 [UpdateChecker] / [UpdateExecutor]), 本枚举只描述"平台是什么",
 * 不硬编码"用哪个渠道查、怎么装" —— 那由 [UpdateStrategies] 一张表配置。
 */
enum class UpdatePlatform {
    ANDROID, WINDOWS, MACOS, LINUX, IOS, OHOS;

    /** 本平台安装包后缀, 按优先级排列 (小写匹配)。 */
    val assetSuffixes: List<String>
        get() = when (this) {
            ANDROID -> listOf(".apk")
            WINDOWS -> listOf(".msi", ".exe", ".zip")
            MACOS -> listOf(".dmg", ".pkg")
            LINUX -> listOf(".deb", ".rpm", ".appimage")
            IOS -> listOf(".ipa")
            OHOS -> listOf(".hap")
        }
}

/**
 * 架构标记归一化: 把各端原生 ABI/arch 名映射为 release 资产名里用的短标记。
 *
 * - Android `Build.SUPPORTED_ABIS`: arm64-v8a → arm64, armeabi-v7a → armv7, x86_64 → x64
 * - JVM `os.arch`: amd64/x86_64 → x64, aarch64 → arm64, x86/i386 → x86
 * - iOS/鸿蒙: 真机恒 arm64
 */
object AbiTokens {

    fun normalize(abis: List<String>): List<String> = abis.map { normalize(it) }.distinct()

    fun normalize(abi: String): String {
        val a = abi.lowercase()
        return when {
            a.startsWith("arm64") || a == "aarch64" -> "arm64"
            a.startsWith("armeabi-v7") || a.startsWith("armv7") -> "armv7"
            a == "x86_64" || a == "amd64" || a == "x64" -> "x64"
            a == "i386" || a == "i686" || a == "x86" -> "x86"
            else -> a
        }
    }

    /** 资产名里表示"全架构/通用包"的标记。 */
    val universalTokens = listOf("all", "universal", "noarch")
}
